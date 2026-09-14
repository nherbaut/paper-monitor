const STORAGE_KEY = "paper-monitor.paper-analysis.active-job";
const RUNNING = new Set(["QUEUED", "ANALYZING", "SAVING"]);

function installUi() {
    if (document.getElementById("paper-analysis-progress-style")) return;
    const style = document.createElement("style");
    style.id = "paper-analysis-progress-style";
    style.textContent = `
        .paper-analysis-modal { border: 0; border-radius: 20px; padding: 0; width: min(34rem, calc(100vw - 2rem)); box-shadow: 0 24px 80px rgb(15 23 42 / .3); }
        .paper-analysis-modal::backdrop { background: rgb(15 23 42 / .38); backdrop-filter: blur(2px); }
        .paper-analysis-body { padding: 1.2rem; color: #1f2937; background: #fff; }
        .paper-analysis-header, .paper-analysis-line { display: flex; align-items: start; justify-content: space-between; gap: 1rem; }
        .paper-analysis-header h3, .paper-analysis-line p { margin: 0; }
        .paper-analysis-header .meta { margin: .3rem 0 0; color: #637182; }
        .paper-analysis-minimize { border: 1px solid #d7e0ea; border-radius: 999px; background: #fff; width: 2.5rem; height: 2.5rem; cursor: pointer; }
        .paper-analysis-progress-shell { display: grid; gap: .75rem; margin-top: 1rem; }
        .paper-analysis-progress-shell progress { width: 100%; height: .8rem; accent-color: #d69e2e; }
        .paper-analysis-modal[data-analysis-state="completed"] progress { accent-color: #3182ce; }
        .paper-analysis-modal[data-analysis-state="failed"] progress { accent-color: #c53030; }
        .paper-analysis-error { margin: 0; color: #b42318; white-space: pre-wrap; overflow-wrap: anywhere; }
        .paper-analysis-hidden { display: none !important; }
        .paper-analysis-watch { --analysis-angle: 0deg; position: fixed; right: 1rem; bottom: 1rem; z-index: 250; width: 4.25rem; height: 4.25rem; }
        .paper-analysis-watch-button { width: 4.25rem; height: 4.25rem; padding: .8rem; border: 0; border-radius: 999px; cursor: pointer; color: #744210; background: conic-gradient(#d69e2e var(--analysis-angle), #fef3c7 0); box-shadow: 0 12px 28px rgb(15 23 42 / .22); }
        .paper-analysis-watch.completed .paper-analysis-watch-button { color: #174a75; background: #bee3f8; }
        .paper-analysis-watch.failed .paper-analysis-watch-button { color: #822727; background: #fed7d7; }
        .paper-analysis-watch-button svg { width: 100%; height: 100%; fill: none; stroke: currentColor; stroke-width: 1.8; }
        .paper-analysis-watch-close { position: absolute; right: -.2rem; top: -.3rem; width: 1.45rem; height: 1.45rem; padding: 0; border: 0; border-radius: 999px; color: #fff; background: #334155; cursor: pointer; }
    `;
    document.head.append(style);

    const dialog = document.createElement("dialog");
    dialog.id = "paper-analysis-modal";
    dialog.className = "paper-analysis-modal";
    dialog.dataset.analysisState = "running";
    dialog.innerHTML = `
        <div class="paper-analysis-body">
            <div class="paper-analysis-header">
                <div><h3>OpenAI paper analysis</h3><p class="meta" data-analysis-title></p></div>
                <button class="paper-analysis-minimize" type="button" title="Minimize paper analysis" aria-label="Minimize paper analysis">—</button>
            </div>
            <div class="paper-analysis-progress-shell">
                <div class="paper-analysis-line"><p data-analysis-phase role="status" aria-live="polite">Preparing analysis…</p><p class="meta" data-analysis-count></p></div>
                <progress max="3" aria-label="OpenAI paper analysis progress"></progress>
                <p class="paper-analysis-error paper-analysis-hidden" data-analysis-error></p>
            </div>
        </div>`;
    document.body.append(dialog);

    const watch = document.createElement("div");
    watch.id = "paper-analysis-watch";
    watch.className = "paper-analysis-watch paper-analysis-hidden";
    watch.innerHTML = `
        <button class="paper-analysis-watch-button" type="button" title="Open paper analysis progress" aria-label="Open paper analysis progress">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9"></path><path d="M12 7v5l3 2"></path><path d="M16 3h5v5"></path></svg>
        </button>
        <button class="paper-analysis-watch-close paper-analysis-hidden" type="button" title="Dismiss paper analysis" aria-label="Dismiss paper analysis">×</button>`;
    document.body.append(watch);
}

export function createPaperAnalysisProgress(options = {}) {
    installUi();
    const modal = document.getElementById("paper-analysis-modal");
    const watch = document.getElementById("paper-analysis-watch");
    const title = modal.querySelector("[data-analysis-title]");
    const phase = modal.querySelector("[data-analysis-phase]");
    const count = modal.querySelector("[data-analysis-count]");
    const progress = modal.querySelector("progress");
    const error = modal.querySelector("[data-analysis-error]");
    const minimize = modal.querySelector(".paper-analysis-minimize");
    const watchButton = watch.querySelector(".paper-analysis-watch-button");
    const watchClose = watch.querySelector(".paper-analysis-watch-close");
    let currentJob = null;
    let pollTimer = null;
    let minimized = false;
    let completedCallbackJobId = null;

    const notifyState = () => options.onStateChange?.(currentJob);
    const stopPolling = () => {
        if (pollTimer) window.clearTimeout(pollTimer);
        pollTimer = null;
    };
    const dismiss = () => {
        stopPolling();
        currentJob = null;
        window.localStorage.removeItem(STORAGE_KEY);
        if (modal.open) modal.close();
        watch.classList.add("paper-analysis-hidden");
        notifyState();
    };
    const showModal = () => {
        minimized = false;
        watch.classList.add("paper-analysis-hidden");
        if (!modal.open) modal.showModal();
    };
    const render = (job) => {
        currentJob = job;
        const status = String(job.status || "FAILED").toUpperCase();
        const running = RUNNING.has(status);
        const completed = status === "COMPLETED";
        const failed = status === "FAILED";
        const done = completed || failed;
        const total = Math.max(Number(job.total) || 3, 1);
        const completedSteps = Math.min(Number(job.completed) || 0, total);
        title.textContent = job.paperTitle || "Selected paper";
        phase.textContent = job.phase || (completed ? "Analysis complete" : failed ? "Analysis failed" : "Analyzing paper…");
        count.textContent = completedSteps + " / " + total;
        progress.max = total;
        progress.value = completedSteps;
        modal.dataset.analysisState = failed ? "failed" : completed ? "completed" : "running";
        error.textContent = job.error || "";
        error.classList.toggle("paper-analysis-hidden", !job.error);
        watch.style.setProperty("--analysis-angle", Math.round((completedSteps / total) * 360) + "deg");
        watch.classList.toggle("completed", completed);
        watch.classList.toggle("failed", failed);
        watchClose.classList.toggle("paper-analysis-hidden", !done);
        watchButton.setAttribute("aria-label", phase.textContent + ", " + count.textContent);
        if (minimized) {
            if (modal.open) modal.close();
            watch.classList.remove("paper-analysis-hidden");
        } else {
            watch.classList.add("paper-analysis-hidden");
            if (!modal.open) modal.showModal();
        }
        notifyState();
        if (completed && completedCallbackJobId !== String(job.jobId)) {
            completedCallbackJobId = String(job.jobId);
            options.onCompleted?.(job);
        }
        if (!running) stopPolling();
    };
    const poll = () => {
        stopPolling();
        if (!currentJob?.jobId || !RUNNING.has(String(currentJob.status || "").toUpperCase())) return;
        const jobId = currentJob.jobId;
        pollTimer = window.setTimeout(async () => {
            try {
                const response = await fetch("/api/paper-analyses/" + encodeURIComponent(jobId), {
                    headers: { Accept: "application/json" }
                });
                const body = await response.text();
                if (!response.ok) throw new Error(body || "Unable to load paper analysis progress");
                if (String(currentJob?.jobId) !== String(jobId)) return;
                render(JSON.parse(body));
                poll();
            } catch (pollError) {
                phase.textContent = "Waiting for paper analysis status…";
                pollTimer = window.setTimeout(poll, 2500);
            }
        }, 900);
    };
    const failedStart = (message, paperTitle) => {
        render({ status: "FAILED", phase: "Analysis could not be started", error: message, paperTitle, completed: 0, total: 3 });
    };

    minimize.addEventListener("click", () => {
        minimized = true;
        if (modal.open) modal.close();
        watch.classList.remove("paper-analysis-hidden");
    });
    watchButton.addEventListener("click", showModal);
    watchClose.addEventListener("click", (event) => {
        event.stopPropagation();
        dismiss();
    });
    modal.addEventListener("cancel", (event) => {
        event.preventDefault();
        minimized = true;
        modal.close();
        watch.classList.remove("paper-analysis-hidden");
    });

    return {
        async start({ paperId, reviewId = null, trigger = "paper-menu", paperTitle = "Selected paper" }) {
            if (!paperId) return null;
            window.localStorage.removeItem(STORAGE_KEY);
            completedCallbackJobId = null;
            minimized = false;
            render({ status: "QUEUED", phase: "Starting paper analysis…", paperTitle, completed: 0, total: 3 });
            const query = new URLSearchParams({ trigger });
            if (reviewId) query.set("reviewId", reviewId);
            try {
                const response = await fetch("/api/papers/" + encodeURIComponent(paperId) + "/analysis?" + query, {
                    method: "POST",
                    headers: { Accept: "application/json" }
                });
                const body = await response.text();
                if (!response.ok) throw new Error(body || "Unable to start paper analysis");
                const job = JSON.parse(body);
                window.localStorage.setItem(STORAGE_KEY, String(job.jobId));
                render(job);
                poll();
                return job;
            } catch (startError) {
                failedStart(startError.message || "Unable to start paper analysis", paperTitle);
                return null;
            }
        },
        async restore(paperId = null) {
            const storedJobId = window.localStorage.getItem(STORAGE_KEY);
            const url = storedJobId
                ? "/api/paper-analyses/" + encodeURIComponent(storedJobId)
                : paperId
                    ? "/api/papers/" + encodeURIComponent(paperId) + "/analysis/latest"
                    : null;
            if (!url) return;
            try {
                const response = await fetch(url, { headers: { Accept: "application/json" } });
                if (!response.ok) {
                    if (storedJobId) window.localStorage.removeItem(STORAGE_KEY);
                    return;
                }
                const job = await response.json();
                const running = RUNNING.has(String(job.status || "").toUpperCase());
                if (!job.jobId || (!storedJobId && !running)) return;
                window.localStorage.setItem(STORAGE_KEY, String(job.jobId));
                minimized = true;
                render(job);
                poll();
            } catch (ignored) {
                // The paper page remains usable while status is temporarily unavailable.
            }
        },
        isRunningFor(paperId) {
            return Boolean(currentJob && String(currentJob.paperId) === String(paperId)
                && RUNNING.has(String(currentJob.status || "").toUpperCase()));
        },
        current() {
            return currentJob;
        },
        dismiss
    };
}
