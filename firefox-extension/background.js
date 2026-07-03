const MAX_PDF_BYTES = 50 * 1024 * 1024;
const STORAGE_KEY = "armedPdfCaptures";
const capturesByTab = new Map();

const persistCaptures = async () => {
  const captures = Array.from(capturesByTab.entries())
    .filter(([, capture]) => capture.state === "ARMED")
    .map(([tabId, capture]) => [tabId, capture]);
  await browser.storage.local.set({ [STORAGE_KEY]: captures });
};

const restoreCaptures = async () => {
  const stored = await browser.storage.local.get(STORAGE_KEY);
  for (const [tabId, capture] of stored[STORAGE_KEY] || []) {
    if (Date.parse(capture.expiresAt) > Date.now()) {
      capturesByTab.set(Number(tabId), capture);
    }
  }
  await persistCaptures();
};

const sendStatus = async (capture, status, extra = {}) => {
  const payload = {
    captureId: capture.captureId,
    paperId: capture.paperId,
    status,
    ...extra
  };
  try {
    await browser.tabs.sendMessage(capture.monitorTabId, {
      type: "CAPTURE_STATUS",
      status: payload
    });
  } catch {
    // The authenticated status endpoint remains available if the tab was reloaded.
  }
};

const notify = async (title, message) => {
  try {
    await browser.notifications.create({
      type: "basic",
      title,
      message
    });
  } catch {
    // Notifications are supplementary; capture status is also shown in Paper Monitor.
  }
};

const disarm = async (tabId) => {
  capturesByTab.delete(tabId);
  await persistCaptures();
};

const failureUrl = (capture) => capture.uploadUrl.replace(/\/upload$/, "/failure");

const reportFailure = async (capture, error) => {
  const message = error instanceof Error ? error.message : String(error || "PDF capture failed");
  try {
    await fetch(failureUrl(capture), {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + capture.uploadToken,
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
      },
      body: new URLSearchParams({ error: message })
    });
  } catch {
    // The token will expire even if Paper Monitor is temporarily unreachable.
  }
  await sendStatus(capture, "FAILED", { error: message });
  await notify("Paper Monitor PDF capture failed", message);
};

const headerValue = (headers, name) => {
  const normalizedName = name.toLowerCase();
  return (headers || []).find((header) => header.name.toLowerCase() === normalizedName)?.value || "";
};

const responseLooksLikePdf = (details) => {
  const contentType = headerValue(details.responseHeaders, "content-type").toLowerCase();
  const contentDisposition = headerValue(details.responseHeaders, "content-disposition").toLowerCase();
  const url = (details.url || "").toLowerCase();
  return contentType.includes("application/pdf")
    || contentDisposition.includes(".pdf")
    || /\.pdf(?:$|[?#])/.test(url);
};

const filenameFromResponse = (details, capture) => {
  const disposition = headerValue(details.responseHeaders, "content-disposition");
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  let filename = encoded
    ? decodeURIComponent(encoded[1])
    : plain
      ? plain[1]
      : "";
  if (!filename) {
    try {
      filename = decodeURIComponent(new URL(details.url).pathname.split("/").pop() || "");
    } catch {
      filename = "";
    }
  }
  if (!filename.toLowerCase().endsWith(".pdf")) {
    filename = (capture.paperTitle || "paper") + ".pdf";
  }
  return filename
    .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, "-")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 240) || "paper.pdf";
};

const startsWithPdfMagic = async (blob) => {
  if (blob.size < 4) {
    return false;
  }
  const bytes = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  return bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46;
};

const uploadCapture = async (tabId, capture, details, chunks, totalBytes, exceededLimit) => {
  if (exceededLimit) {
    throw new Error("The PDF exceeds Paper Monitor's 50 MiB capture limit");
  }
  const blob = new Blob(chunks, { type: "application/pdf" });
  if (blob.size !== totalBytes || !(await startsWithPdfMagic(blob))) {
    throw new Error("The intercepted response is not a valid PDF");
  }

  const fileName = filenameFromResponse(details, capture);
  const form = new FormData();
  form.append("pdf", blob, fileName);
  form.append("sourceUrl", details.url);
  await sendStatus(capture, "UPLOADING", { message: "Uploading captured PDF..." });

  const response = await fetch(capture.uploadUrl, {
    method: "POST",
    headers: {
      "Authorization": "Bearer " + capture.uploadToken
    },
    body: form
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Paper Monitor rejected the captured PDF");
  }
  const status = await response.json();
  await sendStatus(capture, "UPLOADED", status);
  await notify("Paper Monitor PDF captured", capture.paperTitle || "The PDF was attached successfully");
  await disarm(tabId);
};

const interceptPdfResponse = (details, capture) => {
  capture.state = "CAPTURING";
  const filter = browser.webRequest.filterResponseData(details.requestId);
  const chunks = [];
  let totalBytes = 0;
  let exceededLimit = false;

  filter.ondata = (event) => {
    const chunkLength = event.data.byteLength;
    const copy = event.data.slice(0);
    filter.write(event.data);
    totalBytes += chunkLength;
    if (!exceededLimit && totalBytes <= MAX_PDF_BYTES) {
      chunks.push(copy);
    } else {
      exceededLimit = true;
      chunks.length = 0;
    }
  };

  filter.onstop = () => {
    filter.close();
    uploadCapture(details.tabId, capture, details, chunks, totalBytes, exceededLimit)
      .catch(async (error) => {
        await reportFailure(capture, error);
        await disarm(details.tabId);
      });
  };

  filter.onerror = () => {
    filter.disconnect();
    reportFailure(capture, filter.error || "Firefox could not intercept the PDF response")
      .finally(() => disarm(details.tabId));
  };
};

browser.runtime.onMessage.addListener(async (message, sender) => {
  if (message?.type !== "ARM_CAPTURE" || !sender.tab) {
    return;
  }
  const capture = message.capture;
  const monitorOrigin = new URL(sender.tab.url).origin;
  if (!capture
      || new URL(capture.uploadUrl).origin !== monitorOrigin
      || !/^https?:/.test(capture.providerUrl)
      || Date.parse(capture.expiresAt) <= Date.now()) {
    throw new Error("Paper Monitor supplied invalid capture details");
  }

  const providerTab = await browser.tabs.create({ url: "about:blank", active: true });
  capturesByTab.set(providerTab.id, {
    ...capture,
    monitorTabId: sender.tab.id,
    providerTabId: providerTab.id,
    state: "ARMED"
  });
  await persistCaptures();
  await browser.tabs.update(providerTab.id, { url: capture.providerUrl, active: true });
  await sendStatus(capturesByTab.get(providerTab.id), "ARMED", {
    message: "Capture armed. Download the PDF in this provider tab."
  });
});

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const capture = capturesByTab.get(details.tabId);
    if (!capture || capture.state !== "ARMED") {
      return {};
    }
    if (Date.parse(capture.expiresAt) <= Date.now()) {
      reportFailure(capture, "PDF capture expired").finally(() => disarm(details.tabId));
      return {};
    }
    if (responseLooksLikePdf(details)) {
      interceptPdfResponse(details, capture);
    }
    return {};
  },
  {
    urls: ["<all_urls>"],
    types: ["main_frame", "sub_frame", "xmlhttprequest", "other"]
  },
  ["blocking", "responseHeaders"]
);

browser.tabs.onRemoved.addListener((tabId) => {
  const capture = capturesByTab.get(tabId);
  if (capture?.state === "ARMED") {
    reportFailure(capture, "The provider tab was closed before a PDF was captured")
      .finally(() => disarm(tabId));
  }
});

restoreCaptures().catch(console.error);
