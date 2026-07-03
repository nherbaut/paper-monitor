const PAGE_BRIDGE = "paper-monitor-pdf-capture";
const EXTENSION_BRIDGE = "paper-monitor-pdf-capture-extension";

const postToPage = (type, status = null) => {
  window.postMessage({
    bridge: EXTENSION_BRIDGE,
    type,
    status
  }, window.location.origin);
};

window.addEventListener("message", async (event) => {
  if (event.source !== window || event.origin !== window.location.origin) {
    return;
  }
  const message = event.data;
  if (!message || message.bridge !== PAGE_BRIDGE || message.type !== "ARM_CAPTURE") {
    return;
  }
  try {
    await browser.runtime.sendMessage({
      type: "ARM_CAPTURE",
      capture: message.capture
    });
  } catch (error) {
    postToPage("CAPTURE_STATUS", {
      captureId: message.capture?.captureId,
      status: "FAILED",
      error: error.message || "Could not communicate with the PDF capture extension"
    });
  }
});

browser.runtime.onMessage.addListener((message) => {
  if (message?.type === "CAPTURE_STATUS") {
    postToPage("CAPTURE_STATUS", message.status);
  }
});

postToPage("READY");
