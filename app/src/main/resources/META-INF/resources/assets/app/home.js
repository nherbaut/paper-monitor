    import { createPaperAnalysisProgress } from "/assets/vendor/paper-analysis-progress.js?v=2";

    let markedPromise = null;
    let pdfJsPromise = null;
    const loadMarked = () => markedPromise ||= import("https://cdn.jsdelivr.net/npm/marked@15.0.12/lib/marked.esm.js")
        .then((module) => module.marked);
    const loadPdfJs = () => pdfJsPromise ||= import("https://cdn.jsdelivr.net/npm/pdfjs-dist@5.4.624/build/pdf.min.mjs")
        .then((module) => {
            module.GlobalWorkerOptions.workerSrc = "https://cdn.jsdelivr.net/npm/pdfjs-dist@5.4.624/build/pdf.worker.min.mjs";
            return module;
        });

    const storageKey = "paper-monitor.lastSeenLogicalFeed";
    const readerBootstrap = document.getElementById("reader-bootstrap");
    const statusTabsContainer = document.getElementById("reader-tabs");
    const statusSubtabsContainer = document.getElementById("reader-subtabs");
    const mobileStateMenu = document.getElementById("mobile-state-menu");
    const mobileStateMenuSummary = document.getElementById("mobile-state-menu-summary");
    const mobileStateTabs = document.getElementById("mobile-state-tabs");
    const lastSeenElement = document.getElementById("last-seen-logical-feed");
    const latestPaperCard = document.querySelector("[data-logical-feed-name]");
    let paperRecords = [];
    let papersLoaded = false;
    let browserNextCursor = null;
    let browserTotal = 0;
    let browserFacets = [];
    let browserRequestGeneration = 0;
    let browserRequestInFlight = false;
    let loadedBrowserFilterSignature = "";
    let requestedBrowserFilterSignature = "";
    let browserFilterDebounceTimer = null;
    const logicalFeedFilter = document.getElementById("logical-feed-filter");
    const doiImportLogicalFeed = document.getElementById("doi-import-logical-feed");
    const reviewCreateModal = document.getElementById("review-create-modal");
    const reviewModalTitle = document.getElementById("review-modal-title");
    const reviewModalClose = document.getElementById("review-modal-close");
    const reviewMenuStatus = document.getElementById("review-menu-status");
    const reviewCreateForm = document.getElementById("review-create-form");
    const reviewStateList = document.getElementById("review-state-list");
    const reviewModeWizard = document.getElementById("review-mode-wizard");
    const reviewModePde = document.getElementById("review-mode-pde");
    const reviewTemplateSelect = document.getElementById("review-template-select");
    const reviewPdeBack = document.getElementById("review-pde-back");
    const reviewPdeRefresh = document.getElementById("review-pde-refresh");
    const reviewPdeNext = document.getElementById("review-pde-next");
    const rqEditorList = document.getElementById("rq-editor-list");
    const rqAddButton = document.getElementById("rq-add-button");
    const reviewWizardSteps = document.querySelectorAll("[data-review-wizard-step]");
    const reviewWizardQuestionsBack = document.getElementById("review-wizard-questions-back");
    const reviewWizardQuestionsNext = document.getElementById("review-wizard-questions-next");
    const reviewWizardStatesBack = document.getElementById("review-wizard-states-back");
    const reviewWizardStatesNext = document.getElementById("review-wizard-states-next");
    const reviewWizardConfirmBack = document.getElementById("review-wizard-confirm-back");
    const reviewWizardSummary = document.getElementById("review-wizard-summary");
    const builtInReviewTemplateId = "five-research-questions";
    let reviewCreationMode = null;
    let reviewTemplates = [];
    let reviewBaseQuestions = [];
    let reviewWizardLoadSequence = 0;
    let draggedRqRow = null;
    const feedCreateModal = document.getElementById("feed-create-modal");
    const feedCreateModalTitle = document.getElementById("feed-create-modal-title");
    const feedCreateModalStatus = document.getElementById("feed-create-modal-status");
    const feedCreateModalClose = document.getElementById("feed-create-modal-close");
    const feedCreateForm = document.getElementById("feed-create-form");
    const feedCreateLogicalFeedId = document.getElementById("feed-create-logical-feed-id");
    const feedCreateReturnTo = document.getElementById("feed-create-return-to");
    const feedCreateName = document.getElementById("feed-create-name");
    const feedCreateUrl = document.getElementById("feed-create-url");
    const feedDeleteModal = document.getElementById("feed-delete-modal");
    const feedDeleteModalStatus = document.getElementById("feed-delete-modal-status");
    const feedDeleteModalClose = document.getElementById("feed-delete-modal-close");
    const feedDeleteForm = document.getElementById("feed-delete-form");
    const feedDeleteExpectedName = document.getElementById("feed-delete-expected-name");
    const feedDeleteCancel = document.getElementById("feed-delete-cancel");
    const feedDeleteSubmit = document.getElementById("feed-delete-submit");
    let deletingLogicalFeedName = "";
    const paperImportModal = document.getElementById("paper-import-modal");
    const paperImportModalClose = document.getElementById("paper-import-modal-close");
    const paperImportDoiTab = document.getElementById("paper-import-doi-tab");
    const paperImportWebTab = document.getElementById("paper-import-web-tab");
    const paperImportDoiPanel = document.getElementById("paper-import-doi-panel");
    const paperImportWebPanel = document.getElementById("paper-import-web-panel");
    const doiImportModalStatus = document.getElementById("manual-import-modal-status");
    const urlImportModalStatus = document.getElementById("url-import-modal-status");
    const urlImportForm = document.getElementById("url-import-form");
    const urlImportUrl = document.getElementById("url-import-url");
    const urlImportStatus = document.getElementById("url-import-status");
    const urlImportFeedback = document.getElementById("url-import-feedback");
    const urlImportSubmit = document.getElementById("url-import-submit");
    const batchPaperModal = document.getElementById("batch-paper-modal");
    const batchPaperModalClose = document.getElementById("batch-paper-modal-close");
    const batchPaperModalStatus = document.getElementById("batch-paper-modal-status");
    const batchPaperForm = document.getElementById("batch-paper-form");
    const batchPaperOperation = document.getElementById("batch-paper-operation");
    const batchPaperStatusField = document.getElementById("batch-paper-status-field");
    const batchPaperStatus = document.getElementById("batch-paper-status");
    const batchPaperTagField = document.getElementById("batch-paper-tag-field");
    const batchPaperTag = document.getElementById("batch-paper-tag");
    const batchPaperFormSummary = document.getElementById("batch-paper-form-summary");
    const batchPaperFormFeedback = document.getElementById("batch-paper-form-feedback");
    const batchPaperApply = document.getElementById("batch-paper-apply");
    const criteriaSelectionModal = document.getElementById("criteria-selection-modal");
    const criteriaSelectionClose = document.getElementById("criteria-selection-close");
    const criteriaSelectionCancel = document.getElementById("criteria-selection-cancel");
    const criteriaSelectionForm = document.getElementById("criteria-selection-form");
    const criteriaSelectionGroups = document.getElementById("criteria-selection-groups");
    const criteriaSelectionTitle = document.getElementById("criteria-selection-title");
    const criteriaSelectionStatus = document.getElementById("criteria-selection-status");
    const criteriaSelectionError = document.getElementById("criteria-selection-error");
    const criteriaSelectionNotesInput = document.getElementById("criteria-selection-notes-input");
    const manualImportForm = document.getElementById("manual-import-form");
    const manualImportDoi = document.getElementById("manual-import-doi");
    const manualImportFetch = document.getElementById("manual-import-fetch");
    const manualImportStatus = document.getElementById("manual-import-status");
    const manualImportPreview = document.getElementById("manual-import-preview");
    const manualImportPreviewTitle = document.getElementById("manual-import-preview-title");
    const manualImportPreviewMeta = document.getElementById("manual-import-preview-meta");
    const manualImportPreviewSummary = document.getElementById("manual-import-preview-summary");
    const manualImportPreviewLinks = document.getElementById("manual-import-preview-links");
    const manualImportDropzone = document.getElementById("manual-import-dropzone");
    const manualImportPdf = document.getElementById("manual-import-pdf");
    const manualImportFileButton = document.getElementById("manual-import-file-button");
    const manualImportFileLabel = document.getElementById("manual-import-file-label");
    const manualImportSubmit = document.getElementById("manual-import-submit");
    const exportTabButton = document.getElementById("reader-export-button");
    const exportTabMenu = document.getElementById("reader-export-menu");
    const readerImportPaperButton = document.getElementById("reader-import-paper-button");
    const readerOpenReviewButton = document.getElementById("reader-open-review-button");
    const readerOpenExportButton = document.getElementById("reader-open-export-button");
    const readerBatchUpdateButton = document.getElementById("reader-batch-update-button");
    const readerDiagramButton = document.getElementById("reader-diagram-button");
    const readerMendeleySyncButton = document.getElementById("reader-mendeley-sync-button");
    const mendeleySyncModal = document.getElementById("mendeley-sync-modal");
    const mendeleySyncFeedName = document.getElementById("mendeley-sync-feed-name");
    const mendeleySyncMinimize = document.getElementById("mendeley-sync-minimize");
    const mendeleySyncPhase = document.getElementById("mendeley-sync-phase");
    const mendeleySyncCount = document.getElementById("mendeley-sync-count");
    const mendeleySyncProgress = document.getElementById("mendeley-sync-progress");
    const mendeleySyncError = document.getElementById("mendeley-sync-error");
    const mendeleySyncWatch = document.getElementById("mendeley-sync-watch");
    const mendeleySyncWatchButton = document.getElementById("mendeley-sync-watch-button");
    const mendeleySyncWatchClose = document.getElementById("mendeley-sync-watch-close");
    const readerPublicUrlButton = document.getElementById("reader-public-url-button");
    const readerPublicUrlLabel = document.getElementById("reader-public-url-label");
    const paperExportModal = document.getElementById("paper-export-modal");
    const paperExportModalClose = document.getElementById("paper-export-modal-close");
    const paperExportScope = document.getElementById("paper-export-scope");
    const paperExportSelectAll = document.getElementById("paper-export-select-all");
    const paperExportClear = document.getElementById("paper-export-clear");
    const paperExportError = document.getElementById("paper-export-error");
    const exportStateList = document.getElementById("export-state-list");
    const exportFormatButtons = document.querySelectorAll(".export-format-button");
    const feedDashboard = document.getElementById("feed-dashboard");
    const feedDashboardPanel = document.getElementById("feed-dashboard-panel");
    const browserReaderSection = document.getElementById("browser-reader-section");
    const paperFeedHeading = document.getElementById("paper-feed-heading");
    const readerViewSwitch = document.getElementById("reader-view-switch");
    const readerListMode = document.getElementById("reader-list-mode");
    const readerSwipeMode = document.getElementById("reader-swipe-mode");
    const tagBrowser = document.getElementById("tag-browser");
    const tagBadgeList = document.getElementById("tag-badge-list");
    const clearTagFiltersButton = document.getElementById("clear-tag-filters");
    const tagBrowserMeta = document.getElementById("tag-browser-meta");
    const readerSearch = document.getElementById("reader-search");
    const stateSearchInput = document.getElementById("state-search-input");
    const searchLoadMoreRow = document.getElementById("search-load-more-row");
    const searchLoadMoreButton = document.getElementById("search-load-more");
    const themeToggle = document.getElementById("theme-toggle");
    const themeToggleLabel = document.getElementById("theme-toggle-label");
    const fullscreenToggle = document.getElementById("fullscreen-toggle");
    const fullscreenToggleLabel = document.getElementById("fullscreen-toggle-label");
    const classifyRssLink = document.getElementById("classify-rss-link");
    const readerLayout = document.querySelector(".reader-layout");
    const paperList = document.getElementById("paper-list");
    const leftStateWheel = document.getElementById("left-state-wheel");
    const rightStateWheel = document.getElementById("right-state-wheel");
    const leftStateOptions = document.getElementById("left-state-options");
    const rightStateOptions = document.getElementById("right-state-options");
    const classificationView = document.getElementById("classification-view");
    const classificationSurface = document.getElementById("classification-surface");
    const classificationDeck = document.getElementById("classification-deck");
    const classificationCard = document.getElementById("classification-card");
    const classificationDropZones = document.getElementById("classification-drop-zones");
    const classificationMobileStatePicker = document.getElementById("classification-mobile-state-picker");
    const classificationKeyboardBar = document.getElementById("classification-keyboard-bar");
    const classificationNextCard = document.getElementById("classification-next-card");
    const classificationNextTitle = document.getElementById("classification-next-title");
    const classificationNextMeta = document.getElementById("classification-next-meta");
    const classificationQueueTitle = document.getElementById("classification-queue-title");
    const classificationProgress = document.getElementById("classification-progress");
    const classificationProgressLabel = document.getElementById("classification-progress-label");
    const classificationListMode = document.getElementById("classification-list-mode");
    const classificationSwipeMode = document.getElementById("classification-swipe-mode");
    const classificationKicker = document.getElementById("classification-kicker");
    const classificationTitle = document.getElementById("classification-title");
    const classificationMeta = document.getElementById("classification-meta");
    const classificationTags = document.getElementById("classification-tags");
    const classificationLinks = document.getElementById("classification-links");
    const classificationAbstract = document.getElementById("classification-abstract");
    const classificationActionDock = document.getElementById("classification-action-dock");
    const classificationActions = document.getElementById("classification-actions");
    const classificationFooter = document.getElementById("classification-footer");
    const classificationLeftAction = document.getElementById("classification-left-action");
    const classificationRightAction = document.getElementById("classification-right-action");
    const classificationInfoAction = document.getElementById("classification-info-action");
    const classificationLeftLabel = document.getElementById("classification-left-label");
    const classificationRightLabel = document.getElementById("classification-right-label");
    const classificationLeftStamp = document.getElementById("classification-left-stamp");
    const classificationRightStamp = document.getElementById("classification-right-stamp");
    const classificationLeftStampLabel = document.getElementById("classification-left-stamp-label");
    const classificationRightStampLabel = document.getElementById("classification-right-stamp-label");
    const classificationDragTarget = document.getElementById("classification-drag-target");
    const classificationDragTargetLabel = document.getElementById("classification-drag-target-label");
    const classificationNotes = document.getElementById("classification-notes");
    const classificationNotesToggle = document.getElementById("classification-notes-toggle");
    const classificationNotesEditor = document.getElementById("classification-notes-editor");
    const classificationNotesIndicator = document.getElementById("classification-notes-indicator");
    const classificationNotesStatus = document.getElementById("classification-notes-status");
    const classificationViewerReturn = document.getElementById("classification-viewer-return");
    const classificationEmpty = document.querySelector(".classification-empty");
    const classificationEmptyTitle = document.getElementById("classification-empty-title");
    const classificationEmptyMessage = document.getElementById("classification-empty-message");
    const pdfPanel = document.getElementById("pdf-panel");
    const pdfScroll = document.querySelector(".pdf-scroll");
    const pdfFrame = document.getElementById("pdf-frame");
    const contentPanelLabel = document.getElementById("content-panel-label");
    const pdfPanelTitle = document.getElementById("pdf-panel-title");
    const pdfPanelMeta = document.getElementById("pdf-panel-meta");
    const pdfClose = document.getElementById("pdf-close");
    const keyboardStatusToast = document.getElementById("keyboard-status-toast");
    const stateFeedbackPop = document.getElementById("state-feedback-pop");
    const stateFeedbackImage = document.getElementById("state-feedback-image");
    const paperActionsMenu = document.getElementById("paper-actions-menu");
    const paperPdfActionsMenu = document.getElementById("paper-pdf-actions-menu");
    const paperZoomControls = document.getElementById("paper-zoom-controls");
    const paperZoomOutButton = document.getElementById("paper-zoom-out");
    const paperZoomInButton = document.getElementById("paper-zoom-in");
    const paperSpeakButton = document.getElementById("paper-speak");
    const paperSpeakLabel = document.getElementById("paper-speak-label");
    const paperAnalyzeButton = document.getElementById("paper-analyze");
    const paperDownloadButton = document.getElementById("paper-download");
    const paperImportPdfButton = document.getElementById("paper-import-pdf");
    const paperCapturePdfButton = document.getElementById("paper-capture-pdf");
    const paperCapturePdfLabel = document.getElementById("paper-capture-pdf-label");
    const paperRemovePdfButton = document.getElementById("paper-remove-pdf");
    const paperShareButton = document.getElementById("paper-share");
    const paperTagEditor = document.getElementById("paper-tag-editor");
    const paperTagList = document.getElementById("paper-tag-list");
    const paperTagAdd = document.getElementById("paper-tag-add");
    const paperTagInput = document.getElementById("paper-tag-input");
    const paperTagAddButton = document.getElementById("paper-tag-add-button");
    const paperTagSuggestions = document.getElementById("paper-tag-suggestions");
    const ttsPlayer = document.getElementById("tts-player");
    const notesPanel = document.getElementById("notes-panel");
    const notesCollapseButton = document.getElementById("notes-collapse");
    const notesExpandButton = document.getElementById("notes-expand");
    const noteViewUploadCue = document.getElementById("note-view-upload-cue");
    const notesPreview = document.getElementById("notes-preview");
    const notesEditorShell = document.getElementById("notes-editor-shell");
    const notesHighlight = document.getElementById("notes-highlight");
    const notesEditor = document.getElementById("notes-editor");
    const notesStatus = document.getElementById("notes-status");
    const STATE_DRAG_TRIGGER_X = 150;
    const STATE_WHEEL_ROW_HEIGHT = 56;
    const CLASSIFICATION_DRAG_TRIGGER_X = 96;
    const CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS = 170;
    const CLASSIFICATION_HANDOFF_MS = 380;
    const MOBILE_STATE_LONG_PRESS_MS = 400;
    const MOBILE_STATE_LONG_PRESS_MOVE_TOLERANCE = 12;
    let currentRenderToken = 0;
    let selectedPaperId = null;
    let notesSaveTimer = null;
    let currentZoom = 1;

    const paperAnalysisProgress = createPaperAnalysisProgress({
        onCompleted(job) {
            if (typeof job.notes !== "string") return;
            const paperId = String(job.paperId || "");
            const card = allPaperCards().find((item) => item.dataset.paperId === paperId);
            if (card) card.dataset.paperNotes = job.notes;
            const record = paperRecordById(paperId);
            if (record) record.paperNotes = job.notes;
            if (String(selectedPaperId || "") === paperId) {
                window.clearTimeout(notesSaveTimer);
                notesEditor.value = job.notes;
                updateNotesEditorView();
                renderNotesPreview();
                notesStatus.textContent = job.reviewDraftUpdated
                    ? "Structured abstract and review draft ready"
                    : "Structured abstract ready";
            }
        },
        onStateChange() {
            if (typeof applyLayoutState === "function") applyLayoutState();
        }
    });
    let selectedPdfUrl = null;
    let selectedDownloadUrl = null;
    let selectedContentKind = null;
    let currentPdfPageTexts = [];
    let currentTtsUrl = null;
    let lastPdfSelectionText = "";
    let handToolState = null;
    let activeStatusTab = "NEW";
    let activeChildStatus = null;
    let activePrimaryTab = "state";
    let cardDragState = null;
    let activeTouchStatusDrag = null;
    let keyboardStatePicker = null;
    let keyboardShortcutPicker = null;
    let keyboardShortcutShiftHeld = false;
    let classificationModeActive = false;
    let classificationModeTemporary = false;
    let classificationBusy = false;
    let classificationDragState = null;
    let classificationMobileLongPress = null;
    let classificationQueueWheelDelta = 0;
    let classificationQueueWheelTimer = null;
    let classificationQueueInitialTotal = null;
    const classificationQueueCompletedPaperIds = new Set();
    let classificationNotesSaveTimer = null;
    let classificationNotesSaving = null;
    let classificationViewerReturnPaperId = null;
    const classificationNoteDrafts = new Map();
    let activePdfCapture = null;
    let pdfCapturePollTimer = null;
    let pdfCaptureExtensionReady = false;
    let keyboardStatusUndo = null;
    let suppressCardClick = false;
    let paperStatusTransitionSequence = 0;
    const paperStatusTransitionTokens = new Map();
    const paperStatusPersistenceQueues = new Map();
    let stateSearchQuery = "";
    let stateSearchVisibleLimit = 10;
    let selectedTagFilters = [];
    let reviewSummaries = [];
    let draggedFeedDashboardCard = null;
    let feedDashboardOrderSaving = false;
    const themeStorageKey = "paper-monitor.theme";
    const notesCollapsedStorageKey = "paper-monitor.notes-collapsed";
    const pdfCaptureStorageKey = "paper-monitor.active-pdf-capture";
    const KEYBOARD_STATUS_TOAST_MS = 2200;
    const STATE_FEEDBACK_POP_MS = 1200;
    const MIAGE_STATE_FEEDBACK_FRAME_COUNT = 15;
    const STATE_FEEDBACK_DIRECTION_IMAGES = {
        up: [
            "up-01-elevator.png",
            "up-02-escalator.png",
            "up-03-balloon.png",
            "up-04-rocket.png",
            "up-05-helicopter.png"
        ],
        down: [
            "down-01-elevator.png",
            "down-02-escalator.png",
            "down-03-parachute.png",
            "down-04-sled.png",
            "down-05-mine-cart.png"
        ]
    };
    const MIAGE_STATE_FEEDBACK_FOLDERS = {
        DISCARDED: "discarded",
        TODO: "todo",
        DONE: "done"
    };
    const MAD_PROFESSOR_QUESTION_IMAGES = [
        "01-kept-paper-question.png", "02-kept-classification-question.png", "03-magnifier-question.png",
        "04-thinking-question.png", "05-shrug-question.png", "06-glasses-question.png",
        "07-reading-question.png", "08-scratching-head-question.png"
    ];
    const MAD_PROFESSOR_CONGRATULATION_IMAGES = [
        "01-thumbs-up.png", "02-cheering.png", "03-trophy.png", "04-double-thumbs-up.png",
        "05-great-job.png", "06-high-five.png", "07-heart.png", "08-idea-thumbs-up.png"
    ];
    let stateFeedbackTimer = null;
    const initialPaperId = readerBootstrap?.dataset.initialPaperId || "";
    const initialLogicalFeedId = readerBootstrap?.dataset.initialLogicalFeedId || "";
    const authenticated = readerBootstrap?.dataset.authenticated === "true";
    const canEdit = readerBootstrap?.dataset.canEdit === "true";
    const anonymousSetupToken = readerBootstrap?.dataset.anonymousSetupToken || "";
    const shareMode = readerBootstrap?.dataset.shareMode === "true";
    const sharedFeedToken = shareMode
        ? (window.location.pathname.match(/^\/share\/feed\/([^/]+)$/)?.[1] || "")
        : "";
    const serverRenderedShareMode = shareMode && !sharedFeedToken;
    const classificationQueueMode = readerBootstrap?.dataset.classificationQueueMode === "true";
    const classificationQueueStates = new URLSearchParams(window.location.search).getAll("state").filter(Boolean);
    const startClassificationMode = readerBootstrap?.dataset.startClassificationMode === "true";
    if (anonymousSetupToken) {
        const nativeFetch = window.fetch.bind(window);
        window.fetch = (resource, options = {}) => {
            const resourceUrl = resource instanceof Request ? resource.url : String(resource);
            const url = new URL(resourceUrl, window.location.href);
            if (url.origin !== window.location.origin) {
                return nativeFetch(resource, options);
            }
            const headers = new Headers(resource instanceof Request ? resource.headers : options.headers);
            headers.set("X-Paper-Monitor-Setup-Token", anonymousSetupToken);
            if (resource instanceof Request) {
                return nativeFetch(new Request(resource, { ...options, headers }));
            }
            return nativeFetch(resource, { ...options, headers });
        };
    }
    document.body.classList.toggle("classification-queue-mode", classificationQueueMode);
    let loadedLogicalFeedId = shareMode ? String(readerBootstrap?.dataset.initialLogicalFeedId || "") : "";
    let notesCollapsed = false;
    let pdfResizeObserver = null;
    let lastPdfFrameWidth = 0;
    const searchDebugEnabled = true;

    const allPaperCards = () => Array.from(document.querySelectorAll("[data-paper-id]"));
    const paperRecordById = (paperId) => paperRecords.find((record) => String(record.id) === String(paperId)) || null;

    const isMobileViewport = () => window.innerWidth <= 720;

    const copyTextWithFallback = async (text, promptLabel) => {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch {
            window.prompt(promptLabel, text);
            return false;
        }
    };

    const renderLastSeenLogicalFeed = (logicalFeedName) => {
        if (lastSeenElement) {
            lastSeenElement.textContent = logicalFeedName || "No paper feed seen yet.";
        }
    };

    const updatePaperFeedHeading = () => {
        const selectedOption = logicalFeedFilter?.selectedOptions?.[0];
        if (selectedOption && selectedOption.value) {
            paperFeedHeading.textContent = selectedOption.dataset.feedName;
            return;
        }
        paperFeedHeading.textContent = "All paper feeds";
    };

    const applyTheme = (theme) => {
        const darkMode = theme === "dark";
        document.body.classList.toggle("dark-mode", darkMode);
        themeToggleLabel.textContent = darkMode ? "Light mode" : "Dark mode";
    };

    const applyNotesCollapsedState = () => {
        const collapsed = notesCollapsed && window.innerWidth > 720;
        readerLayout.classList.toggle("notes-collapsed", collapsed);
        notesPanel.dataset.collapsedLabel = collapsed ? "Notes" : "";
        if (notesCollapseButton) {
            notesCollapseButton.title = collapsed ? "Show notes" : "Fold notes";
            notesCollapseButton.textContent = collapsed ? "‹" : "›";
        }
        if (notesExpandButton) {
            notesExpandButton.title = "Show notes";
        }
    };

    const escapeHtml = (value) => value
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");

    const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

    const highlightedHtml = (value, query) => {
        const source = String(value || "");
        const normalizedQuery = (query || "").replace(/\s+/g, " ").trim();
        if (!normalizedQuery) {
            return escapeHtml(source);
        }
        const pattern = new RegExp(escapeRegex(normalizedQuery).replace(/\s+/g, "\\s+"), "gi");
        let result = "";
        let lastIndex = 0;
        let match;
        while ((match = pattern.exec(source)) !== null) {
            result += escapeHtml(source.slice(lastIndex, match.index));
            result += "<mark class=\"search-highlight\">" + escapeHtml(match[0]) + "</mark>";
            lastIndex = match.index + match[0].length;
        }
        result += escapeHtml(source.slice(lastIndex));
        return result;
    };

    const setManualImportStatus = (message, isError = false) => {
        if (!manualImportStatus) {
            return;
        }
        manualImportStatus.textContent = message || "";
        manualImportStatus.classList.toggle("hidden", !message);
        manualImportStatus.style.color = isError ? "var(--danger, #a33)" : "";
    };

    const setManualImportSpinnerStatus = (message) => {
        if (!manualImportStatus) {
            return;
        }
        manualImportStatus.innerHTML = message ? "<span class=\"inline-spinner\" aria-hidden=\"true\"></span>" + escapeHtml(message) : "";
        manualImportStatus.classList.toggle("hidden", !message);
        manualImportStatus.style.color = "";
    };

    const updateManualImportFileLabel = () => {
        if (!manualImportFileLabel || !manualImportPdf) {
            return;
        }
        manualImportFileLabel.textContent = manualImportPdf.files && manualImportPdf.files[0]
            ? "Selected PDF: " + manualImportPdf.files[0].name
            : "Drop a PDF here, or select one from your device.";
    };

    const parseManualImportDois = () => {
        const rawValue = manualImportDoi?.value || "";
        return Array.from(new Set(rawValue
            .split(/[,\s]+/)
            .map((value) => value.trim())
            .filter(Boolean)));
    };

    const resolveCurrentLogicalFeedId = () => {
        if (logicalFeedFilter?.value) {
            return logicalFeedFilter.value;
        }
        if (loadedLogicalFeedId) {
            return String(loadedLogicalFeedId);
        }
        return "";
    };

    const syncDoiImportLogicalFeed = () => {
        if (!doiImportLogicalFeed) {
            return "";
        }
        const selectedFeedId = resolveCurrentLogicalFeedId();
        if (selectedFeedId && Array.from(doiImportLogicalFeed.options).some((option) => option.value === selectedFeedId)) {
            doiImportLogicalFeed.value = selectedFeedId;
            return selectedFeedId;
        }
        doiImportLogicalFeed.value = "";
        return "";
    };

    const resetManualImportPreview = () => {
        if (manualImportPreview) {
            manualImportPreview.classList.add("hidden");
        }
        if (manualImportDropzone) {
            manualImportDropzone.classList.add("hidden");
            manualImportDropzone.classList.remove("drag-over");
        }
        if (manualImportSubmit) {
            manualImportSubmit.classList.add("hidden");
            manualImportSubmit.disabled = true;
        }
        if (manualImportPdf) {
            manualImportPdf.value = "";
        }
        updateManualImportFileLabel();
    };

    const syncManualImportControls = () => {
        const dois = parseManualImportDois();
        const isBatch = dois.length > 1;
        const hasPreview = manualImportPreview && !manualImportPreview.classList.contains("hidden");
        if (manualImportFetch) {
            manualImportFetch.disabled = false;
            manualImportFetch.textContent = isBatch ? "Preview first DOI" : "Fetch metadata";
        }
        if (manualImportSubmit) {
            manualImportSubmit.textContent = isBatch ? "Import " + dois.length + " papers" : "Import paper";
            manualImportSubmit.disabled = isBatch ? !dois.length : !hasPreview;
            manualImportSubmit.classList.toggle("hidden", !isBatch && !hasPreview);
        }
        if (manualImportDropzone) {
            manualImportDropzone.classList.toggle("hidden", isBatch || manualImportPreview?.classList.contains("hidden"));
            manualImportDropzone.classList.remove("drag-over");
        }
        if (manualImportPdf) {
            if (isBatch) {
                manualImportPdf.value = "";
            }
        }
        updateManualImportFileLabel();
        return { dois, isBatch };
    };

    const renderManualImportPreview = (metadata) => {
        if (!manualImportPreview) {
            return;
        }
        manualImportPreviewTitle.textContent = metadata.title || "Untitled paper";
        const metaParts = [];
        if (metadata.authors) {
            metaParts.push(metadata.authors);
        }
        if (metadata.publisher) {
            metaParts.push(metadata.publisher);
        }
        if (metadata.publishedOn) {
            metaParts.push(metadata.publishedOn);
        }
        manualImportPreviewMeta.textContent = metaParts.join(" | ");
        manualImportPreviewSummary.textContent = metadata.summary || "No abstract available.";
        const links = [];
        if (metadata.sourceLink) {
            links.push("<a href=\"" + escapeHtml(metadata.sourceLink) + "\" target=\"_blank\" rel=\"noreferrer\">Source</a>");
        }
        if (metadata.openAccessUrl) {
            links.push("<a href=\"" + escapeHtml(metadata.openAccessUrl) + "\" target=\"_blank\" rel=\"noreferrer\">Open access</a>");
        }
        manualImportPreviewLinks.innerHTML = links.join(" | ");
        manualImportPreview.classList.remove("hidden");
        manualImportDropzone.classList.remove("hidden");
        manualImportSubmit.classList.remove("hidden");
        manualImportSubmit.disabled = false;
        syncManualImportControls();
    };

    const tryAutoFetchManualImportPdf = async (metadata) => {
        if (!metadata?.openAccessUrl || !manualImportPdf) {
            return false;
        }
        setManualImportSpinnerStatus("Trying to fetch the open access PDF...");
        try {
            const params = new URLSearchParams({
                url: metadata.openAccessUrl,
                title: metadata.title || "paper"
            });
            const response = await fetch("/papers/import-doi/preview-pdf?" + params.toString(), {
                headers: { Accept: "application/pdf" }
            });
            if (!response.ok) {
                return false;
            }
            const blob = await response.blob();
            const disposition = response.headers.get("Content-Disposition") || "";
            const fileNameMatch = disposition.match(/filename="([^"]+)"/i);
            const fileName = fileNameMatch?.[1] || ((metadata.title || "paper").replace(/[^a-zA-Z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "paper") + ".pdf";
            const transfer = new DataTransfer();
            transfer.items.add(new File([blob], fileName, { type: "application/pdf" }));
            manualImportPdf.files = transfer.files;
            updateManualImportFileLabel();
            setManualImportStatus("Metadata loaded. Open access PDF fetched.");
            return true;
        } catch (error) {
            return false;
        }
    };

    const highlightMarkdown = (value) => {
        let html = escapeHtml(value);
        html = html.replace(/^(#{1}|#{2}|#{3}|#{4}|#{5}|#{6})\s.*$/gm, '<span class="md-heading">$&</span>');
        html = html.replace(/(\*\*[^*]+\*\*|__[^_]+__)/g, '<span class="md-syntax">$1</span>');
        html = html.replace(/(\*[^*\n]+\*|_[^_\n]+_|`[^`\n]+`|~~[^~]+~~)/g, '<span class="md-emphasis">$1</span>');
        html = html.replace(/^(\s*[-*+] |\s*\d+\. |> )/gm, '<span class="md-syntax">$1</span>');
        return html;
    };

    const updateNotesEditorView = () => {
        notesHighlight.innerHTML = highlightMarkdown(notesEditor.value) + "\n";
    };

    const renderNotesPreview = async () => {
        const source = notesEditor.value.trim();
        if (!source) {
            notesPreview.innerHTML = "<p class=\"meta\">No notes yet. Click here to start writing.</p>";
            return;
        }
        const previewSource = source;
        try {
            const marked = await loadMarked();
            if (notesEditor.value.trim() === previewSource) notesPreview.innerHTML = marked.parse(previewSource);
        } catch (error) {
            notesPreview.textContent = previewSource;
        }
    };

    const enterNotesEditMode = () => {
        if (!authenticated) {
            return;
        }
        notesPreview.classList.add("hidden");
        notesEditorShell.classList.remove("hidden");
        updateNotesEditorView();
        notesEditor.focus();
        const end = notesEditor.value.length;
        notesEditor.setSelectionRange(end, end);
    };

    const leaveNotesEditMode = () => {
        notesEditorShell.classList.add("hidden");
        notesPreview.classList.remove("hidden");
        renderNotesPreview();
    };

    const storedLogicalFeedName = window.localStorage.getItem(storageKey);
    const storedNotesCollapsed = window.localStorage.getItem(notesCollapsedStorageKey);
    notesCollapsed = storedNotesCollapsed == null ? true : storedNotesCollapsed === "true";
    applyTheme(window.localStorage.getItem(themeStorageKey) || "light");
    renderLastSeenLogicalFeed(storedLogicalFeedName);
    applyNotesCollapsedState();

    if (latestPaperCard) {
        const latestLogicalFeedName = latestPaperCard.dataset.logicalFeedName;
        if (latestLogicalFeedName) {
            window.localStorage.setItem(storageKey, latestLogicalFeedName);
            renderLastSeenLogicalFeed(latestLogicalFeedName);
        }
    }

    if (initialLogicalFeedId
            && Array.from(logicalFeedFilter.options).some((option) => option.value === initialLogicalFeedId)) {
        logicalFeedFilter.value = initialLogicalFeedId;
    }

    const applyLayoutState = () => {
        const hasSelectedPaper = Boolean(selectedPaperId);
        const hasReaderContent = hasSelectedPaper
            && (selectedContentKind === "pdf" || selectedContentKind === "abstract" || selectedContentKind === "card");
        const hasPdf = hasReaderContent && selectedContentKind === "pdf";
        const activeCard = selectedPaperCard();
        const canEditPaper = Boolean(activeCard)
            && authenticated
            && activeCard.dataset.paperCanEditTags === "true";
        const importUrl = supportedSourcePdfUrl(activeCard);
        const providerUrl = providerPageUrl(activeCard);
        const downloadUrl = downloadablePaperUrl(activeCard);
        readerLayout.classList.toggle("with-notes", hasReaderContent);
        paperList.classList.toggle("hidden", hasReaderContent);
        pdfPanel.classList.toggle("visible", hasReaderContent);
        notesPanel.classList.toggle("visible", hasReaderContent);
        readerSearch.classList.toggle("hidden", activePrimaryTab !== "state" || hasReaderContent);
        applyNotesCollapsedState();
        paperSpeakLabel.textContent = hasPdf ? "Speak selection" : "Speak abstract";
        setActionAvailability(paperSpeakButton, hasSelectedPaper, "Select a paper first.");
        setActionAvailability(
            paperAnalyzeButton,
            Boolean(canEditPaper && activeCard?.dataset.pdfUrl
                && !paperAnalysisProgress.isRunningFor(activeCard?.dataset.paperId)),
            !hasSelectedPaper
                ? "Select a paper first."
                : !canEditPaper
                    ? "You need administrator access on this paper feed to run OpenAI analysis."
                    : !activeCard?.dataset.pdfUrl
                        ? "Attach a PDF before running OpenAI analysis."
                        : "OpenAI analysis is already running for this paper."
        );
        setActionAvailability(paperZoomOutButton, hasPdf, "Zoom is available only when a PDF is open.");
        setActionAvailability(paperZoomInButton, hasPdf, "Zoom is available only when a PDF is open.");
        paperZoomControls?.classList.toggle("hidden", !hasPdf);
        setActionAvailability(paperDownloadButton, Boolean(downloadUrl), "No downloadable source is available for this paper.");
        setActionAvailability(
            paperImportPdfButton,
            Boolean(canEditPaper && importUrl),
            !authenticated
                ? "Sign in with edit access to fetch a source PDF."
                : !canEditPaper
                    ? "You need admin access on this paper feed to fetch a source PDF."
                    : "No supported source PDF link is available."
        );
        setActionAvailability(
            paperCapturePdfButton,
            Boolean(canEditPaper && providerUrl),
            !authenticated
                ? "Sign in with edit access to capture a provider PDF."
                : !canEditPaper
                    ? "You need admin access on this paper feed to capture a provider PDF."
                    : "No provider page is available for this paper."
        );
        setActionAvailability(
            paperRemovePdfButton,
            Boolean(hasPdf && authenticated && canEditPaper),
            !authenticated
                ? "Sign in with edit access to remove the attached PDF."
                : !canEditPaper
                    ? "You need admin access on this paper feed to remove the attached PDF."
                    : "This paper has no attached PDF."
        );
        const canManagePdf = Boolean(canEditPaper && (hasPdf || importUrl || providerUrl));
        paperPdfActionsMenu?.classList.toggle("hidden", !canManagePdf);
        if (!canManagePdf && paperPdfActionsMenu) {
            paperPdfActionsMenu.open = false;
        }
        setActionAvailability(paperShareButton, hasSelectedPaper, "Select a paper first.");
        if (noteViewUploadCue) {
            noteViewUploadCue.title = hasSelectedPaper
                ? "Drop a PDF on the paper or notes area to attach it to the selected paper."
                : "Select a paper, then drop a PDF on the paper or notes area to attach it.";
        }
        if (!authenticated) {
            notesEditorShell.classList.add("hidden");
            notesPreview.classList.remove("hidden");
        }
    };

    const selectedPaperCard = () => {
        if (!selectedPaperId) {
            return null;
        }
        return findCardByPaperId(selectedPaperId);
    };

    const queuePdfRefit = () => {
        requestAnimationFrame(() => {
            rerenderSelectedPdf();
        });
    };

    const setNotesCollapsed = (nextCollapsed) => {
        notesCollapsed = Boolean(nextCollapsed);
        window.localStorage.setItem(notesCollapsedStorageKey, notesCollapsed ? "true" : "false");
        applyNotesCollapsedState();
        queuePdfRefit();
    };

    const paperLinksHtml = (card, insideAbstractView = false) => {
        if (!card || card.dataset.pdfUrl) {
            return "";
        }
        const links = [];
        const sourceLink = (card.dataset.paperSourceLink || "").trim();
        const openAccessLink = (card.dataset.paperOpenAccessLink || "").trim();
        if (sourceLink && !sourceLink.startsWith("upload:")) {
            links.push("<a href=\"" + escapeHtml(sourceLink) + "\" target=\"_blank\" rel=\"noreferrer\">Source</a>");
        }
        if (openAccessLink) {
            links.push("<a href=\"" + escapeHtml(openAccessLink) + "\" target=\"_blank\" rel=\"noreferrer\">Open access</a>");
        }
        if (!links.length) {
            return "";
        }
        if (insideAbstractView) {
            return "<p class=\"paper-link-row meta\">" + links.join(" | ") + "</p>";
        }
        return "Source links: " + links.join(" | ");
    };

    const renderCardSearchHighlights = (card) => {
        if (!card) {
            return;
        }
        const query = parseStateSearchQuery(stateSearchQuery).textQuery;
        const title = card.querySelector(".paper-title");
        const meta = card.querySelector(".paper-meta");
        const summary = card.querySelector(".paper-summary");
        if (title) {
            title.innerHTML = highlightedHtml(card.dataset.paperTitle || "", query);
        }
        if (meta) {
            meta.innerHTML = ""
                + highlightedHtml(card.dataset.paperAuthors || "Unknown authors", query) + "<br>"
                + highlightedHtml(card.dataset.paperVenue || "Unknown publisher", query) + "<br>"
                + "Published: " + escapeHtml(card.dataset.paperPublishedOn || "unknown") + "<br>"
                + "From feed: " + escapeHtml(card.dataset.paperFeedName || "");
        }
        if (summary) {
            summary.innerHTML = highlightedHtml(card.dataset.paperSummary || "", query);
        }
    };

    const supportedSourcePdfUrl = (card) => {
        if (!card) {
            return null;
        }
        const candidates = [card.dataset.paperOpenAccessLink, card.dataset.paperSourceLink]
            .map((value) => (value || "").trim())
            .filter(Boolean);
        for (const candidate of candidates) {
            try {
                const url = new URL(candidate);
                const hostname = url.hostname.toLowerCase();
                if (hostname.endsWith("doi.org")) {
                    const doi = decodeURIComponent(url.pathname.replace(/^\//, ""));
                    const match = doi.match(/^10\.48550\/arxiv\.(.+)$/i);
                    const identifier = match?.[1] || "";
                    if (/^(?:[0-9][0-9][0-9][0-9]\.[0-9][0-9][0-9][0-9](?:[0-9])?|[a-z0-9.-]+\/[0-9][0-9][0-9][0-9][0-9][0-9][0-9])(?:v[0-9]+)?$/i.test(identifier)) {
                        return "https://arxiv.org/pdf/" + identifier + ".pdf";
                    }
                    continue;
                }
                if (!hostname.endsWith("arxiv.org")) {
                    continue;
                }
                if (url.pathname.startsWith("/abs/")) {
                    return "https://arxiv.org/pdf/" + url.pathname.slice("/abs/".length) + ".pdf";
                }
                if (url.pathname.startsWith("/pdf/")) {
                    return url.pathname.endsWith(".pdf") ? candidate : "https://arxiv.org" + url.pathname + ".pdf";
                }
            } catch {
                // Ignore invalid URLs from metadata.
            }
        }
        return null;
    };

    const providerPageUrl = (card) => {
        if (!card) {
            return null;
        }
        return [card.dataset.paperOpenAccessLink, card.dataset.paperSourceLink]
            .map((value) => (value || "").trim())
            .find((value) => value.startsWith("https://") || value.startsWith("http://")) || null;
    };

    const downloadablePaperUrl = (card) => {
        if (!card) {
            return "";
        }
        return selectedDownloadUrl
            || card.dataset.pdfUrl
            || card.dataset.paperOpenAccessLink
            || card.dataset.paperSourceLink
            || "";
    };

    const setActionAvailability = (button, enabled, reason, enabledTitle = "") => {
        if (!button) {
            return;
        }
        button.disabled = !enabled;
        button.title = enabled ? enabledTitle : reason;
    };

    const normalizeStatus = (status) => status || "NEW";

    const parsePaperTags = (card) => {
        const token = card?.dataset.paperTags || "";
        if (!token) {
            return [];
        }
        return token.split("|").map((value) => value.trim()).filter(Boolean);
    };

    const setPaperTags = (card, tags) => {
        card.dataset.paperTags = tags.join("|");
        const record = paperRecordById(card.dataset.paperId);
        if (record) {
            record.paperTags = tags.join("|");
        }
    };

    const setPaperRecordTags = (record, tags) => {
        if (record) {
            record.paperTags = tags.join("|");
        }
    };

    const parsePaperTagsFromRecord = (record) => {
        const token = record?.paperTags || "";
        if (!token) {
            return [];
        }
        return token.split("|").map((value) => value.trim()).filter(Boolean);
    };

    const tagFilterKeysForRecord = (record) => {
        const keys = new Set(parsePaperTagsFromRecord(record));
        keys.add("state:" + (record.paperTopStatus || normalizeStatus(record.paperStatus)));
        if (record.pdfUrl) {
            keys.add("has-pdf");
        }
        if (record.paperIsNew) {
            keys.add("new-papers");
        }
        return keys;
    };

    const tagFilterMatchesRecord = (record, keys) => {
        if (!keys.length) {
            return true;
        }
        const recordKeys = tagFilterKeysForRecord(record);
        return keys.every((key) => recordKeys.has(key));
    };

    const workflowTreeForLogicalFeed = (logicalFeedId) => {
        const option = Array.from(logicalFeedFilter.options).find((item) => item.value === logicalFeedId);
        if (!option?.dataset.workflowTree) {
            return [];
        }
        try {
            const value = JSON.parse(option.dataset.workflowTree);
            return Array.isArray(value) ? value : [];
        } catch {
            return [];
        }
    };

    const workflowConfigForLogicalFeed = (logicalFeedId) => {
        const option = Array.from(logicalFeedFilter.options).find((item) => item.value === logicalFeedId);
        if (!option?.dataset.workflowConfig) {
            return null;
        }
        try {
            return JSON.parse(option.dataset.workflowConfig);
        } catch {
            return null;
        }
    };

    const workflowStateById = (logicalFeedId, stateId) => {
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const states = Array.isArray(config?.states) ? config.states : [];
        return states.find((state) => state?.id === stateId) || null;
    };

    const workflowTargetsFromState = (logicalFeedId, stateId) => {
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const transitions = Array.isArray(config?.transitions) ? config.transitions : [];
        return Array.from(new Set(transitions
            .filter((item) => item?.from === stateId)
            .flatMap((item) => Array.isArray(item?.to) ? item.to : [])
            .filter(Boolean)));
    };

    const logicalFeedOptionById = (logicalFeedId) => {
        return Array.from(logicalFeedFilter?.options || [])
            .find((option) => option.value === String(logicalFeedId)) || null;
    };

    const selectedLeafStateForSwipeMode = (logicalFeedId) => {
        if (activePrimaryTab !== "state" || !logicalFeedId) {
            return null;
        }
        if (activeChildStatus) {
            return activeChildStatus;
        }
        const activeGroup = selectedWorkflowGroups()
            .find((group) => group.name === activeStatusTab);
        const leaves = stateLeafChoices(activeGroup);
        return leaves.length === 1 ? leaves[0].value : null;
    };

    const feedCanUseSwipeMode = (logicalFeedId) => {
        const option = logicalFeedOptionById(logicalFeedId);
        return option?.dataset.viewerCanAdmin === "true"
            && (Boolean(selectedLeafStateForSwipeMode(logicalFeedId))
                || Number(option.dataset.rssIntakeCount || 0) > 0);
    };

    const usesDefaultMiageWorkflow = (logicalFeedId) => {
        return logicalFeedOptionById(logicalFeedId)?.dataset.defaultMiageWorkflow === "true";
    };

    const updateReaderModeControls = () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        const option = logicalFeedOptionById(logicalFeedId);
        const canAdmin = option?.dataset.viewerCanAdmin === "true";
        const intakeCount = Number(option?.dataset.rssIntakeCount || 0);
        const selectedState = selectedLeafStateForSwipeMode(logicalFeedId);
        const selectedStateLabel = selectedState
            ? statusLabel(selectedState)
            : null;
        readerViewSwitch?.classList.toggle("hidden", !logicalFeedId || !canAdmin || shareMode);
        readerListMode?.classList.add("active");
        readerListMode?.setAttribute("aria-pressed", "true");
        readerSwipeMode?.classList.remove("active");
        readerSwipeMode?.setAttribute("aria-pressed", "false");
        if (readerSwipeMode) {
            readerSwipeMode.disabled = !canAdmin || (!selectedState && intakeCount <= 0);
            readerSwipeMode.classList.toggle("available", !readerSwipeMode.disabled);
            readerSwipeMode.title = selectedState
                ? "Classify papers in " + selectedStateLabel + " in Swipe view"
                : intakeCount > 0
                    ? "Classify " + intakeCount + " new RSS paper" + (intakeCount === 1 ? "" : "s") + " in Swipe view"
                    : "Select a state or wait for new RSS papers to classify";
        }
    };

    const swipeModeUrl = (logicalFeedId) => {
        if (anonymousSetupToken) {
            return "/anonymous/feed/" + encodeURIComponent(anonymousSetupToken) + "?mode=swipe";
        }
        const params = new URLSearchParams({ logicalFeedId });
        const selectedState = selectedLeafStateForSwipeMode(logicalFeedId);
        if (selectedState) {
            params.append("state", selectedState);
        }
        return "/classify?" + params.toString();
    };

    const listModeUrl = (logicalFeedId) => {
        if (anonymousSetupToken) {
            return "/anonymous/feed/" + encodeURIComponent(anonymousSetupToken) + "?mode=list";
        }
        return logicalFeedId ? "/?logicalFeedId=" + encodeURIComponent(logicalFeedId) : "/";
    };

    const navigateToSwipeMode = (logicalFeedId) => {
        if (!feedCanUseSwipeMode(logicalFeedId)) {
            return false;
        }
        window.location.href = swipeModeUrl(logicalFeedId);
        return true;
    };

    const navigateToListMode = (logicalFeedId) => {
        window.location.href = listModeUrl(logicalFeedId);
    };

    const taxonomyLeaves = (taxonomyId, logicalFeedId) => {
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const taxonomy = config?.taxonomies?.[taxonomyId];
        const leaves = [];
        const walk = (items) => {
            (Array.isArray(items) ? items : []).forEach((item) => {
                const children = Array.isArray(item?.children) ? item.children : [];
                if (!children.length && item?.id) {
                    leaves.push({ id: item.id, label: item.label || item.id });
                    return;
                }
                walk(children);
            });
        };
        walk(taxonomy?.values);
        return leaves;
    };

    const workflowStatesFromToken = (token) => {
        if (!token) {
            return [];
        }
        return token.split("|").map((value) => value.trim()).filter(Boolean);
    };

    const workflowStatesForLogicalFeed = (logicalFeedId) => {
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const configuredStates = Array.isArray(config?.states)
            ? config.states.map((state) => state?.id).filter(Boolean)
            : [];
        if (configuredStates.length) {
            return configuredStates;
        }
        const option = Array.from(logicalFeedFilter.options).find((item) => item.value === logicalFeedId);
        return workflowStatesFromToken(option?.dataset.workflowStates || "");
    };

    const selectedLogicalFeedOption = () => logicalFeedFilter?.selectedOptions?.[0] || null;

    const selectedLogicalFeedCanAdmin = () => selectedLogicalFeedOption()?.dataset.viewerCanAdmin === "true";

    const selectedLogicalFeedIsPublic = () => selectedLogicalFeedOption()?.dataset.publicReadable === "true";

    const selectedLogicalFeedPublicUrl = () => selectedLogicalFeedOption()?.dataset.publicUrl || "";

    let mendeleySyncActiveFeedId = "";
    let mendeleySyncCurrentJob = null;
    let mendeleySyncPollTimer = null;
    let mendeleySyncMinimized = true;
    const mendeleySyncDismissedKey = (feedId) => "miage-review-factory.mendeley-sync.dismissed." + feedId;

    const mendeleySyncJobIdentity = (job) => job?.finishedAt || job?.startedAt || job?.status || "";

    const mendeleySyncWasDismissed = (feedId, job) => {
        if (!feedId || !job || job.running) return false;
        try {
            return window.localStorage.getItem(mendeleySyncDismissedKey(feedId))
                === mendeleySyncJobIdentity(job);
        } catch (ignored) {
            return false;
        }
    };

    const selectedMendeleySyncRunning = () => Boolean(
        mendeleySyncCurrentJob?.running
        && String(mendeleySyncActiveFeedId) === String(logicalFeedFilter?.value || ""));

    const mendeleySyncFeedLabel = (feedId) => Array.from(logicalFeedFilter?.options || [])
        .find((option) => String(option.value) === String(feedId))?.dataset?.feedName
        || Array.from(logicalFeedFilter?.options || [])
            .find((option) => String(option.value) === String(feedId))?.textContent?.trim()
        || "Current paper feed";

    const stopMendeleySyncPolling = () => {
        if (mendeleySyncPollTimer) window.clearTimeout(mendeleySyncPollTimer);
        mendeleySyncPollTimer = null;
    };

    const hideMendeleySyncProgress = () => {
        stopMendeleySyncPolling();
        mendeleySyncWatch?.classList.add("hidden");
        if (mendeleySyncModal?.open) mendeleySyncModal.close();
        mendeleySyncCurrentJob = null;
        mendeleySyncActiveFeedId = "";
    };

    const renderMendeleySyncProgress = (job) => {
        if (!job || !mendeleySyncActiveFeedId || !mendeleySyncModal || !mendeleySyncWatch) return;
        mendeleySyncCurrentJob = job;
        const status = String(job.status || "IDLE").toUpperCase();
        const running = Boolean(job.running);
        const completed = Math.max(0, Number(job.completed || 0));
        const total = Math.max(0, Number(job.total || 0));
        const failed = status === "FAILED";
        const done = status === "COMPLETED";
        const terminal = done || failed;
        const dismissed = terminal && mendeleySyncWasDismissed(mendeleySyncActiveFeedId, job);
        const ratio = total > 0 ? Math.min(completed / total, 1) : (terminal ? 1 : 0.1);

        mendeleySyncModal.dataset.syncState = failed ? "failed" : done ? "completed" : "running";
        mendeleySyncFeedName.textContent = mendeleySyncFeedLabel(mendeleySyncActiveFeedId);
        mendeleySyncPhase.textContent = job.phase || (failed ? "Synchronization failed" : done
            ? "Synchronization complete" : "Preparing synchronization…");
        mendeleySyncCount.textContent = total > 0 ? completed + " / " + total : (running ? "Preparing…" : "");
        if (running && total === 0) {
            mendeleySyncProgress.removeAttribute("value");
        } else {
            mendeleySyncProgress.max = Math.max(total, 1);
            mendeleySyncProgress.value = total > 0 ? Math.min(completed, total) : (terminal ? 1 : 0);
        }
        mendeleySyncError.textContent = job.error || "";
        mendeleySyncError.classList.toggle("hidden", !job.error);

        mendeleySyncWatch.style.setProperty("--sync-angle", Math.round(ratio * 360) + "deg");
        mendeleySyncWatch.classList.toggle("running", running);
        mendeleySyncWatch.classList.toggle("completed", done);
        mendeleySyncWatch.classList.toggle("failed", failed);
        mendeleySyncWatchClose.classList.toggle("hidden", !terminal);
        mendeleySyncWatchButton.setAttribute("aria-label",
            (job.phase || "Mendeley synchronization")
            + (total > 0 ? ", " + completed + " of " + total : ""));

        if (dismissed) {
            mendeleySyncWatch.classList.add("hidden");
            if (mendeleySyncModal.open) mendeleySyncModal.close();
        } else if (mendeleySyncMinimized) {
            mendeleySyncWatch.classList.remove("hidden");
            if (mendeleySyncModal.open) mendeleySyncModal.close();
        } else {
            mendeleySyncWatch.classList.add("hidden");
            if (!mendeleySyncModal.open) mendeleySyncModal.showModal();
        }
        if (readerMendeleySyncButton
                && String(logicalFeedFilter?.value || "") === String(mendeleySyncActiveFeedId)) {
            readerMendeleySyncButton.disabled = running;
        }
    };

    const pollMendeleySyncProgress = () => {
        stopMendeleySyncPolling();
        if (!mendeleySyncCurrentJob?.running || !mendeleySyncActiveFeedId) return;
        const feedId = mendeleySyncActiveFeedId;
        mendeleySyncPollTimer = window.setTimeout(async () => {
            if (String(feedId) !== String(mendeleySyncActiveFeedId)) return;
            try {
                const response = await fetch("/api/mendeley/feeds/" + encodeURIComponent(feedId) + "/sync-status", {
                    headers: { Accept: "application/json" }
                });
                const body = await response.text();
                if (!response.ok) throw new Error(body || "Unable to load Mendeley synchronization progress.");
                if (String(feedId) !== String(mendeleySyncActiveFeedId)) return;
                renderMendeleySyncProgress(JSON.parse(body));
                pollMendeleySyncProgress();
            } catch (error) {
                if (String(feedId) !== String(mendeleySyncActiveFeedId)) return;
                mendeleySyncPhase.textContent = "Waiting for synchronization status…";
                mendeleySyncPollTimer = window.setTimeout(pollMendeleySyncProgress, 2500);
            }
        }, 900);
    };

    const restoreMendeleySyncProgress = async (feedId) => {
        hideMendeleySyncProgress();
        if (!feedId || !selectedLogicalFeedCanAdmin() || !mendeleySyncModal) return;
        const requestedFeedId = String(feedId);
        mendeleySyncActiveFeedId = requestedFeedId;
        try {
            const response = await fetch("/api/mendeley/feeds/" + encodeURIComponent(requestedFeedId) + "/sync-status", {
                headers: { Accept: "application/json" }
            });
            if (!response.ok || requestedFeedId !== mendeleySyncActiveFeedId) {
                if (requestedFeedId === mendeleySyncActiveFeedId) hideMendeleySyncProgress();
                return;
            }
            const job = await response.json();
            if (!job.running && !["COMPLETED", "FAILED"].includes(String(job.status || "").toUpperCase())) {
                hideMendeleySyncProgress();
                return;
            }
            mendeleySyncMinimized = true;
            renderMendeleySyncProgress(job);
            pollMendeleySyncProgress();
        } catch (ignored) {
            if (requestedFeedId === mendeleySyncActiveFeedId) hideMendeleySyncProgress();
        }
    };

    const selectedWorkflowGroups = () => {
        const selectedOption = logicalFeedFilter.selectedOptions?.[0];
        if (selectedOption && selectedOption.value) {
            return workflowTreeForLogicalFeed(selectedOption.value);
        }
        return [];
    };

    const workflowGroupByName = (logicalFeedId, groupName) => {
        return workflowTreeForLogicalFeed(logicalFeedId).find((group) => group?.name === groupName) || null;
    };

    const stateLeafChoices = (group) => {
        if (!group?.name) {
            return [];
        }
        const children = Array.isArray(group?.children) ? group.children.filter(Boolean) : [];
        if (!children.length) {
            return [{ value: group.name, label: group.name }];
        }
        return children.map((child) => ({
            value: group.name + "/" + child,
            label: group.name + "/" + child,
            child
        }));
    };

    const wheelOptionsForGroups = (groups) => {
        const options = [];
        groups.forEach((group) => {
            const choices = stateLeafChoices(group);
            if (choices.length === 1 && choices[0].value === group.name) {
                options.push({ type: "state", label: group.name, value: group.name });
                return;
            }
            options.push({ type: "group", label: group.name });
            choices.forEach((choice) => {
                options.push({ type: "state", label: choice.child || choice.value, value: choice.value });
            });
        });
        return options;
    };

    const wheelOptionsForReachableStates = (logicalFeedId, stateIds) => {
        if (!stateIds.length) {
            return [];
        }
        const groups = workflowTreeForLogicalFeed(logicalFeedId);
        const stateSet = new Set(stateIds);
        const options = [];
        groups.forEach((group) => {
            const choices = stateLeafChoices(group).filter((choice) => stateSet.has(choice.value));
            if (!choices.length) {
                return;
            }
            if (choices.length === 1 && choices[0].value === group.name) {
                options.push({ type: "state", label: group.name, value: group.name });
                return;
            }
            options.push({ type: "group", label: group.name });
            choices.forEach((choice) => {
                options.push({ type: "state", label: choice.child || choice.value, value: choice.value });
            });
        });
        return options;
    };

    const syncActiveStateSelection = () => {
        const groups = selectedWorkflowGroups();
        const groupNames = groups.map((group) => group.name);
        if (!groupNames.length) {
            activeStatusTab = null;
            activeChildStatus = null;
            return;
        }
        if (!groupNames.includes(activeStatusTab)) {
            activeStatusTab = groupNames[0];
            activeChildStatus = null;
        }
        const activeGroup = groups.find((group) => group.name === activeStatusTab) || null;
        const childValues = stateLeafChoices(activeGroup)
            .map((choice) => choice.value)
            .filter((value) => value !== activeStatusTab);
        if (!childValues.length) {
            activeChildStatus = null;
            return;
        }
        if (activeChildStatus && !childValues.includes(activeChildStatus)) {
            activeChildStatus = null;
        }
    };

    const selectedStateMatchesRecord = (record) => {
        if (activeChildStatus) {
            return normalizeStatus(record.paperStatus) === activeChildStatus;
        }
        return activeStatusTab == null || record.paperTopStatus === activeStatusTab;
    };

    const selectedStateMatchesCard = (card) => {
        if (activeChildStatus) {
            return normalizeStatus(card.dataset.paperStatus) === activeChildStatus;
        }
        return activeStatusTab == null || card.dataset.paperTopStatus === activeStatusTab;
    };

    const childStateCountsForGroup = (logicalFeedId, parentState) => {
        const counts = {};
        if (!logicalFeedId || !parentState) {
            return counts;
        }
        if (!serverRenderedShareMode && browserFacets.length) {
            browserFacets
                .filter((facet) => facet.key?.startsWith("state:" + parentState + "/"))
                .forEach((facet) => { counts[facet.key.slice("state:".length)] = facet.count || 0; });
            return counts;
        }
        const targetRecords = serverRenderedShareMode
            ? allPaperCards()
                .filter((card) => card.dataset.logicalFeedId === logicalFeedId)
                .map((card) => ({
                    paperStatus: card.dataset.paperStatus
                }))
            : paperRecords.filter((record) => String(record.logicalFeedId) === String(logicalFeedId));
        targetRecords.forEach((record) => {
            const status = normalizeStatus(record.paperStatus);
            if (!status.startsWith(parentState + "/")) {
                return;
            }
            counts[status] = (counts[status] || 0) + 1;
        });
        return counts;
    };

    const stateCountsFromToken = (token) => {
        const counts = {};
        if (!token) {
            return counts;
        }
        token.split("|").forEach((entry) => {
            const separatorIndex = entry.lastIndexOf(":");
            if (separatorIndex <= 0) {
                return;
            }
            const status = entry.slice(0, separatorIndex).trim();
            const rawCount = entry.slice(separatorIndex + 1).trim();
            counts[status] = Number.parseInt(rawCount, 10) || 0;
        });
        return counts;
    };

    const selectedWorkflowCounts = () => {
        const selectedOption = logicalFeedFilter.selectedOptions[0];
        if (selectedOption && selectedOption.value) {
            return stateCountsFromToken(selectedOption.dataset.stateCounts || "");
        }

        const counts = {};
        Array.from(logicalFeedFilter.options)
                .filter((option) => option.value)
                .forEach((option) => {
                    const optionCounts = stateCountsFromToken(option.dataset.stateCounts || "");
                    Object.entries(optionCounts).forEach(([status, count]) => {
                        counts[status] = (counts[status] || 0) + count;
                    });
                });
        return counts;
    };

    const selectedWorkflowStates = () => {
        const selectedOption = logicalFeedFilter.selectedOptions[0];
        if (selectedOption && selectedOption.value) {
            return workflowStatesForLogicalFeed(selectedOption.value);
        }

        const seen = [];
        Array.from(logicalFeedFilter.options)
                .filter((option) => option.value)
                .forEach((option) => {
                    workflowStatesFromToken(option.dataset.workflowStates).forEach((state) => {
                        if (!seen.includes(state)) {
                            seen.push(state);
                        }
                    });
                });
        return seen;
    };

    const specialTagLabel = (key) => {
        if (key === "has-pdf") {
            return "Has PDF";
        }
        if (key === "new-papers") {
            return "New papers";
        }
        if (key.startsWith("state:")) {
            return statusLabel(key.slice("state:".length));
        }
        return key;
    };

    const tagFilterKeysForCard = (card) => {
        const keys = new Set(parsePaperTags(card));
        keys.add("state:" + (card.dataset.paperTopStatus || normalizeStatus(card.dataset.paperStatus)));
        if (card.dataset.pdfUrl) {
            keys.add("has-pdf");
        }
        if (card.dataset.paperIsNew === "true") {
            keys.add("new-papers");
        }
        return keys;
    };

    const tagFilterMatchesCard = (card, keys) => {
        if (!keys.length) {
            return true;
        }
        const cardKeys = tagFilterKeysForCard(card);
        return keys.every((key) => cardKeys.has(key));
    };

    const normalizedSearchText = (value) => {
        return (value || "").toLowerCase().replace(/\s+/g, " ").trim();
    };

    const parseSearchDateValue = (value) => {
        const rawValue = String(value ?? "");
        const withoutZeroWidth = rawValue.replace(/[\u200B-\u200D\uFEFF]/g, "");
        const normalized = withoutZeroWidth
            .replace(/[^\d-]/g, "")
            .trim();
        if (searchDebugEnabled) {
            console.log("[paper-monitor] parseSearchDateValue:start", {
                input: value,
                rawValue,
                rawChars: Array.from(rawValue).map((char) => ({
                    char,
                    code: char.charCodeAt(0)
                })),
                withoutZeroWidth,
                normalized
            });
        }
        const parts = normalized.split("-").filter((part) => part.length > 0);
        if (searchDebugEnabled) {
            console.log("[paper-monitor] parseSearchDateValue:parts", {
                normalized,
                parts,
                partMeta: parts.map((part) => ({
                    part,
                    length: part.length,
                    digitsOnly: isAsciiDigits(part)
                }))
            });
        }
        if (!parts.length || parts.length > 3) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "invalid-part-count",
                    normalized,
                    parts
                });
            }
            return null;
        }
        if (!isAsciiDigits(parts[0], 4)) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "invalid-year-format",
                    parts
                });
            }
            return null;
        }
        if (parts.length >= 2 && !isAsciiDigits(parts[1], 2)) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "invalid-month-format",
                    parts
                });
            }
            return null;
        }
        if (parts.length === 3 && !isAsciiDigits(parts[2], 2)) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "invalid-day-format",
                    parts
                });
            }
            return null;
        }
        const year = Number(parts[0]);
        const month = parts.length >= 2 ? Number(parts[1]) : null;
        const day = parts.length === 3 ? Number(parts[2]) : null;
        if (searchDebugEnabled) {
            console.log("[paper-monitor] parseSearchDateValue:numeric-values", {
                parts,
                year,
                month,
                day
            });
        }
        if (month != null && (month < 1 || month > 12)) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "month-out-of-range",
                    month
                });
            }
            return null;
        }
        const maxDay = month != null ? new Date(Date.UTC(year, month, 0)).getUTCDate() : null;
        if (searchDebugEnabled) {
            console.log("[paper-monitor] parseSearchDateValue:max-day", {
                year,
                month,
                maxDay
            });
        }
        if (day != null && (!maxDay || day < 1 || day > maxDay)) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseSearchDateValue:return-null", {
                    reason: "day-out-of-range",
                    day,
                    maxDay
                });
            }
            return null;
        }
        const parsedDateValue = {
            raw: normalized,
            precision: parts.length === 3 ? "day" : parts.length === 2 ? "month" : "year",
            year,
            month,
            day
        };
        if (searchDebugEnabled) {
            console.log("[paper-monitor] parseSearchDateValue:success", parsedDateValue);
        }
        return parsedDateValue;
    };

    const isAsciiDigits = (candidate, expectedLength = null) => {
        const text = String(candidate || "");
        if (expectedLength != null && text.length !== expectedLength) {
            return false;
        }
        if (!text.length) {
            return false;
        }
        for (const character of text) {
            if (character < "0" || character > "9") {
                return false;
            }
        }
        return true;
    };

    const formatSearchDateValue = (year, month = 1, day = 1) => {
        return [
            String(year).padStart(4, "0"),
            String(month).padStart(2, "0"),
            String(day).padStart(2, "0")
        ].join("-");
    };

    const lastDayOfMonth = (year, month) => new Date(Date.UTC(year, month, 0)).getUTCDate();

    const searchDateRange = (parsed) => {
        if (!parsed) {
            return null;
        }
        if (parsed.precision === "year") {
            return {
                start: formatSearchDateValue(parsed.year, 1, 1),
                end: formatSearchDateValue(parsed.year, 12, 31)
            };
        }
        if (parsed.precision === "month") {
            return {
                start: formatSearchDateValue(parsed.year, parsed.month, 1),
                end: formatSearchDateValue(parsed.year, parsed.month, lastDayOfMonth(parsed.year, parsed.month))
            };
        }
        return {
            start: parsed.raw,
            end: parsed.raw
        };
    };

    const parseStateSearchQuery = (value) => {
        const raw = String(value || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim();
        const directDateSource = raw.replace(/\s+/g, "");
        if (searchDebugEnabled && raw.toLowerCase().includes("date:")) {
            console.log("[paper-monitor] parseStateSearchQuery:start", {
                raw,
                directDateSource
            });
        }
        if (directDateSource.toLowerCase().startsWith("date:")) {
            let remainder = directDateSource.slice(5);
            let operator = "=";
            if (remainder.startsWith("<=") || remainder.startsWith(">=")) {
                operator = remainder.slice(0, 2);
                remainder = remainder.slice(2);
            } else if (["<", ">", "="].includes(remainder.charAt(0))) {
                operator = remainder.charAt(0);
                remainder = remainder.slice(1);
            }
            remainder = remainder.replace(/[.,;:!?]+$/, "");
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseStateSearchQuery:direct-date", {
                    raw,
                    directDateSource,
                    operator,
                    remainder
                });
            }
            const parsed = parseSearchDateValue(remainder);
            const parsedQuery = {
                raw,
                textQuery: "",
                dateFilters: parsed ? [{
                    operator,
                    value: parsed,
                    range: searchDateRange(parsed)
                }] : []
            };
            if (searchDebugEnabled) {
                console.log("[paper-monitor] Parsed direct date search query", parsedQuery);
            }
            return parsedQuery;
        }
        const dateFilters = [];
        const textTokens = [];
        const rawTokens = raw.split(/\s+/).filter(Boolean);
        if (searchDebugEnabled && raw.toLowerCase().includes("date:")) {
            console.log("[paper-monitor] parseStateSearchQuery:tokenized", {
                raw,
                rawTokens
            });
        }
        for (let index = 0; index < rawTokens.length; index += 1) {
            const token = rawTokens[index];
            const sanitizedToken = token.replace(/[.,;:!?]+$/, "");
            if (searchDebugEnabled && raw.toLowerCase().includes("date:")) {
                console.log("[paper-monitor] parseStateSearchQuery:token", {
                    index,
                    token,
                    sanitizedToken
                });
            }
            if (!sanitizedToken.toLowerCase().startsWith("date:")) {
                textTokens.push(token);
                continue;
            }

            let remainder = sanitizedToken.slice(5).trim();
            if (!remainder && index + 1 < rawTokens.length) {
                remainder = rawTokens[index + 1].replace(/[.,;:!?]+$/, "").trim();
                index += 1;
            }

            const operatorMatch = remainder.match(/^(<=|>=|<|>|=)/);
            const operator = operatorMatch ? operatorMatch[1] : "=";
            const dateValue = (operatorMatch ? remainder.slice(operator.length) : remainder).trim();
            if (searchDebugEnabled) {
                console.log("[paper-monitor] parseStateSearchQuery:token-date", {
                    token,
                    remainder,
                    operator,
                    dateValue
                });
            }
            const parsed = parseSearchDateValue(dateValue);
            if (!parsed) {
                if (searchDebugEnabled) {
                    console.log("[paper-monitor] parseStateSearchQuery:token-date-invalid", {
                        token,
                        dateValue
                    });
                }
                textTokens.push(token);
                continue;
            }
            dateFilters.push({
                operator,
                value: parsed,
                range: searchDateRange(parsed)
            });
        }
        const parsedQuery = {
            raw,
            textQuery: normalizedSearchText(textTokens.join(" ")),
            dateFilters
        };
        if (searchDebugEnabled && raw.toLowerCase().includes("date:")) {
            console.log("[paper-monitor] Parsed date search query", parsedQuery);
        }
        return parsedQuery;
    };

    const paperPublishedDateValue = (value) => {
        const normalized = String(value || "").trim();
        const parts = normalized.split("-").slice(0, 3);
        if (parts.length < 3) {
            return null;
        }
        const [year, month, dayWithPossibleSuffix] = parts;
        const day = String(dayWithPossibleSuffix || "").slice(0, 2);
        if (!isAsciiDigits(year, 4) || !isAsciiDigits(month, 2) || !isAsciiDigits(day, 2)) {
            return null;
        }
        return [year, month, day].join("-");
    };

    const publicationDateMatchesFilters = (publishedOn, dateFilters) => {
        if (!dateFilters.length) {
            return true;
        }
        const paperDate = paperPublishedDateValue(publishedOn);
        if (!paperDate) {
            if (searchDebugEnabled) {
                console.log("[paper-monitor] Date filter rejected paper because publication date is missing or invalid", {
                    publishedOn,
                    dateFilters
                });
            }
            return false;
        }
        const matches = dateFilters.every((filter) => {
            if (filter.operator === "<") {
                return paperDate < filter.range.start;
            }
            if (filter.operator === ">") {
                return paperDate > filter.range.end;
            }
            if (filter.operator === "<=") {
                return paperDate <= filter.range.end;
            }
            if (filter.operator === ">=") {
                return paperDate >= filter.range.start;
            }
            return paperDate >= filter.range.start && paperDate <= filter.range.end;
        });
        if (searchDebugEnabled) {
            console.log("[paper-monitor] Date filter evaluation", {
                publishedOn,
                paperDate,
                dateFilters,
                matches
            });
        }
        return matches;
    };

    const stateSearchMatchesCard = (card, searchQuery) => {
        if (!publicationDateMatchesFilters(card.dataset.paperPublishedOn, searchQuery.dateFilters)) {
            return false;
        }
        if (!searchQuery.textQuery) {
            return true;
        }
        return [
            card.dataset.paperTitle,
            card.dataset.paperSummary,
            card.dataset.paperAuthors,
            card.dataset.paperVenue
        ].some((value) => normalizedSearchText(value).includes(searchQuery.textQuery));
    };

    const stateSearchMatchesRecord = (record, searchQuery) => {
        if (!publicationDateMatchesFilters(record.paperPublishedOn, searchQuery.dateFilters)) {
            return false;
        }
        if (!searchQuery.textQuery) {
            return true;
        }
        return [
            record.paperTitle,
            record.paperSummary,
            record.paperAuthors,
            record.paperVenue
        ].some((value) => normalizedSearchText(value).includes(searchQuery.textQuery));
    };

    const currentFeedCards = () => {
        const selectedLogicalFeedId = logicalFeedFilter.value;
        if (!selectedLogicalFeedId) {
            return [];
        }
        if (serverRenderedShareMode) {
            return allPaperCards().filter((card) => card.dataset.logicalFeedId === selectedLogicalFeedId);
        }
        return paperRecords.filter((record) => String(record.logicalFeedId) === String(selectedLogicalFeedId));
    };

    const tagCandidatesForCurrentFeed = () => {
        if (!serverRenderedShareMode && browserFacets.length) {
            return browserFacets.map((facet) => ({
                key: facet.key,
                label: specialTagLabel(facet.key) || facet.label,
                count: facet.count || 0,
                secondary: Boolean(facet.secondary)
            }));
        }
        const cards = currentFeedCards();
        const workflow = selectedWorkflowStates();
        const entries = new Map();
        workflow.forEach((state) => {
            entries.set("state:" + state, { key: "state:" + state, label: statusLabel(state), secondary: true });
        });
        entries.set("has-pdf", { key: "has-pdf", label: "Has PDF", secondary: true });
        if (cards.some((card) => (serverRenderedShareMode ? card.dataset.paperIsNew === "true" : card.paperIsNew))) {
            entries.set("new-papers", { key: "new-papers", label: "New papers", secondary: true });
        }
        cards.forEach((card) => {
            const tags = serverRenderedShareMode ? parsePaperTags(card) : parsePaperTagsFromRecord(card);
            tags.forEach((tag) => {
                if (!entries.has(tag)) {
                    entries.set(tag, { key: tag, label: tag, secondary: false });
                }
            });
        });
        return Array.from(entries.values());
    };

    const normalizeTagInput = (value) => value.trim().replace(/\s+/g, " ");

    const applyBatchOperationToRecords = (records, operation, value) => {
        const normalizedValue = operation === "change_state" ? normalizeStatus(value) : normalizeTagInput(value);
        records.forEach((record) => {
            if (operation === "change_state") {
                record.paperStatus = normalizedValue;
                record.paperTopStatus = normalizedValue.includes("/") ? normalizedValue.split("/")[0] : normalizedValue;
                return;
            }
            const tags = parsePaperTagsFromRecord(record);
            if (operation === "add_tag") {
                const nextTags = Array.from(new Set([...tags, normalizedValue])).sort((left, right) => left.localeCompare(right));
                setPaperRecordTags(record, nextTags);
                return;
            }
            const nextTags = tags.filter((tag) => tag.toLowerCase() !== normalizedValue.toLowerCase());
            setPaperRecordTags(record, nextTags);
        });
    };

    const getNextInterestingStatus = (status, logicalFeedId) => {
        const targets = workflowTargetsFromState(logicalFeedId, status);
        return targets.length ? targets[0] : null;
    };

    const statusLabel = (status) => {
        return status.toLowerCase().replaceAll("_", " ");
    };

    const formatStateSummary = (workflowStates, counts) => {
        return workflowStates
            .map((status) => statusLabel(status) + " " + (counts[status] || 0))
            .join(" · ");
    };

    const findCardByPaperId = (paperId) => {
        return allPaperCards().find((card) => card.dataset.paperId === paperId) || null;
    };

    const refreshLogicalFeedOptionLabel = (option) => {
        if (!option || !option.value) {
            return;
        }
        const workflowStates = workflowStatesFromToken(option.dataset.workflowStates || "");
        const counts = stateCountsFromToken(option.dataset.stateCounts || "");
        const summary = formatStateSummary(workflowStates, counts);
        option.textContent = summary ? option.dataset.feedName + " (" + summary + ")" : option.dataset.feedName;
    };

    const reviewForLogicalFeed = (logicalFeedId) => {
        return reviewSummaries.find((review) => String(review.logicalFeedId) === String(logicalFeedId)) || null;
    };

    const updateReaderReviewAction = () => {
        if (!readerOpenReviewButton) {
            return;
        }
        const logicalFeedId = logicalFeedFilter?.value || "";
        const review = reviewForLogicalFeed(logicalFeedId);
        readerOpenReviewButton.disabled = !review;
        readerOpenReviewButton.title = review
            ? "Open " + (review.title || "the review") + "."
            : logicalFeedId
                ? "No review is configured for this paper feed."
                : "Select a paper feed first.";
    };

    const openReviewCreateModal = (logicalFeedId) => {
        if (!reviewCreateModal || !logicalFeedFilter) {
            return;
        }
        logicalFeedFilter.value = String(logicalFeedId || "");
        prepareReviewWizard();
        if (typeof reviewCreateModal.showModal === "function") {
            reviewCreateModal.showModal();
        } else {
            reviewCreateModal.setAttribute("open", "open");
        }
    };

    const closeReviewCreateModal = () => {
        if (!reviewCreateModal) {
            return;
        }
        if (typeof reviewCreateModal.close === "function") {
            reviewCreateModal.close();
        } else {
            reviewCreateModal.removeAttribute("open");
        }
    };

    const refreshRqOrdinals = () => {
        Array.from(rqEditorList?.children || []).forEach((row, index) => {
            row.querySelector(".rq-code").textContent = "RQ " + (index + 1);
            row.querySelector(".rq-question").setAttribute("aria-label", "Research question " + (index + 1));
        });
    };

    const createRqRow = (question = {}) => {
        const row = document.createElement("div");
        row.className = "rq-editor-row";
        row.draggable = true;
        row.dataset.key = question.key || "";

        const header = document.createElement("div");
        header.className = "rq-editor-row-header";
        const code = document.createElement("span");
        code.className = "rq-code";
        const actions = document.createElement("div");
        actions.className = "rq-editor-actions";
        [["↑", "Move question up", -1], ["↓", "Move question down", 1]].forEach(([icon, title, direction]) => {
            const button = document.createElement("button");
            button.className = "icon-button secondary";
            button.type = "button";
            button.title = title;
            button.textContent = icon;
            button.addEventListener("click", () => {
                const sibling = direction < 0 ? row.previousElementSibling : row.nextElementSibling;
                if (!sibling) return;
                if (direction < 0) rqEditorList.insertBefore(row, sibling);
                else rqEditorList.insertBefore(sibling, row);
                refreshRqOrdinals();
            });
            actions.append(button);
        });
        const remove = document.createElement("button");
        remove.className = "icon-button secondary";
        remove.type = "button";
        remove.title = "Remove question";
        remove.textContent = "×";
        remove.addEventListener("click", () => {
            if (rqEditorList.children.length <= 1) {
                reviewMenuStatus.textContent = "A review must contain at least one research question.";
                return;
            }
            row.remove();
            refreshRqOrdinals();
        });
        actions.append(remove);
        header.append(code, actions);

        const input = document.createElement("textarea");
        input.className = "rq-question";
        input.maxLength = 2000;
        input.placeholder = "What should the reviewer determine from this paper?";
        input.value = question.question || "";
        const requiredLabel = document.createElement("label");
        requiredLabel.className = "rq-required meta";
        const required = document.createElement("input");
        required.type = "checkbox";
        required.className = "rq-required-input";
        required.checked = Boolean(question.required);
        requiredLabel.append(required, document.createTextNode("Required before the paper is complete"));
        row.append(header, input, requiredLabel);

        row.addEventListener("dragstart", () => {
            draggedRqRow = row;
            row.classList.add("dragging");
        });
        row.addEventListener("dragend", () => {
            draggedRqRow = null;
            row.classList.remove("dragging");
            refreshRqOrdinals();
        });
        return row;
    };

    const renderRqEditor = (questions) => {
        rqEditorList.innerHTML = "";
        const rows = questions.length ? questions : new Array(5).fill(null).map((_, index) => ({ question: "RQ" + (index + 1) }));
        rows.forEach((question) => rqEditorList.append(createRqRow(question)));
        refreshRqOrdinals();
    };

    const collectResearchQuestions = () => Array.from(rqEditorList.children).map((row) => {
        const result = {
            "question": row.querySelector(".rq-question").value.trim(),
            "required": row.querySelector(".rq-required-input").checked
        };
        if (row.dataset.key) result.key = row.dataset.key;
        return result;
    });

    if (rqEditorList) {
        rqEditorList.addEventListener("dragover", (event) => {
            event.preventDefault();
            if (!draggedRqRow) return;
            const target = event.target.closest(".rq-editor-row");
            if (!target || target === draggedRqRow) return;
            const after = event.clientY > target.getBoundingClientRect().top + target.offsetHeight / 2;
            rqEditorList.insertBefore(draggedRqRow, after ? target.nextSibling : target);
        });
    }

    const setReviewWizardStep = (step) => {
        reviewWizardSteps.forEach((panel) => {
            panel.hidden = panel.dataset.reviewWizardStep !== step;
        });
    };

    const reviewFeedName = () => logicalFeedFilter?.selectedOptions?.[0]?.dataset?.feedName || "Paper feed";
    const reviewSchemeTitle = () => reviewFeedName() + " review";
    const normalizedQuestions = (questions) => questions.map((question) => ({
        key: question.key || "",
        question: (question.question || "").trim(),
        required: Boolean(question.required)
    }));
    const reviewQuestionsChanged = (questions) => JSON.stringify(normalizedQuestions(questions))
        !== JSON.stringify(normalizedQuestions(reviewBaseQuestions));

    const validateReviewQuestions = () => {
        const questions = collectResearchQuestions();
        const missingIndex = questions.findIndex((question) => !question.question);
        if (missingIndex >= 0) {
            reviewMenuStatus.textContent = "Enter a question for RQ " + (missingIndex + 1) + ".";
            rqEditorList.children[missingIndex].querySelector(".rq-question").focus();
            return null;
        }
        return questions;
    };

    const renderReviewScope = () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        reviewStateList.innerHTML = "";
        workflowStatesForLogicalFeed(logicalFeedId).forEach((state, index) => {
            const label = document.createElement("label");
            label.className = "review-state-option";
            label.innerHTML = "<input type=\"checkbox\" name=\"review-selected-state\" value=\"" + state + "\""
                + (index === 0 ? " checked" : "") + "><span>" + statusLabel(state) + "</span>";
            reviewStateList.appendChild(label);
        });
    };

    const renderReviewTemplateOptions = (selectedTemplateId = "") => {
        if (!reviewTemplateSelect) return;
        reviewTemplateSelect.innerHTML = "";
        const placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = reviewTemplates.length
            ? "Select a PDE review model"
            : "No PDE review models available";
        reviewTemplateSelect.appendChild(placeholder);
        reviewTemplates.forEach((template) => {
            const option = document.createElement("option");
            option.value = template.id;
            option.textContent = template.title + (template.revision ? " · revision " + template.revision : "");
            reviewTemplateSelect.appendChild(option);
        });
        if (selectedTemplateId && reviewTemplates.some((template) => template.id === selectedTemplateId)) {
            reviewTemplateSelect.value = selectedTemplateId;
        }
    };

    const refreshReviewTemplates = async () => {
        const selectedTemplateId = reviewTemplateSelect?.value || "";
        const response = await fetch("/api/review-templates", { headers: { Accept: "application/json" } });
        const body = await response.text();
        if (!response.ok) throw new Error(body || "Could not load PDE review models.");
        reviewTemplates = JSON.parse(body);
        renderReviewTemplateOptions(selectedTemplateId);
    };

    const prepareReviewWizard = () => {
        if (!logicalFeedFilter?.value) return;
        reviewWizardLoadSequence += 1;
        if (reviewModalTitle) reviewModalTitle.textContent = "Create review for " + reviewFeedName();
        reviewCreationMode = null;
        reviewBaseQuestions = [];
        renderRqEditor([]);
        renderReviewTemplateOptions();
        renderReviewScope();
        reviewWizardQuestionsNext.disabled = true;
        setReviewWizardStep("mode");
        const existing = reviewForLogicalFeed(logicalFeedFilter.value);
        reviewMenuStatus.textContent = existing
            ? "A live review already exists and will be replaced. Choose how to build the new review."
            : "Choose a guided setup or an existing PDE review model.";
    };

    const startGuidedReviewWizard = async () => {
        const loadSequence = ++reviewWizardLoadSequence;
        reviewCreationMode = "wizard";
        setReviewWizardStep("questions");
        reviewWizardQuestionsNext.disabled = true;
        reviewMenuStatus.textContent = "Loading the five-question review scheme...";
        try {
            const response = await fetch("/api/review-templates/" + builtInReviewTemplateId, {
                headers: { Accept: "application/json" }
            });
            const body = await response.text();
            if (!response.ok) throw new Error(body || "Could not load the built-in review scheme.");
            const detail = JSON.parse(body);
            if (loadSequence !== reviewWizardLoadSequence || reviewCreationMode !== "wizard") return;
            reviewBaseQuestions = detail.researchQuestions || [];
            renderRqEditor(reviewBaseQuestions);
            reviewWizardQuestionsNext.disabled = false;
            reviewMenuStatus.textContent = "Tailor the research questions, then choose the review scope.";
        } catch (error) {
            if (loadSequence !== reviewWizardLoadSequence || reviewCreationMode !== "wizard") return;
            reviewBaseQuestions = [];
            renderRqEditor([]);
            reviewMenuStatus.textContent = error.message || "Could not load the built-in review scheme.";
        }
    };

    const startPdeReviewFlow = async () => {
        reviewCreationMode = "pde";
        setReviewWizardStep("model");
        renderReviewTemplateOptions(reviewTemplateSelect?.value || "");
        reviewMenuStatus.textContent = reviewTemplates.length
            ? "Choose a PDE review model."
            : "Loading PDE review models...";
        if (reviewTemplates.length) return;
        try {
            await refreshReviewTemplates();
            reviewMenuStatus.textContent = reviewTemplates.length
                ? "Choose a PDE review model."
                : "No PDE review models are available. Create one in PDE, then refresh the list.";
        } catch (error) {
            reviewMenuStatus.textContent = error.message || "Could not load PDE review models.";
        }
    };

    const scholarCache = {
        feeds: null,
        history: null
    };

    const scholarText = (value) => value == null ? "" : String(value);

    const scholarSnippet = (value, maxLength = 260) => {
        const normalized = scholarText(value).replace(/\s+/g, " ").trim();
        return normalized.length > maxLength ? normalized.slice(0, maxLength - 1) + "..." : normalized;
    };

    const scholarFeedName = (item, prefix) => {
        const id = item.id || item.queryId || "";
        return (prefix || "Scholar feed") + (id ? " " + id : "");
    };

    const fetchScholarRows = async (kind) => {
        if (scholarCache[kind]) {
            return scholarCache[kind];
        }
        const response = await fetch(kind === "history" ? "/api/scholar/history" : "/api/scholar/feeds", {
            headers: { Accept: "application/json" }
        });
        const body = await response.text();
        if (!response.ok) {
            throw new Error(body || "Could not load MIAGE Scholar sources.");
        }
        scholarCache[kind] = JSON.parse(body);
        return scholarCache[kind];
    };

    const fillScholarForm = (picker, name, url) => {
        const form = picker.closest("form");
        const nameField = form?.querySelector("[data-scholar-name-field]");
        const urlField = form?.querySelector("[data-scholar-url-field]");
        if (nameField && name) {
            nameField.value = name;
        }
        if (urlField && url) {
            urlField.value = url;
            urlField.dispatchEvent(new Event("input", { bubbles: true }));
        }
    };

    const createFeedFromScholarHistory = async (picker, item, status) => {
        const queryId = item.queryId || item.id;
        if (!queryId) {
            status.textContent = "This history entry has no query id.";
            return;
        }
        status.textContent = "Creating RSS feed from Scholar query " + queryId + "...";
        const response = await fetch("/api/scholar/history/" + encodeURIComponent(queryId) + "/feed", {
            method: "POST",
            headers: { Accept: "application/json" }
        });
        const body = await response.text();
        if (!response.ok) {
            throw new Error(body || "Could not create RSS feed from this Scholar query.");
        }
        const created = JSON.parse(body);
        fillScholarForm(picker, scholarFeedName(created, "Scholar query"), created.rssUrl);
        status.textContent = "Selected Scholar query " + queryId + ".";
    };

    const renderScholarPicker = async (picker) => {
        const activeTab = picker.dataset.scholarActiveTab || "feeds";
        const search = scholarText(picker.querySelector("[data-scholar-search]")?.value).toLowerCase().trim();
        const status = picker.querySelector("[data-scholar-status]");
        const results = picker.querySelector("[data-scholar-results]");
        if (!status || !results) {
            return;
        }
        picker.querySelectorAll("[data-scholar-tab]").forEach((button) => {
            button.classList.toggle("active", button.dataset.scholarTab === activeTab);
        });
        status.textContent = activeTab === "history" ? "Loading recent Scholar queries..." : "Loading Scholar feeds...";
        results.innerHTML = "";
        try {
            const rows = await fetchScholarRows(activeTab);
            const filtered = rows.filter((item) => {
                const haystack = [
                    item.id,
                    item.queryId,
                    item.query,
                    item.rssUrl,
                    item.permalinkUrl
                ].map(scholarText).join(" ").toLowerCase();
                return !search || haystack.includes(search);
            }).slice(0, 20);
            status.textContent = filtered.length
                ? "Showing " + filtered.length + " Scholar " + (activeTab === "history" ? "queries." : "feeds.")
                : "No Scholar source matches this search.";
            filtered.forEach((item) => {
                const card = document.createElement("div");
                card.className = "scholar-result";
                const query = document.createElement("p");
                query.className = "scholar-result-query";
                query.textContent = scholarSnippet(item.query || item.rssUrl || item.permalinkUrl || "Untitled Scholar source");
                const meta = document.createElement("p");
                meta.className = "scholar-result-meta";
                meta.textContent = activeTab === "history"
                    ? "Query " + (item.queryId || item.id || "?") + " · " + (item.count ?? "?") + " result(s) · " + (item.timestamp || "unknown date")
                    : "Feed " + (item.id || "?") + " · " + (item.count ?? "?") + " paper(s) · " + (item.hit ?? "?") + " hit(s)";
                const choose = document.createElement("button");
                choose.className = "secondary";
                choose.type = "button";
                choose.textContent = activeTab === "history" ? "Create RSS from this query" : "Use this feed";
                choose.addEventListener("click", async () => {
                    try {
                        if (activeTab === "history") {
                            await createFeedFromScholarHistory(picker, item, status);
                        } else {
                            fillScholarForm(picker, scholarFeedName(item, "Scholar feed"), item.rssUrl);
                            status.textContent = "Selected Scholar feed " + (item.id || "") + ".";
                        }
                    } catch (error) {
                        status.textContent = error.message || "Scholar selection failed.";
                    }
                });
                card.append(query, meta, choose);
                results.appendChild(card);
            });
        } catch (error) {
            status.textContent = error.message || "Could not load MIAGE Scholar sources.";
        }
    };

    document.querySelectorAll("[data-scholar-picker]").forEach((picker) => {
        picker.dataset.scholarActiveTab = "feeds";
        picker.querySelectorAll("[data-scholar-tab]").forEach((button) => {
            button.addEventListener("click", () => {
                picker.dataset.scholarActiveTab = button.dataset.scholarTab || "feeds";
                renderScholarPicker(picker);
            });
        });
        picker.querySelector("[data-scholar-search]")?.addEventListener("input", () => renderScholarPicker(picker));
        renderScholarPicker(picker);
    });

    const openFeedCreateModal = (logicalFeedId, logicalFeedName) => {
        if (!feedCreateModal || !feedCreateLogicalFeedId || !feedCreateReturnTo) {
            return;
        }
        feedCreateLogicalFeedId.value = logicalFeedId || "";
        feedCreateReturnTo.value = logicalFeedId ? "/?logicalFeedId=" + encodeURIComponent(logicalFeedId) : "/";
        if (feedCreateModalTitle) {
            feedCreateModalTitle.textContent = "Add RSS feed";
        }
        if (feedCreateModalStatus) {
            feedCreateModalStatus.textContent = logicalFeedName
                ? 'Add a new RSS source to "' + logicalFeedName + '". Default polling frequency is 1 day.'
                : "Add a new RSS source to this paper feed. Default polling frequency is 1 day.";
        }
        if (feedCreateForm) {
            feedCreateForm.reset();
        }
        if (feedCreateLogicalFeedId) {
            feedCreateLogicalFeedId.value = logicalFeedId || "";
        }
        if (feedCreateReturnTo) {
            feedCreateReturnTo.value = logicalFeedId ? "/?logicalFeedId=" + encodeURIComponent(logicalFeedId) : "/";
        }
        if (feedCreateName && logicalFeedName) {
            feedCreateName.value = logicalFeedName + " RSS";
        }
        if (typeof feedCreateModal.showModal === "function") {
            feedCreateModal.showModal();
        } else {
            feedCreateModal.setAttribute("open", "open");
        }
        window.setTimeout(() => {
            feedCreateUrl?.focus();
        }, 0);
    };

    const closeFeedCreateModal = () => {
        if (!feedCreateModal) {
            return;
        }
        if (typeof feedCreateModal.close === "function") {
            feedCreateModal.close();
        } else {
            feedCreateModal.removeAttribute("open");
        }
    };

    const closeFeedDeleteModal = () => {
        deletingLogicalFeedName = "";
        feedDeleteForm?.reset();
        if (feedDeleteSubmit) feedDeleteSubmit.disabled = true;
        if (typeof feedDeleteModal?.close === "function") feedDeleteModal.close();
        else feedDeleteModal?.removeAttribute("open");
    };

    const openFeedDeleteModal = (logicalFeedId, logicalFeedName) => {
        if (!feedDeleteModal || !feedDeleteForm || !logicalFeedId) return;
        deletingLogicalFeedName = logicalFeedName || "";
        feedDeleteForm.action = "/logical-feeds/" + encodeURIComponent(logicalFeedId) + "/delete";
        feedDeleteForm.reset();
        if (feedDeleteModalStatus) feedDeleteModalStatus.textContent = 'To delete "' + deletingLogicalFeedName + '", type its name exactly below.';
        if (feedDeleteSubmit) feedDeleteSubmit.disabled = true;
        if (typeof feedDeleteModal.showModal === "function") feedDeleteModal.showModal();
        else feedDeleteModal.setAttribute("open", "open");
        window.setTimeout(() => feedDeleteExpectedName?.focus(), 0);
    };

    const closePaperImportModal = () => {
        if (!paperImportModal) {
            return;
        }
        if (typeof paperImportModal.close === "function") {
            paperImportModal.close();
        } else {
            paperImportModal.removeAttribute("open");
        }
    };

    const showPaperImportTab = (mode, focus = true) => {
        const showDoi = mode !== "web";
        paperImportDoiTab?.classList.toggle("active", showDoi);
        paperImportDoiTab?.setAttribute("aria-selected", String(showDoi));
        paperImportDoiTab?.setAttribute("tabindex", showDoi ? "0" : "-1");
        paperImportWebTab?.classList.toggle("active", !showDoi);
        paperImportWebTab?.setAttribute("aria-selected", String(!showDoi));
        paperImportWebTab?.setAttribute("tabindex", showDoi ? "-1" : "0");
        paperImportDoiPanel?.classList.toggle("hidden", !showDoi);
        paperImportWebPanel?.classList.toggle("hidden", showDoi);
        if (paperImportDoiPanel) paperImportDoiPanel.hidden = !showDoi;
        if (paperImportWebPanel) paperImportWebPanel.hidden = showDoi;
        if (focus) {
            window.setTimeout(() => (showDoi ? manualImportDoi : urlImportUrl)?.focus(), 0);
        }
    };

    const prepareDoiImport = () => {
        if (!doiImportLogicalFeed || !syncDoiImportLogicalFeed()) {
            return false;
        }
        manualImportForm?.reset();
        resetManualImportPreview();
        syncManualImportControls();
        setManualImportStatus("");
        if (doiImportModalStatus) {
            const feedName = logicalFeedFilter.selectedOptions?.[0]?.dataset?.feedName || "the current paper feed";
            doiImportModalStatus.textContent = "Paste one DOI or several DOIs separated by spaces or commas. The papers will be added to " + feedName + ".";
        }
        return true;
    };

    const prepareUrlImport = () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        if (!logicalFeedId || !Array.isArray(config?.states)) {
            return false;
        }
        urlImportForm?.reset();
        urlImportStatus.replaceChildren();
        config.states.forEach((state) => {
            const option = document.createElement("option");
            option.value = state.id;
            option.textContent = state.label || state.id;
            if (state?.report?.prismaBucket === "OTHER_IDENTIFIED") {
                option.selected = true;
            }
            urlImportStatus.append(option);
        });
        setUrlImportFeedback("");
        const feedName = logicalFeedFilter.selectedOptions?.[0]?.dataset?.feedName || "the current paper feed";
        urlImportModalStatus.textContent = "Add a web resource to " + feedName + ".";
        return true;
    };

    const openPaperImportModal = () => {
        if (!paperImportModal || !prepareDoiImport() || !prepareUrlImport()) {
            window.alert("Select a paper feed before importing a paper.");
            return;
        }
        showPaperImportTab("doi", false);
        if (typeof paperImportModal.showModal === "function") {
            paperImportModal.showModal();
        } else {
            paperImportModal.setAttribute("open", "open");
        }
        window.setTimeout(() => manualImportDoi?.focus(), 0);
    };

    const setUrlImportFeedback = (message, error = false) => {
        if (!urlImportFeedback) {
            return;
        }
        urlImportFeedback.textContent = message || "";
        urlImportFeedback.classList.toggle("hidden", !message);
        urlImportFeedback.classList.toggle("error", error);
    };

    const setBatchPaperFeedback = (message, isError = false) => {
        if (!batchPaperFormFeedback) {
            return;
        }
        batchPaperFormFeedback.textContent = message || "";
        batchPaperFormFeedback.classList.toggle("hidden", !message);
        batchPaperFormFeedback.style.color = isError ? "var(--danger, #a33)" : "";
    };

    const currentBatchPaperRecords = () => shareMode ? [] : filteredPaperRecords();

    const renderBatchPaperStatusOptions = () => {
        if (!batchPaperStatus) {
            return;
        }
        const logicalFeedId = logicalFeedFilter?.value || "";
        const workflowTree = workflowTreeForLogicalFeed(logicalFeedId);
        batchPaperStatus.innerHTML = "";
        workflowTree.forEach((group) => {
            const choices = stateLeafChoices(group);
            if (choices.length === 1 && choices[0].value === group?.name) {
                const option = document.createElement("option");
                option.value = choices[0].value;
                option.textContent = statusLabel(choices[0].value);
                batchPaperStatus.appendChild(option);
                return;
            }
            const optgroup = document.createElement("optgroup");
            optgroup.label = statusLabel(group?.name || "");
            choices.forEach((choice) => {
                const option = document.createElement("option");
                option.value = choice.value;
                option.textContent = statusLabel(choice.value);
                optgroup.appendChild(option);
            });
            batchPaperStatus.appendChild(optgroup);
        });
    };

    const syncBatchPaperOperation = () => {
        const operation = batchPaperOperation?.value || "change_state";
        batchPaperStatusField?.classList.toggle("hidden", operation !== "change_state");
        batchPaperTagField?.classList.toggle("hidden", operation === "change_state");
        if (batchPaperStatus) {
            batchPaperStatus.required = operation === "change_state";
        }
        if (batchPaperTag) {
            batchPaperTag.required = operation !== "change_state";
        }
    };

    const updateBatchPaperSummary = () => {
        const records = currentBatchPaperRecords();
        const feedName = logicalFeedFilter?.selectedOptions?.[0]?.dataset?.feedName || "the current paper feed";
        const count = shareMode ? records.length : browserTotal;
        if (batchPaperModalStatus) {
            batchPaperModalStatus.textContent = count
                ? "Apply an operation to " + count + " paper(s) currently matching the filters in " + feedName + "."
                : "No papers currently match the filters in " + feedName + ".";
        }
        if (batchPaperFormSummary) {
            batchPaperFormSummary.textContent = count
                ? count + " paper(s) will be affected."
                : "Adjust the filters first so at least one paper matches.";
        }
        if (batchPaperApply) {
            batchPaperApply.disabled = count === 0;
        }
    };

    const refreshLogicalFeedStateCounts = (logicalFeedId) => {
        if (!logicalFeedId) {
            return;
        }
        const option = Array.from(logicalFeedFilter.options).find((item) => item.value === logicalFeedId);
        if (!option) {
            return;
        }
        const counts = {};
        paperRecords
            .filter((record) => String(record.logicalFeedId) === String(logicalFeedId))
            .forEach((record) => {
                const status = record.paperTopStatus || normalizeStatus(record.paperStatus);
                counts[status] = (counts[status] || 0) + 1;
            });
        option.dataset.stateCounts = Object.entries(counts)
            .map(([status, count]) => status + ":" + count)
            .join("|");
    };

    const openBatchPaperModal = () => {
        if (!batchPaperModal || !logicalFeedFilter?.value) {
            window.alert("Select a paper feed before using batch updates.");
            return;
        }
        batchPaperForm?.reset();
        renderBatchPaperStatusOptions();
        syncBatchPaperOperation();
        setBatchPaperFeedback("");
        updateBatchPaperSummary();
        if (typeof batchPaperModal.showModal === "function") {
            batchPaperModal.showModal();
        } else {
            batchPaperModal.setAttribute("open", "open");
        }
        window.setTimeout(() => {
            batchPaperOperation?.focus();
        }, 0);
    };

    const closeBatchPaperModal = () => {
        if (!batchPaperModal) {
            return;
        }
        if (typeof batchPaperModal.close === "function") {
            batchPaperModal.close();
        } else {
            batchPaperModal.removeAttribute("open");
        }
    };

    const feedDashboardCards = () => Array.from(feedDashboard?.querySelectorAll("[data-feed-card]") || []);

    const feedDashboardOrder = () => feedDashboardCards()
        .map((card) => card.dataset.logicalFeedId)
        .filter(Boolean);

    const restoreFeedDashboardOrder = (order) => {
        if (!feedDashboard) {
            return;
        }
        const cardsById = new Map(feedDashboardCards().map((card) => [card.dataset.logicalFeedId, card]));
        order.forEach((logicalFeedId) => {
            const card = cardsById.get(logicalFeedId);
            if (card) {
                feedDashboard.appendChild(card);
            }
        });
    };

    const saveFeedDashboardOrder = async (previousOrder) => {
        if (!feedDashboard || feedDashboardOrderSaving) {
            return;
        }
        feedDashboardOrderSaving = true;
        try {
            const response = await fetch("/logical-feeds/dashboard-order", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                    "Accept": "application/json"
                },
                body: new URLSearchParams(feedDashboardOrder().map((logicalFeedId) => ["feedIds", logicalFeedId]))
            });
            if (!response.ok) {
                throw new Error(await response.text() || "Unable to save the paper feed order.");
            }
        } catch (error) {
            restoreFeedDashboardOrder(previousOrder);
            window.alert(error.message || "Unable to save the paper feed order.");
        } finally {
            feedDashboardOrderSaving = false;
        }
    };

    const clearFeedDashboardDragState = () => {
        draggedFeedDashboardCard?.classList.remove("dragging");
        feedDashboardCards().forEach((card) => card.classList.remove("drag-over"));
        draggedFeedDashboardCard = null;
    };

    const renderFeedDashboardReviewActions = () => {
        if (!feedDashboard) {
            return;
        }
        feedDashboard.querySelectorAll("[data-feed-card]").forEach((card) => {
            const logicalFeedId = card.dataset.logicalFeedId;
            const target = document.getElementById("feed-review-action-" + logicalFeedId);
            if (!target) {
                return;
            }
            const existing = reviewForLogicalFeed(logicalFeedId);
            if (existing) {
                target.innerHTML = "<button class=\"secondary feed-dashboard-icon-action review\" type=\"button\" data-open-live-review=\"" + existing.id + "\" title=\"Open review\" aria-label=\"Open review\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\" aria-hidden=\"true\"><path d=\"M8 2.5 14 5.5v5L8 13.5l-6-3v-5l6-3Zm0 1.1L3.2 6 8 8.4 12.8 6 8 3.6Zm-5 3.2v3l4.5 2.2v-3L3 6.8Zm5.5 5.2 4.5-2.2v-3L8.5 9v3Z\"/></svg><span>Open review</span></button>";
                const openReviewButton = target.querySelector("[data-open-live-review]");
                openReviewButton?.addEventListener("click", () => {
                    window.location.href = "/reviews/" + existing.id;
                });
                return;
            }
            target.innerHTML = "<button class=\"secondary feed-dashboard-icon-action review\" type=\"button\" data-open-create-review-card=\"" + logicalFeedId + "\" title=\"Create review\" aria-label=\"Create review\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\" aria-hidden=\"true\"><path d=\"M7.5 2h1v5.5H14v1H8.5V14h-1V8.5H2v-1h5.5V2Z\"/></svg><span>Create review</span></button>";
            target.querySelector("[data-open-create-review-card]")?.addEventListener("click", () => openReviewCreateModal(logicalFeedId));
        });
    };

    const renderFrontPagePanels = () => {
        const selectedFeedId = logicalFeedFilter?.value || "";
        if (feedDashboardPanel && !shareMode) {
            feedDashboardPanel.classList.toggle("hidden", Boolean(selectedFeedId));
        }
    };

    const selectedReviewStates = () => {
        return Array.from(reviewStateList?.querySelectorAll("input[name='review-selected-state']:checked") || [])
            .map((input) => input.value);
    };

    const loadReviewWorkspace = async () => {
        if (!authenticated) {
            return;
        }
        try {
            const reviewsResponse = await fetch("/api/reviews", { headers: { Accept: "application/json" } });
            reviewSummaries = reviewsResponse.ok ? await reviewsResponse.json() : [];
        } catch (error) {
            reviewSummaries = [];
            if (reviewMenuStatus) {
                reviewMenuStatus.textContent = error.message || "Failed to load review workspace.";
            }
        }
        renderReviewTemplateOptions(reviewTemplateSelect?.value || "");
        renderFeedDashboardReviewActions();
        updateReaderReviewAction();
    };

    const renderReviewLauncher = () => {};

    const selectedExportStates = () => {
        return Array.from(exportStateList?.querySelectorAll("input[name='export-selected-state']:checked") || [])
            .map((input) => input.value);
    };

    const setPaperExportError = (message = "") => {
        if (!paperExportError) {
            return;
        }
        paperExportError.textContent = message;
        paperExportError.classList.toggle("hidden", !message);
    };

    const updatePaperExportControls = () => {
        const hasSelection = selectedExportStates().length > 0;
        exportFormatButtons.forEach((button) => {
            button.disabled = !(logicalFeedFilter?.value || "");
        });
        if (hasSelection) {
            setPaperExportError();
        }
    };

    const renderExportStateOptions = () => {
        if (!exportStateList) {
            return;
        }
        const logicalFeedId = logicalFeedFilter?.value || "";
        exportStateList.innerHTML = "";
        if (!logicalFeedId) {
            exportStateList.innerHTML = "<p class=\"meta\">Select a paper feed to choose states.</p>";
            updatePaperExportControls();
            return;
        }
        workflowStatesForLogicalFeed(logicalFeedId).forEach((state) => {
            const label = document.createElement("label");
            label.className = "export-state-option";
            label.innerHTML = "<input type=\"checkbox\" name=\"export-selected-state\" value=\"" + state + "\" checked><span>" + statusLabel(state) + "</span>";
            exportStateList.appendChild(label);
        });
        updatePaperExportControls();
    };

    const closePaperExportModal = () => {
        if (typeof paperExportModal?.close === "function") {
            paperExportModal.close();
        } else {
            paperExportModal?.removeAttribute("open");
        }
    };

    const openPaperExportModal = () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        if (!paperExportModal || !logicalFeedId) {
            window.alert("Select a paper feed before exporting.");
            return;
        }
        renderExportStateOptions();
        setPaperExportError();
        if (paperExportScope) {
            const feedName = logicalFeedFilter.selectedOptions?.[0]?.dataset?.feedName || "the current paper feed";
            const filterNote = selectedTagFilters.length
                ? " Current tag filters will also be applied."
                : "";
            paperExportScope.textContent = "Choose what to export from " + feedName + "." + filterNote;
        }
        if (typeof paperExportModal.showModal === "function") {
            paperExportModal.showModal();
        } else {
            paperExportModal.setAttribute("open", "open");
        }
        window.setTimeout(() => exportStateList?.querySelector("input")?.focus(), 0);
    };

    const renderTagBrowser = () => {
        if (!tagBrowser || !tagBadgeList || !tagBrowserMeta) {
            return;
        }
        const visible = activePrimaryTab === "tags";
        tagBrowser.classList.toggle("visible", visible);
        if (!visible) {
            return;
        }

        const cards = currentFeedCards();
        if (!logicalFeedFilter.value) {
            tagBadgeList.innerHTML = "";
            tagBrowserMeta.textContent = "Select a paper feed to browse tags.";
            return;
        }

        const candidates = tagCandidatesForCurrentFeed()
            .map((candidate) => {
                if (!serverRenderedShareMode && browserFacets.length) {
                    return candidate;
                }
                const stateKeys = selectedTagFilters.filter((key) => key.startsWith("state:"));
                let filterKeys = selectedTagFilters.filter((key) => key !== candidate.key);
                if (candidate.key.startsWith("state:") && stateKeys.length) {
                    filterKeys = filterKeys.filter((key) => !key.startsWith("state:"));
                }
                if (!selectedTagFilters.includes(candidate.key)) {
                    filterKeys = [...filterKeys, candidate.key];
                } else {
                    filterKeys = [...filterKeys, candidate.key];
                }
                const count = cards.filter((card) => serverRenderedShareMode
                    ? tagFilterMatchesCard(card, filterKeys)
                    : tagFilterMatchesRecord(card, filterKeys)).length;
                return { ...candidate, count };
            })
            .sort((left, right) => (right.count - left.count) || left.label.localeCompare(right.label));

        tagBadgeList.innerHTML = "";
        candidates.forEach((candidate) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "badge-button"
                + (selectedTagFilters.includes(candidate.key) ? " active" : "")
                + (candidate.secondary ? " secondary-badge" : "");
            button.textContent = candidate.label + " (" + candidate.count + ")";
            button.addEventListener("click", () => {
                if (candidate.key.startsWith("state:")) {
                    selectedTagFilters = selectedTagFilters.filter((key) => !key.startsWith("state:"));
                }
                if (selectedTagFilters.includes(candidate.key)) {
                    selectedTagFilters = selectedTagFilters.filter((key) => key !== candidate.key);
                } else {
                    selectedTagFilters = [...selectedTagFilters, candidate.key];
                }
                applyLogicalFeedFilter();
            });
            tagBadgeList.appendChild(button);
        });

        const matchingCount = !serverRenderedShareMode && browserFacets.length ? browserTotal : cards.filter((card) => serverRenderedShareMode
            ? tagFilterMatchesCard(card, selectedTagFilters)
            : tagFilterMatchesRecord(card, selectedTagFilters)).length;
        tagBrowserMeta.textContent = selectedTagFilters.length
            ? matchingCount + " paper(s) match " + selectedTagFilters.map(specialTagLabel).join(", ")
            : (!serverRenderedShareMode && browserFacets.length ? browserTotal : cards.length) + " paper(s) in this paper feed.";
    };

    const feedTagSuggestions = (logicalFeedId, currentPaperId = null) => {
        const suggestions = new Set();
        if (serverRenderedShareMode) {
            allPaperCards()
                .filter((card) => card.dataset.logicalFeedId === logicalFeedId && card.dataset.paperId !== currentPaperId)
                .forEach((card) => {
                    parsePaperTags(card).forEach((tag) => suggestions.add(tag));
                });
        } else {
            paperRecords
                .filter((record) => String(record.logicalFeedId) === String(logicalFeedId) && String(record.id) !== String(currentPaperId))
                .forEach((record) => {
                    parsePaperTagsFromRecord(record).forEach((tag) => suggestions.add(tag));
                });
        }
        return Array.from(suggestions).sort((left, right) => left.localeCompare(right));
    };

    const renderPaperTagEditor = (card) => {
        if (!paperTagEditor || !paperTagList || !paperTagAdd || !paperTagSuggestions) {
            return;
        }
        if (!card) {
            paperTagEditor.style.display = "none";
            return;
        }
        paperTagEditor.style.display = "grid";
        const tags = parsePaperTags(card);
        const canEditTags = canEdit && card.dataset.paperCanEditTags === "true";
        paperTagList.innerHTML = "";
        tags.forEach((tag) => {
            const badge = document.createElement("span");
            badge.className = "paper-tag";
            badge.textContent = tag;
            if (canEditTags) {
                const removeButton = document.createElement("button");
                removeButton.type = "button";
                removeButton.textContent = "×";
                removeButton.title = "Remove tag";
                removeButton.addEventListener("click", async () => {
                    await savePaperTags(card, tags.filter((value) => value !== tag));
                });
                badge.appendChild(removeButton);
            }
            paperTagList.appendChild(badge);
        });
        paperTagAdd.style.display = canEditTags ? "flex" : "none";
        paperTagInput.value = "";
        paperTagSuggestions.innerHTML = "";
        feedTagSuggestions(card.dataset.logicalFeedId, card.dataset.paperId).forEach((tag) => {
            const option = document.createElement("option");
            option.value = tag;
            paperTagSuggestions.appendChild(option);
        });
    };

    const savePaperTags = async (card, tags) => {
        const normalized = Array.from(new Set(tags.map(normalizeTagInput).filter(Boolean))).sort((a, b) => a.localeCompare(b));
        const response = await fetch("/papers/" + card.dataset.paperId + "/tags", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: new URLSearchParams({ tags: normalized.join("\n") })
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to save tags");
        }
        setPaperTags(card, normalized);
        renderPaperTagEditor(card);
        applyLogicalFeedFilter();
    };

    const renderStateWheel = (container, options, activeIndex) => {
        container.innerHTML = "";
        options.forEach((entry, index) => {
            if (entry.type === "group") {
                const title = document.createElement("div");
                title.className = "meta";
                title.textContent = statusLabel(entry.label);
                container.appendChild(title);
                return;
            }
            const option = document.createElement("div");
            option.className = "state-wheel-option" + (index === activeIndex ? " active" : "");
            if (entry.shortcut) {
                const shortcut = document.createElement("span");
                shortcut.className = "state-wheel-shortcut";
                shortcut.textContent = entry.shortcut;
                option.appendChild(shortcut);
            }
            const label = document.createElement("span");
            label.textContent = statusLabel(entry.label);
            option.appendChild(label);
            container.appendChild(option);
        });
    };

    const renderStateTabs = (availableCounts) => {
        if (!statusTabsContainer) {
            return;
        }
        const logicalFeedId = logicalFeedFilter?.value || "";
        const groups = selectedWorkflowGroups();
        statusTabsContainer.innerHTML = "";
        if (statusSubtabsContainer) {
            statusSubtabsContainer.innerHTML = "";
            statusSubtabsContainer.classList.add("hidden");
        }
        if (mobileStateTabs) {
            mobileStateTabs.innerHTML = "";
        }

        groups.forEach((group) => {
            const topLabel = statusLabel(group.name) + " (" + (availableCounts[group.name] || 0) + ")";
            const activateTopTab = () => {
                activePrimaryTab = "state";
                activeStatusTab = group.name;
                activeChildStatus = null;
                stateSearchVisibleLimit = 10;
                applyLogicalFeedFilter();
            };
            const tab = document.createElement("button");
            tab.type = "button";
            tab.className = "reader-tab" + (activePrimaryTab === "state" && group.name === activeStatusTab ? " active" : "");
            tab.textContent = topLabel;
            tab.addEventListener("click", activateTopTab);
            statusTabsContainer.appendChild(tab);

            if (mobileStateTabs) {
                const mobileTab = document.createElement("button");
                mobileTab.type = "button";
                mobileTab.className = "secondary";
                mobileTab.textContent = topLabel;
                mobileTab.addEventListener("click", () => {
                    activateTopTab();
                    if (mobileStateMenu) {
                        mobileStateMenu.open = false;
                    }
                });
                mobileStateTabs.appendChild(mobileTab);
            }
        });

        const activeGroup = groups.find((group) => group.name === activeStatusTab) || null;
        const childChoices = activeGroup ? stateLeafChoices(activeGroup).filter((choice) => choice.value !== activeGroup.name) : [];
        if (activeGroup && childChoices.length && statusSubtabsContainer) {
            const childCounts = childStateCountsForGroup(logicalFeedId, activeGroup.name);
            statusSubtabsContainer.classList.remove("hidden");
            childChoices.forEach((choice) => {
                const label = statusLabel(choice.child || choice.value) + " (" + (childCounts[choice.value] || 0) + ")";
                const childTab = document.createElement("button");
                childTab.type = "button";
                childTab.className = "reader-tab" + (activePrimaryTab === "state" && activeChildStatus === choice.value ? " active" : "");
                childTab.textContent = label;
                childTab.addEventListener("click", () => {
                    activePrimaryTab = "state";
                    activeStatusTab = activeGroup.name;
                    activeChildStatus = activeChildStatus === choice.value ? null : choice.value;
                    stateSearchVisibleLimit = 10;
                    applyLogicalFeedFilter();
                });
                statusSubtabsContainer.appendChild(childTab);

                if (mobileStateTabs) {
                    const mobileChildTab = document.createElement("button");
                    mobileChildTab.type = "button";
                    mobileChildTab.className = "secondary";
                    mobileChildTab.textContent = statusLabel(activeGroup.name) + " / " + label;
                    mobileChildTab.addEventListener("click", () => {
                        activePrimaryTab = "state";
                        activeStatusTab = activeGroup.name;
                        activeChildStatus = choice.value;
                        stateSearchVisibleLimit = 10;
                        applyLogicalFeedFilter();
                        if (mobileStateMenu) {
                            mobileStateMenu.open = false;
                        }
                    });
                    mobileStateTabs.appendChild(mobileChildTab);
                }
            });
        }

        const tagsTab = document.createElement("button");
        tagsTab.type = "button";
        tagsTab.className = "reader-tab" + (activePrimaryTab === "tags" ? " active" : "");
        tagsTab.textContent = "Tags";
        tagsTab.addEventListener("click", () => {
            activePrimaryTab = "tags";
            stateSearchVisibleLimit = 10;
            applyLogicalFeedFilter();
        });
        statusTabsContainer.appendChild(tagsTab);
        if (mobileStateTabs) {
            const mobileTagsTab = document.createElement("button");
            mobileTagsTab.type = "button";
            mobileTagsTab.className = "secondary";
            mobileTagsTab.textContent = "Tags";
            mobileTagsTab.addEventListener("click", () => {
                activePrimaryTab = "tags";
                stateSearchVisibleLimit = 10;
                applyLogicalFeedFilter();
                if (mobileStateMenu) {
                    mobileStateMenu.open = false;
                }
            });
            mobileStateTabs.appendChild(mobileTagsTab);
        }
    };

    const hideStateWheels = () => {
        leftStateWheel.classList.remove("visible");
        rightStateWheel.classList.remove("visible");
        leftStateOptions.innerHTML = "";
        rightStateOptions.innerHTML = "";
    };

    const renderKeyboardStatePicker = () => {
        hideStateWheels();
        if (!keyboardStatePicker || !keyboardStatePicker.options.length) {
            return;
        }
        if (keyboardStatePicker.side === "left") {
            leftStateWheel.classList.add("visible");
            renderStateWheel(leftStateOptions, keyboardStatePicker.options, keyboardStatePicker.activeIndex);
            return;
        }
        rightStateWheel.classList.add("visible");
        renderStateWheel(rightStateOptions, keyboardStatePicker.options, keyboardStatePicker.activeIndex);
    };

    const clearKeyboardStatePicker = () => {
        keyboardStatePicker = null;
        hideStateWheels();
    };

    const canClassifyCard = (card) => {
        return Boolean(card)
            && canEdit
            && card.dataset.paperCanEditTags === "true";
    };

    const classificationDirectionalTargets = (card) => {
        if (!card) {
            return { left: [], right: [], other: [] };
        }
        const logicalFeedId = card.dataset.logicalFeedId;
        const currentStatus = normalizeStatus(card.dataset.paperStatus);
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const states = Array.isArray(config?.states) ? config.states : [];
        const currentIndex = states.findIndex((state) => state?.id === currentStatus);
        const uniqueTargets = Array.from(new Set(workflowTargetsFromState(logicalFeedId, currentStatus)));
        const result = { left: [], right: [], other: [] };
        uniqueTargets.forEach((target) => {
            const targetIndex = states.findIndex((state) => state?.id === target);
            if (currentIndex < 0 || targetIndex < 0 || targetIndex === currentIndex) {
                result.other.push(target);
            } else if (targetIndex < currentIndex) {
                result.left.push(target);
            } else {
                result.right.push(target);
            }
        });
        return result;
    };

    const classificationStateLabel = (card, stateId) => {
        return workflowStateById(card.dataset.logicalFeedId, stateId)?.label || statusLabel(stateId);
    };

    const classificationTargetCount = (directional) => {
        return directional.left.length + directional.right.length + directional.other.length;
    };

    const classificationSwipeChoices = (card, directional = classificationDirectionalTargets(card)) => {
        const twoEarlier = directional.left.length === 2
            && directional.right.length === 0
            && directional.other.length === 0;
        const twoLater = directional.right.length === 2
            && directional.left.length === 0
            && directional.other.length === 0;
        if (twoEarlier || twoLater) {
            const targets = twoEarlier ? directional.left : directional.right;
            return {
                neutral: true,
                left: targets[0],
                right: targets[1]
            };
        }
        return {
            neutral: false,
            left: directional.left.length === 1 ? directional.left[0] : null,
            right: directional.right.length === 1 ? directional.right[0] : null
        };
    };

    const setClassificationIcon = (container, iconId) => {
        container?.querySelector("use")?.setAttribute("href", "#" + iconId);
    };

    const DROP_ZONE_COLORS = ["#f08b7a", "#78b8dc", "#f0c86c", "#8dca93", "#b49ad7", "#e993bf"];
    const FULL_TURN = Math.PI * 2;
    const RADIAL_DROP_START_ANGLE = -3 * Math.PI / 4;

    const hideClassificationDropZones = () => {
        classificationDropZones?.classList.remove("visible");
        classificationDropZones?.setAttribute("aria-hidden", "true");
        if (classificationDropZones) {
            classificationDropZones.innerHTML = "";
        }
    };

    const polarPoint = (centerX, centerY, radius, angle) => ({
        x: centerX + Math.cos(angle) * radius,
        y: centerY + Math.sin(angle) * radius
    });

    const normalizedAngleDistance = (left, right) => {
        const difference = (left - right + Math.PI) % FULL_TURN;
        return Math.abs(difference < 0 ? difference + Math.PI : difference - Math.PI);
    };

    const classificationRadialTargets = (card) => {
        const directional = classificationDirectionalTargets(card);
        const sidesByTarget = new Map([
            ...directional.left.map((target) => [target, "left"]),
            ...directional.right.map((target) => [target, "right"]),
            ...directional.other.map((target) => [target, "bottom"])
        ]);
        const logicalFeedId = card?.dataset.logicalFeedId;
        const currentStatus = normalizeStatus(card?.dataset.paperStatus);
        return workflowTargetsFromState(logicalFeedId, currentStatus)
            .filter((target, index, targets) => targets.indexOf(target) === index)
            .map((target) => ({ target, side: sidesByTarget.get(target) || "bottom" }));
    };

    const showClassificationDropZones = (card) => {
        hideClassificationDropZones();
        const targets = classificationRadialTargets(card);
        if (targets.length <= 2 || !classificationDropZones) {
            return { targets: [], deadZoneRadius: CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS };
        }
        const viewportWidth = window.innerWidth;
        const viewportHeight = window.innerHeight;
        // The card can be vertically offset by the queue header. Keep the visual wheel in the
        // actual viewport centre so every label remains reachable and visible.
        const centerX = viewportWidth / 2;
        const centerY = viewportHeight / 2;
        const smallestViewportSide = Math.min(viewportWidth, viewportHeight);
        const deadZoneRadius = Math.max(72, Math.min(
            CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS,
            Math.floor(smallestViewportSide / 2) - 118
        ));
        const mapRadius = Math.max(
            Math.hypot(centerX, centerY),
            Math.hypot(viewportWidth - centerX, centerY),
            Math.hypot(centerX, viewportHeight - centerY),
            Math.hypot(viewportWidth - centerX, viewportHeight - centerY)
        ) + 2;
        const labelClearance = 98;
        const maximumLabelRadius = Math.max(deadZoneRadius + 24,
            Math.min(viewportWidth / 2 - labelClearance, viewportHeight / 2 - labelClearance));
        const labelRadius = Math.min(maximumLabelRadius,
            Math.max(deadZoneRadius + 40, Math.min(smallestViewportSide * 0.36, 330)));
        const sectorAngle = FULL_TURN / targets.length;
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.classList.add("classification-drop-zone-map");
        svg.setAttribute("viewBox", "0 0 " + viewportWidth + " " + viewportHeight);
        svg.setAttribute("aria-hidden", "true");

        const radialTargets = targets.map(({ target, side }, index) => {
            const angle = RADIAL_DROP_START_ANGLE + index * sectorAngle;
            const innerStart = polarPoint(centerX, centerY, deadZoneRadius, angle - sectorAngle / 2);
            const innerEnd = polarPoint(centerX, centerY, deadZoneRadius, angle + sectorAngle / 2);
            const start = polarPoint(centerX, centerY, mapRadius, angle - sectorAngle / 2);
            const end = polarPoint(centerX, centerY, mapRadius, angle + sectorAngle / 2);
            const color = DROP_ZONE_COLORS[index % DROP_ZONE_COLORS.length];
            const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
            path.classList.add("classification-drop-zone-sector");
            path.dataset.targetState = target;
            path.setAttribute("d", "M " + innerStart.x + " " + innerStart.y
                + " L " + start.x + " " + start.y
                + " A " + mapRadius + " " + mapRadius + " 0 0 1 " + end.x + " " + end.y
                + " L " + innerEnd.x + " " + innerEnd.y
                + " A " + deadZoneRadius + " " + deadZoneRadius + " 0 0 0 " + innerStart.x + " " + innerStart.y + " Z");
            path.style.setProperty("--classification-drop-zone-color", color);
            svg.appendChild(path);
            return { target, side, angle, color };
        });
        classificationDropZones.appendChild(svg);

        const center = document.createElement("div");
        center.className = "classification-drop-zone-center";
        center.style.left = centerX + "px";
        center.style.top = centerY + "px";
        classificationDropZones.appendChild(center);

        radialTargets.forEach(({ target, side, angle, color }) => {
            const point = polarPoint(centerX, centerY, labelRadius, angle);
            const zone = document.createElement("div");
            zone.className = "classification-drop-zone";
            zone.dataset.side = side;
            zone.dataset.targetState = target;
            zone.style.left = point.x + "px";
            zone.style.top = point.y + "px";
            zone.style.setProperty("--classification-drop-zone-color", color);
            zone.textContent = classificationStateLabel(card, target);
            classificationDropZones.appendChild(zone);
        });
        classificationDropZones.classList.add("visible");
        classificationDropZones.setAttribute("aria-hidden", "false");
        return { targets: radialTargets, deadZoneRadius };
    };

    const classificationDropTargetForDrag = (dragState, deltaX, deltaY) => {
        const radius = Math.hypot(deltaX, deltaY);
        if (!dragState.radialTargets?.length) {
            classificationDragTarget?.classList.remove("visible");
            return null;
        }
        const angle = Math.atan2(deltaY, deltaX);
        const match = dragState.radialTargets.reduce((closest, candidate) => {
            return !closest || normalizedAngleDistance(angle, candidate.angle) < normalizedAngleDistance(angle, closest.angle)
                ? candidate
                : closest;
        }, null);
        const deadZoneRadius = dragState.radialDeadZoneRadius || CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS;
        if (radius < deadZoneRadius) {
            classificationDragTarget?.classList.remove("visible");
            classificationDropZones?.querySelectorAll(".classification-drop-zone, .classification-drop-zone-sector")
                .forEach((element) => element.classList.remove("active"));
            return null;
        }
        if (classificationDragTarget && classificationDragTargetLabel && match) {
            classificationDragTargetLabel.textContent = classificationStateLabel(dragState.card, match.target);
            classificationDragTarget.style.color = match.color;
            classificationDragTarget.classList.add("visible");
        }
        classificationDropZones?.querySelectorAll(".classification-drop-zone, .classification-drop-zone-sector")
            .forEach((element) => element.classList.toggle("active", element.dataset.targetState === match?.target));
        return match;
    };

    const hideClassificationMobileStatePicker = () => {
        document.body.classList.remove("mobile-state-picker-open");
        classificationMobileStatePicker?.classList.remove("visible");
        classificationMobileStatePicker?.setAttribute("aria-hidden", "true");
        if (classificationMobileStatePicker) {
            classificationMobileStatePicker.innerHTML = "";
        }
    };

    const mobileStatePickerRowAt = (clientX, clientY) => {
        return Array.from(classificationMobileStatePicker?.querySelectorAll("[data-mobile-state-option]") || [])
            .find((row) => {
                const bounds = row.getBoundingClientRect();
                return clientX >= bounds.left && clientX <= bounds.right
                    && clientY >= bounds.top && clientY <= bounds.bottom;
            }) || null;
    };

    const updateClassificationMobileStatePicker = (longPress, clientX, clientY) => {
        const row = mobileStatePickerRowAt(clientX, clientY);
        classificationMobileStatePicker?.querySelectorAll("[data-mobile-state-option]")
            .forEach((candidate) => candidate.classList.toggle("active", candidate === row));
        if (!row || row.dataset.mobileStateCancel === "true") {
            longPress.activeTransition = null;
            return;
        }
        longPress.activeTransition = longPress.transitions.find((transition) => transition.target === row.dataset.mobileStateTarget) || null;
    };

    const showClassificationMobileStatePicker = (longPress) => {
        if (!classificationMobileStatePicker || !longPress.transitions.length) {
            return false;
        }
        classificationMobileStatePicker.innerHTML = "";
        const stack = document.createElement("div");
        stack.className = "classification-mobile-state-stack";
        const viewportHeight = window.visualViewport?.height || window.innerHeight;
        const optionCount = longPress.transitions.length + 1; // Include the cancel option.
        const availableOptionHeight = Math.floor((viewportHeight - 82 - optionCount * 8) / optionCount);
        const optionHeight = Math.max(34, Math.min(50, availableOptionHeight));
        stack.style.setProperty("--classification-mobile-state-option-height", optionHeight + "px");
        stack.classList.toggle("compact", optionHeight < 44);
        const instruction = document.createElement("p");
        instruction.className = "classification-mobile-state-instruction";
        instruction.textContent = "Move to a state, then lift your finger";
        stack.appendChild(instruction);
        longPress.transitions.forEach((transition, index) => {
            const option = document.createElement("div");
            option.className = "classification-mobile-state-option";
            option.dataset.mobileStateOption = "true";
            option.dataset.mobileStateTarget = transition.target;
            option.style.setProperty("--classification-drop-zone-color", DROP_ZONE_COLORS[index % DROP_ZONE_COLORS.length]);
            option.textContent = classificationStateLabel(longPress.card, transition.target);
            stack.appendChild(option);
        });
        const cancel = document.createElement("div");
        cancel.className = "classification-mobile-state-option cancel";
        cancel.dataset.mobileStateOption = "true";
        cancel.dataset.mobileStateCancel = "true";
        cancel.style.setProperty("--classification-drop-zone-color", "var(--muted)");
        cancel.textContent = "Cancel";
        stack.appendChild(cancel);
        classificationMobileStatePicker.appendChild(stack);
        document.body.classList.add("mobile-state-picker-open");
        classificationMobileStatePicker.classList.add("visible");
        classificationMobileStatePicker.setAttribute("aria-hidden", "false");
        return true;
    };

    const beginClassificationMobileLongPress = (event, card) => {
        const longPress = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            lastX: event.clientX,
            lastY: event.clientY,
            card,
            activated: false,
            movedAfterActivation: false,
            activeTransition: null,
            transitions: classificationRadialTargets(card),
            timer: null
        };
        if (!longPress.transitions.length) {
            return;
        }
        longPress.timer = window.setTimeout(() => {
            if (classificationMobileLongPress !== longPress) {
                return;
            }
            longPress.timer = null;
            if (!showClassificationMobileStatePicker(longPress)) {
                classificationMobileLongPress = null;
                return;
            }
            longPress.activated = true;
            classificationCard?.setPointerCapture?.(longPress.pointerId);
        }, MOBILE_STATE_LONG_PRESS_MS);
        classificationMobileLongPress = longPress;
    };

    const updateClassificationMobileLongPress = (event) => {
        const longPress = classificationMobileLongPress;
        if (!longPress || event.pointerId !== longPress.pointerId) {
            return false;
        }
        longPress.lastX = event.clientX;
        longPress.lastY = event.clientY;
        const moved = Math.hypot(event.clientX - longPress.startX, event.clientY - longPress.startY);
        if (!longPress.activated) {
            if (moved > MOBILE_STATE_LONG_PRESS_MOVE_TOLERANCE) {
                window.clearTimeout(longPress.timer);
                classificationMobileLongPress = null;
            }
            return true;
        }
        event.preventDefault();
        if (moved < MOBILE_STATE_LONG_PRESS_MOVE_TOLERANCE) {
            return true;
        }
        longPress.movedAfterActivation = true;
        updateClassificationMobileStatePicker(longPress, event.clientX, event.clientY);
        return true;
    };

    const finishClassificationMobileLongPress = async (event, cancelled = false) => {
        const longPress = classificationMobileLongPress;
        if (!longPress || event.pointerId !== longPress.pointerId) {
            return false;
        }
        classificationMobileLongPress = null;
        window.clearTimeout(longPress.timer);
        if (!longPress.activated) {
            return true;
        }
        event.preventDefault?.();
        if (classificationCard?.hasPointerCapture?.(event.pointerId)) {
            classificationCard.releasePointerCapture(event.pointerId);
        }
        const transition = !cancelled && longPress.movedAfterActivation ? longPress.activeTransition : null;
        hideClassificationMobileStatePicker();
        if (transition) {
            await performClassificationTransition(longPress.card, transition.target, transition.side, { preserveDragVisual: true });
        }
        return true;
    };

    const classificationQueueCards = () => visiblePaperCards().filter(canClassifyCard);

    const nextClassificationCard = (card) => {
        const cards = classificationQueueCards();
        const currentIndex = cards.findIndex((item) => item.dataset.paperId === card?.dataset.paperId);
        if (currentIndex < 0 || cards.length < 2) {
            return cards.find((item) => item.dataset.paperId !== card?.dataset.paperId) || null;
        }
        return cards[currentIndex + 1] || cards.find((item) => item.dataset.paperId !== card.dataset.paperId) || null;
    };

    const renderClassificationNextCard = (card) => {
        if (!classificationNextCard) {
            return;
        }
        const nextCard = nextClassificationCard(card);
        classificationNextCard.classList.toggle("hidden", !nextCard);
        classificationNextTitle.textContent = nextCard?.dataset.paperTitle || "";
        classificationNextMeta.textContent = nextCard
            ? (nextCard.dataset.paperAuthors || "Unknown authors")
            : "";
    };

    const setClassificationNotesExpanded = (expanded) => {
        classificationNotes?.classList.toggle("expanded", expanded);
        classificationNotesToggle?.setAttribute("aria-expanded", expanded ? "true" : "false");
        if (expanded) {
            window.setTimeout(() => classificationNotesEditor?.focus(), 0);
        }
    };

    const updateClassificationNotesIndicator = () => {
        if (!classificationNotesIndicator || !classificationNotesEditor) {
            return;
        }
        classificationNotesIndicator.textContent = classificationNotesEditor.value.trim()
            ? "Notes added"
            : "No notes";
    };

    const renderClassificationNotes = (card) => {
        if (!classificationNotesEditor) {
            return;
        }
        const paperId = card.dataset.paperId;
        const value = classificationNoteDrafts.has(paperId)
            ? classificationNoteDrafts.get(paperId)
            : (card.dataset.paperNotes || "");
        classificationNotesEditor.value = value;
        classificationNotesStatus.textContent = "Saved";
        updateClassificationNotesIndicator();
        setClassificationNotesExpanded(false);
    };

    const saveClassificationNotes = async (card = selectedPaperCard()) => {
        if (!canEdit || !card || !classificationNotesEditor) {
            return;
        }
        window.clearTimeout(classificationNotesSaveTimer);
        classificationNotesSaveTimer = null;
        const paperId = card.dataset.paperId;
        const notes = classificationNoteDrafts.has(paperId)
            ? classificationNoteDrafts.get(paperId)
            : classificationNotesEditor.value;
        if (notes === (card.dataset.paperNotes || "")) {
            classificationNotesStatus.textContent = "Saved";
            return;
        }
        classificationNotesStatus.textContent = "Saving...";
        const save = fetch("/papers/" + paperId + "/notes", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: new URLSearchParams({ notes })
        }).then(async (response) => {
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Failed to save notes");
            }
            card.dataset.paperNotes = notes;
            const currentCard = findCardByPaperId(paperId);
            if (currentCard) {
                currentCard.dataset.paperNotes = notes;
            }
            const record = paperRecordById(paperId);
            if (record) {
                record.paperNotes = notes;
            }
            classificationNoteDrafts.delete(paperId);
            classificationNotesStatus.textContent = "Saved";
        }).catch((error) => {
            classificationNotesStatus.textContent = error.message || "Failed to save notes";
            throw error;
        }).finally(() => {
            if (classificationNotesSaving === save) {
                classificationNotesSaving = null;
            }
        });
        classificationNotesSaving = save;
        await save;
    };

    const fitClassificationTypography = () => {
        if (!classificationModeActive || classificationSurface.classList.contains("empty")) {
            return;
        }
        classificationSurface.style.setProperty("--classification-title-size", "");
        classificationSurface.style.setProperty("--classification-abstract-size", "");
        classificationCard.scrollTop = 0;
    };

    const renderClassificationPaper = (card) => {
        if (!card) {
            return;
        }
        ensurePaperDetail(card);
        classificationEmpty?.classList.remove("celebrating");
        hideClassificationDropZones();
        hideClassificationMobileStatePicker();
        hideStateWheels();
        classificationCard.classList.remove(
            "dragging", "dragging-left", "dragging-right", "exiting-left", "exiting-right", "entering", "entering-active"
        );
        classificationCard.style.setProperty("--classification-drag-x", "0px");
        classificationCard.style.setProperty("--classification-drag-y", "0px");
        classificationCard.style.setProperty("--classification-drag-rotation", "0deg");
        classificationCard.style.setProperty("--classification-drag-progress", "0");
        classificationSurface.classList.remove("empty");
        const multiTarget = classificationTargetCount(classificationDirectionalTargets(card)) > 2;
        classificationCard.classList.toggle("multi-target-drag", multiTarget);
        classificationSurface.classList.toggle("multi-target-classification", multiTarget);
        classificationDragTarget?.classList.remove("visible");
        if (classificationDragTarget && classificationDragTargetLabel) {
            classificationDragTargetLabel.textContent = "";
            classificationDragTarget.style.removeProperty("color");
        }
        updateClassificationQueueProgress(card);
        classificationKicker.textContent = statusLabel(normalizeStatus(card.dataset.paperStatus))
            + (card.dataset.paperRecordType === "GRAY_LITERATURE" ? " · Gray literature" : "");
        classificationTitle.textContent = card.dataset.paperTitle || "Untitled paper";
        classificationMeta.textContent = "";
        [
            card.dataset.paperAuthors || "Unknown authors",
            (card.dataset.paperVenue || "Unknown venue") + " · " + (card.dataset.paperPublishedOn || "unknown"),
            "From feed: " + (card.dataset.paperFeedName || "Unknown feed")
        ].forEach((line, index) => {
            if (index > 0) {
                classificationMeta.appendChild(document.createElement("br"));
            }
            classificationMeta.appendChild(document.createTextNode(line));
        });
        classificationTags.innerHTML = "";
        (card.dataset.paperTags || "")
            .split("|")
            .map((tag) => tag.trim())
            .filter(Boolean)
            .forEach((tag) => {
                const badge = document.createElement("span");
                badge.className = "classification-tag";
                badge.textContent = tag;
                classificationTags.appendChild(badge);
            });
        classificationAbstract.innerHTML = "";
        const summary = (card.dataset.paperSummary || "").trim();
        classificationAbstract.classList.toggle("has-summary", Boolean(summary));
        if (!summary) {
            const missing = document.createElement("p");
            missing.className = "meta";
            missing.textContent = "No abstract available for this paper.";
            classificationAbstract.appendChild(missing);
        } else {
            summary.split(/\n+/).forEach((paragraph) => {
                const element = document.createElement("p");
                element.textContent = paragraph;
                classificationAbstract.appendChild(element);
            });
        }
        renderClassificationLinks(card);
        renderClassificationNotes(card);
        renderClassificationActions(card);
        renderClassificationNextCard(card);
        fitClassificationTypography();
    };

    const updateClassificationQueueProgress = () => {
        if (!classificationQueueMode || !classificationProgress || !classificationProgressLabel) {
            return;
        }
        if (classificationQueueInitialTotal == null) {
            classificationQueueInitialTotal = paperRecords.length || classificationQueueCards().length;
        }
        const completed = Math.min(classificationQueueInitialTotal, classificationQueueCompletedPaperIds.size);
        const countLabel = completed + " / " + classificationQueueInitialTotal + " papers";
        const currentCard = selectedPaperCard();
        const stateId = classificationQueueStates.length === 1
            ? classificationQueueStates[0]
            : normalizeStatus(currentCard?.dataset.paperStatus || paperRecords[0]?.paperStatus || "");
        const stateLabel = stateId
            ? currentCard
                ? classificationStateLabel(currentCard, stateId)
                : statusLabel(stateId)
            : "Papers";
        classificationQueueTitle.textContent = stateLabel + " papers queue";
        classificationProgress.max = Math.max(1, classificationQueueInitialTotal);
        classificationProgress.value = completed;
        classificationProgress.textContent = countLabel;
        classificationProgress.setAttribute("aria-label", stateLabel + " papers queue progress");
        classificationProgress.setAttribute("aria-valuetext", countLabel + " classified");
        classificationProgressLabel.textContent = countLabel;
        if (filteredPaperRecords().length <= 5 && browserNextCursor && !browserRequestInFlight) {
            loadNextBrowserPage().catch(() => {});
        }
    };

    const appendClassificationLink = (label, href) => {
        if (!classificationLinks || !href) {
            return;
        }
        const link = document.createElement("a");
        link.href = href;
        link.target = "_blank";
        link.rel = "noreferrer";
        link.textContent = label;
        classificationLinks.appendChild(link);
    };

    const renderClassificationLinks = (card) => {
        if (!classificationLinks) {
            return;
        }
        classificationLinks.innerHTML = "";
        appendClassificationLink("Source", card.dataset.paperSourceLink || "");
        appendClassificationLink("Open access", card.dataset.paperOpenAccessLink || "");
        appendClassificationLink("PDF", card.dataset.pdfUrl || "");
    };

    const renderClassificationActions = (card) => {
        if (!classificationActions || !classificationFooter) {
            return;
        }
        classificationActions.innerHTML = "";
        const currentStatus = normalizeStatus(card.dataset.paperStatus);
        const directional = classificationDirectionalTargets(card);
        const targetCount = classificationTargetCount(directional);
        const radialOnly = targetCount > 2;
        const swipeChoices = classificationSwipeChoices(card, directional);
        classificationActionDock?.classList.toggle("radial-only", radialOnly);
        const leftLabel = swipeChoices.left
            ? classificationStateLabel(card, swipeChoices.left)
            : directional.left.length > 1 ? directional.left.length + " earlier states" : "No earlier state";
        const rightLabel = swipeChoices.right
            ? classificationStateLabel(card, swipeChoices.right)
            : directional.right.length > 1 ? directional.right.length + " later states" : "No later state";
        classificationLeftLabel.textContent = leftLabel;
        classificationRightLabel.textContent = rightLabel;
        classificationLeftStampLabel.textContent = swipeChoices.left ? leftLabel : "Choose a state";
        classificationRightStampLabel.textContent = swipeChoices.right ? rightLabel : "Choose a state";
        classificationLeftAction.disabled = !swipeChoices.left;
        classificationRightAction.disabled = !swipeChoices.right;
        classificationLeftAction.classList.toggle("neutral", swipeChoices.neutral);
        classificationRightAction.classList.toggle("neutral", swipeChoices.neutral);
        classificationLeftStamp?.classList.toggle("neutral", swipeChoices.neutral);
        classificationRightStamp?.classList.toggle("neutral", swipeChoices.neutral);
        setClassificationIcon(classificationLeftAction, swipeChoices.neutral ? "lucide-arrow-left" : "lucide-x");
        setClassificationIcon(classificationRightAction, swipeChoices.neutral ? "lucide-arrow-right" : "lucide-heart");
        setClassificationIcon(classificationLeftStamp, swipeChoices.neutral ? "lucide-arrow-left" : "lucide-x");
        setClassificationIcon(classificationRightStamp, swipeChoices.neutral ? "lucide-arrow-right" : "lucide-heart");
        classificationLeftAction.setAttribute("aria-label", swipeChoices.left
            ? "Move paper to " + leftLabel
            : "No left swipe destination available");
        classificationRightAction.setAttribute("aria-label", swipeChoices.right
            ? "Move paper to " + rightLabel
            : "No right swipe destination available");
        classificationLeftAction.title = swipeChoices.left
            ? "Move paper to " + leftLabel
            : "No left swipe destination available";
        classificationRightAction.title = swipeChoices.right
            ? "Move paper to " + rightLabel
            : "No right swipe destination available";

        const explicitTargets = swipeChoices.neutral ? [] : [
            ...(directional.left.length > 1 ? directional.left : []),
            ...directional.other,
            ...(directional.right.length > 1 ? directional.right : [])
        ];
        explicitTargets.forEach((target, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = index === 0 ? "primary" : "";
            button.textContent = classificationStateLabel(card, target);
            button.addEventListener("click", async () => {
                const targetIndex = workflowConfigForLogicalFeed(card.dataset.logicalFeedId)?.states
                    ?.findIndex((state) => state?.id === target) ?? -1;
                const currentIndex = workflowConfigForLogicalFeed(card.dataset.logicalFeedId)?.states
                    ?.findIndex((state) => state?.id === currentStatus) ?? -1;
                await performClassificationTransition(card, target, targetIndex < currentIndex ? "left" : "right");
            });
            classificationActions.appendChild(button);
        });
        classificationActions.classList.toggle("branching", explicitTargets.length > 0);
        classificationFooter.textContent = targetCount
            ? isMobileViewport()
                ? "Press and hold the card, move to a state, then lift your finger."
                : explicitTargets.length
                ? "Choose a labeled state, or swipe where one destination is available."
                : swipeChoices.neutral
                    ? "Drag the card or use either labeled arrow."
                    : "Drag the card or use the X and heart controls."
            : "This state has no configured outgoing transitions. Use the normal reader to inspect this paper.";
    };

    const waitForClassificationAnimation = (duration = CLASSIFICATION_HANDOFF_MS) => {
        const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        return new Promise((resolve) => window.setTimeout(resolve, reducedMotion ? 20 : duration));
    };

    const resetClassificationDragVisual = () => {
        if (!classificationCard) {
            return;
        }
        hideClassificationDropZones();
        hideClassificationMobileStatePicker();
        classificationDragTarget?.classList.remove("visible");
        classificationCard.classList.remove("dragging", "dragging-left", "dragging-right");
        classificationCard.style.setProperty("--classification-drag-x", "0px");
        classificationCard.style.setProperty("--classification-drag-y", "0px");
        classificationCard.style.setProperty("--classification-drag-rotation", "0deg");
        classificationCard.style.setProperty("--classification-drag-progress", "0");
    };

    const revealClassificationChoices = () => {
        if (!classificationActions?.classList.contains("branching")) {
            return;
        }
        classificationActions.classList.remove("attention");
        void classificationActions.offsetWidth;
        classificationActions.classList.add("attention");
        classificationActions.querySelector("button")?.focus({ preventScroll: true });
    };

    const performClassificationTransition = async (card, nextStatus, side, options = {}) => {
        if (classificationBusy || !card || !nextStatus) {
            return false;
        }
        const radialClassification = classificationTargetCount(classificationDirectionalTargets(card)) > 2;
        classificationBusy = true;
        classificationSurface.classList.add("busy");
        classificationFooter.textContent = "Saving classification...";
        hideClassificationDropZones();
        if (options.preserveDragVisual === true) {
            classificationCard.classList.remove("dragging", "dragging-left", "dragging-right");
        } else {
            resetClassificationDragVisual();
        }
        try {
            await saveClassificationNotes(card);
            const applied = await applyPaperStatusTransition(card, nextStatus, normalizeStatus(card.dataset.paperStatus), {
                advanceFocus: false,
                backgroundPersistence: true,
                skipStateFeedback: radialClassification
            });
            if (!applied) {
                resetClassificationDragVisual();
                renderClassificationActions(card);
                return false;
            }
            if (classificationQueueMode) {
                classificationQueueCompletedPaperIds.add(String(card.dataset.paperId));
                updateClassificationQueueProgress();
            }
            if (radialClassification) {
                showMadProfessorFeedback("congratulations");
            }

            const nextCard = classificationQueueCards()[0] || null;
            classificationCard.classList.add(side === "left" ? "exiting-left" : "exiting-right");
            await waitForClassificationAnimation();

            if (!nextCard || !selectPaperForClassification(nextCard)) {
                showClassificationEmpty({
                    celebrate: classificationQueueMode,
                    feedName: card.dataset.logicalFeedName
                });
                return true;
            }

            classificationCard.classList.add("entering");
            void classificationCard.offsetWidth;
            classificationCard.classList.add("entering-active");
            await waitForClassificationAnimation();
            classificationCard.classList.remove("entering", "entering-active");
            if (keyboardShortcutShiftHeld) {
                startKeyboardShortcutPicker();
            }
            return true;
        } catch (error) {
            const restoredCard = findCardByPaperId(card.dataset.paperId) || card;
            if (classificationModeActive && canClassifyCard(restoredCard)) {
                selectPaperForClassification(restoredCard);
            }
            showKeyboardStatusError(error.message || "Failed to update paper status");
            return false;
        } finally {
            classificationBusy = false;
            classificationSurface.classList.remove("busy");
        }
    };

    const deferClassificationPaper = async () => {
        if (!classificationModeActive || !classificationQueueMode || classificationBusy || isMobileViewport()) {
            return;
        }
        const card = selectedPaperCard();
        const queueCards = classificationQueueCards();
        if (!card || queueCards.length < 2) {
            return;
        }
        const recordIndex = paperRecords.findIndex((record) => String(record.id) === String(card.dataset.paperId));
        if (recordIndex < 0) {
            return;
        }
        classificationBusy = true;
        classificationSurface.classList.add("busy");
        try {
            paperRecords.push(paperRecords.splice(recordIndex, 1)[0]);
            applyLogicalFeedFilter();
            const nextCard = classificationQueueCards()[0] || null;
            classificationCard.classList.add("exiting-right");
            await waitForClassificationAnimation();
            if (nextCard && selectPaperForClassification(nextCard)) {
                classificationCard.classList.add("entering");
                void classificationCard.offsetWidth;
                classificationCard.classList.add("entering-active");
                await waitForClassificationAnimation();
                classificationCard.classList.remove("entering", "entering-active");
            }
        } finally {
            classificationBusy = false;
            classificationSurface.classList.remove("busy");
        }
    };

    const handleClassificationDirection = async (side) => {
        if (classificationBusy) {
            return;
        }
        const card = selectedPaperCard();
        const target = classificationSwipeChoices(card)[side];
        if (!target) {
            revealClassificationChoices();
            return;
        }
        await performClassificationTransition(card, target, side);
    };

    const openClassificationPaperViewer = async () => {
        const card = selectedPaperCard();
        if (!card || classificationBusy) {
            return;
        }
        try {
            await saveClassificationNotes(card);
        } catch (error) {
            showKeyboardStatusError(error.message || "Failed to save notes");
            return;
        }
        classificationViewerReturnPaperId = card.dataset.paperId;
        classificationModeActive = false;
        classificationView.setAttribute("aria-hidden", "true");
        document.body.classList.remove("classification-mode");
        document.body.classList.add("classification-viewer-open");
        clearKeyboardStatePicker();
        clearKeyboardShortcutPicker();
        openPaperCard(card);
        classificationViewerReturn?.focus({ preventScroll: true });
    };

    const returnToClassification = () => {
        const paperId = classificationViewerReturnPaperId;
        classificationViewerReturnPaperId = null;
        document.body.classList.remove("classification-viewer-open");
        const card = findCardByPaperId(paperId);
        if (!card || !canClassifyCard(card)) {
            if (!enterClassificationMode({ temporary: classificationModeTemporary })) {
                showEmptyClassificationMode();
            }
            return;
        }
        classificationModeActive = true;
        document.body.classList.add("classification-mode");
        classificationView.setAttribute("aria-hidden", "false");
        selectPaperForClassification(card);
        classificationInfoAction?.focus({ preventScroll: true });
    };

    const selectPaperForClassification = (card) => {
        if (!canClassifyCard(card)) {
            return false;
        }
        currentRenderToken += 1;
        ttsPlayer.pause();
        allPaperCards().forEach((item) => item.classList.remove("active"));
        card.classList.add("active");
        selectedPaperId = card.dataset.paperId;
        selectedPdfUrl = null;
        selectedDownloadUrl = card.dataset.pdfUrl || "";
        selectedContentKind = "card";
        markPaperViewed(selectedPaperId, card);
        renderClassificationPaper(card);
        return true;
    };

    const showClassificationEmpty = (options = {}) => {
        const celebrate = options.celebrate === true && classificationQueueMode;
        const feedName = options.feedName || logicalFeedFilter?.selectedOptions?.[0]?.dataset?.feedName || "This paper feed";
        selectedPaperId = null;
        classificationDragState = null;
        resetClassificationDragVisual();
        allPaperCards().forEach((item) => item.classList.remove("active"));
        classificationSurface.classList.add("empty");
        classificationEmpty?.classList.toggle("celebrating", celebrate);
        classificationNextCard?.classList.add("hidden");
        updateClassificationQueueProgress();
        if (classificationActions) {
            classificationActions.innerHTML = "";
        }
        if (classificationLinks) {
            classificationLinks.innerHTML = "";
        }
        if (classificationFooter) {
            classificationFooter.textContent = "";
        }
        if (classificationEmptyTitle) {
            classificationEmptyTitle.textContent = celebrate
                ? "You did it!"
                : classificationQueueMode
                ? "No papers waiting for classification"
                : "No papers remaining";
        }
        if (classificationEmptyMessage) {
            classificationEmptyMessage.textContent = celebrate
                ? feedName + " is now empty!"
                : classificationQueueMode
                ? "All RSS papers in this paper feed have been classified."
                : "Release Shift to return to the paper feed.";
        }
        hideStateWheels();
    };

    const showEmptyClassificationMode = () => {
        classificationModeActive = true;
        classificationModeTemporary = false;
        document.body.classList.add("classification-mode");
        classificationView.setAttribute("aria-hidden", "false");
        showClassificationEmpty();
    };

    const enterClassificationMode = (options = {}) => {
        let card = selectedPaperCard();
        if (!canClassifyCard(card)) {
            card = visiblePaperCards().find(canClassifyCard) || null;
        }
        if (!card) {
            return false;
        }
        if (!classificationModeActive || options.temporary !== true) {
            classificationModeTemporary = options.temporary === true;
        }
        classificationModeActive = true;
        document.body.classList.add("classification-mode");
        classificationView.setAttribute("aria-hidden", "false");
        selectPaperForClassification(card);
        return true;
    };

    const exitClassificationMode = (options = {}) => {
        if (!classificationModeActive || (options.force !== true && !classificationModeTemporary)) {
            return;
        }
        classificationModeActive = false;
        classificationModeTemporary = false;
        classificationDragState = null;
        resetClassificationDragVisual();
        classificationViewerReturnPaperId = null;
        document.body.classList.remove("classification-mode");
        document.body.classList.remove("classification-viewer-open");
        classificationView.setAttribute("aria-hidden", "true");
        classificationSurface.classList.remove("empty");
        const card = selectedPaperCard();
        if (card) {
            focusPaperCard(card);
            return;
        }
        selectedContentKind = null;
        applyLayoutState();
    };

    const numberStateWheelOptions = (options, firstShortcut) => {
        let shortcut = firstShortcut;
        const numberedOptions = options.map((entry) => {
            if (entry.type !== "state" || shortcut > 9) {
                return { ...entry };
            }
            return { ...entry, shortcut: String(shortcut++) };
        });
        return { options: numberedOptions, nextShortcut: shortcut };
    };

    const renderKeyboardShortcutPicker = () => {
        hideStateWheels();
        if (!keyboardShortcutPicker) {
            return;
        }
        if (classificationModeActive) {
            if (!classificationKeyboardBar) {
                return;
            }
            classificationKeyboardBar.innerHTML = "";
            keyboardShortcutPicker.transitions.forEach((transition) => {
                const choice = document.createElement("span");
                choice.className = "classification-keyboard-choice";
                const shortcut = document.createElement("kbd");
                shortcut.textContent = transition.shortcut;
                choice.appendChild(shortcut);
                choice.appendChild(document.createTextNode(classificationStateLabel(keyboardShortcutPicker.card, transition.value)));
                classificationKeyboardBar.appendChild(choice);
            });
            classificationKeyboardBar.classList.add("visible");
            classificationKeyboardBar.setAttribute("aria-hidden", "false");
            return;
        }
        if (keyboardShortcutPicker.leftOptions.length) {
            leftStateWheel.classList.add("visible");
            renderStateWheel(leftStateOptions, keyboardShortcutPicker.leftOptions, -1);
        }
        if (keyboardShortcutPicker.rightOptions.length) {
            rightStateWheel.classList.add("visible");
            renderStateWheel(rightStateOptions, keyboardShortcutPicker.rightOptions, -1);
        }
    };

    const clearKeyboardShortcutPicker = () => {
        keyboardShortcutPicker = null;
        hideStateWheels();
        classificationKeyboardBar?.classList.remove("visible");
        classificationKeyboardBar?.setAttribute("aria-hidden", "true");
        if (classificationKeyboardBar) {
            classificationKeyboardBar.innerHTML = "";
        }
    };

    const startKeyboardShortcutPicker = () => {
        const card = selectedPaperCard();
        if (!card || (classificationModeActive && !canClassifyCard(card))) {
            return;
        }
        if (classificationModeActive && !keyboardShortcutShiftHeld) {
            return;
        }
        const currentStatus = normalizeStatus(card.dataset.paperStatus);
        if (classificationModeActive) {
            const transitions = classificationRadialTargets(card)
                .slice(0, 9)
                .map((entry, index) => ({ ...entry, shortcut: String(index + 1), value: entry.target }));
            if (!transitions.length) {
                return;
            }
            clearKeyboardStatePicker();
            keyboardShortcutPicker = { card, currentStatus, transitions, leftOptions: [], rightOptions: [] };
            renderKeyboardShortcutPicker();
            return;
        }
        const outgoingStates = workflowTargetsFromState(card.dataset.logicalFeedId, currentStatus);
        const outgoing = numberStateWheelOptions(
            wheelOptionsForReachableStates(card.dataset.logicalFeedId, outgoingStates),
            1
        );
        const transitions = outgoing.options
            .filter((entry) => entry.type === "state" && entry.shortcut);
        if (!transitions.length) {
            return;
        }
        clearKeyboardStatePicker();
        keyboardShortcutPicker = {
            card,
            currentStatus,
            rightOptions: outgoing.options,
            leftOptions: [],
            transitions
        };
        renderKeyboardShortcutPicker();
    };

    const shortcutDigitFromEvent = (event) => {
        const match = /^(?:Digit|Numpad)([1-9])$/.exec(event.code);
        return match ? match[1] : null;
    };

    const clearKeyboardStatusToast = () => {
        if (keyboardStatusUndo?.timer) {
            window.clearTimeout(keyboardStatusUndo.timer);
        }
        keyboardStatusUndo = null;
        if (keyboardStatusToast) {
            keyboardStatusToast.classList.remove("visible");
            keyboardStatusToast.classList.remove("error");
            keyboardStatusToast.textContent = "";
        }
    };

    const showKeyboardStatusToast = (text, undoState) => {
        clearKeyboardStatusToast();
        if (!keyboardStatusToast) {
            return;
        }
        keyboardStatusUndo = {
            ...undoState,
            timer: window.setTimeout(() => {
                clearKeyboardStatusToast();
            }, KEYBOARD_STATUS_TOAST_MS)
        };
        keyboardStatusToast.textContent = text;
        keyboardStatusToast.classList.add("visible");
    };

    const showKeyboardStatusError = (text) => {
        clearKeyboardStatusToast();
        if (!keyboardStatusToast) {
            return;
        }
        keyboardStatusToast.textContent = text;
        keyboardStatusToast.classList.add("visible", "error");
    };

    const topLevelStatus = (status) => {
        const normalized = normalizeStatus(status);
        return normalized.includes("/") ? normalized.split("/")[0] : normalized;
    };

    const hideStateFeedbackPop = () => {
        if (stateFeedbackTimer) {
            window.clearTimeout(stateFeedbackTimer);
            stateFeedbackTimer = null;
        }
        stateFeedbackPop?.classList.remove("visible");
    };

    const showMadProfessorFeedback = (kind) => {
        if (!stateFeedbackPop || !stateFeedbackImage) {
            return;
        }
        const images = kind === "congratulations"
            ? MAD_PROFESSOR_CONGRATULATION_IMAGES
            : MAD_PROFESSOR_QUESTION_IMAGES;
        const imageName = images[Math.floor(Math.random() * images.length)];
        hideStateFeedbackPop();
        stateFeedbackImage.alt = kind === "congratulations"
            ? "The mad professor celebrates your classification"
            : "The mad professor considers the possible classifications";
        stateFeedbackImage.onload = () => {
            requestAnimationFrame(() => stateFeedbackPop.classList.add("visible"));
        };
        stateFeedbackImage.onerror = hideStateFeedbackPop;
        stateFeedbackImage.src = "/assets/mad-professor/" + kind + "/" + imageName;
        stateFeedbackTimer = window.setTimeout(hideStateFeedbackPop, kind === "congratulations" ? 1800 : 1300);
    };

    const stateFeedbackDirection = (logicalFeedId, previousStatus, nextStatus) => {
        const config = workflowConfigForLogicalFeed(logicalFeedId);
        const states = Array.isArray(config?.states) ? config.states : [];
        const previousIndex = states.findIndex((state) => state?.id === previousStatus);
        const nextIndex = states.findIndex((state) => state?.id === nextStatus);
        if (previousIndex < 0 || nextIndex < 0 || previousIndex === nextIndex) {
            return null;
        }
        return nextIndex > previousIndex ? "up" : "down";
    };

    const showStateFeedbackPop = (logicalFeedId, previousStatus, nextStatus) => {
        if (!stateFeedbackPop || !stateFeedbackImage) {
            return;
        }
        hideStateFeedbackPop();
        if (usesDefaultMiageWorkflow(logicalFeedId)) {
            const folder = MIAGE_STATE_FEEDBACK_FOLDERS[topLevelStatus(nextStatus)];
            if (!folder) {
                return;
            }
            const frameNumber = Math.floor(Math.random() * MIAGE_STATE_FEEDBACK_FRAME_COUNT) + 1;
            const frameName = "frame_" + String(frameNumber).padStart(2, "0") + ".png";
            stateFeedbackImage.onload = () => {
                requestAnimationFrame(() => stateFeedbackPop.classList.add("visible"));
            };
            stateFeedbackImage.onerror = hideStateFeedbackPop;
            stateFeedbackImage.src = "/assets/state-feedback/" + folder + "/" + frameName;
            stateFeedbackTimer = window.setTimeout(hideStateFeedbackPop, STATE_FEEDBACK_POP_MS);
            return;
        }
        const direction = stateFeedbackDirection(logicalFeedId, previousStatus, nextStatus);
        const images = STATE_FEEDBACK_DIRECTION_IMAGES[direction] || [];
        if (!images.length) {
            return;
        }
        const imageName = images[Math.floor(Math.random() * images.length)];
        stateFeedbackImage.onload = () => {
            requestAnimationFrame(() => stateFeedbackPop.classList.add("visible"));
        };
        stateFeedbackImage.onerror = hideStateFeedbackPop;
        stateFeedbackImage.src = "/assets/state-feedback/" + direction + "/" + imageName;
        stateFeedbackTimer = window.setTimeout(hideStateFeedbackPop, STATE_FEEDBACK_POP_MS);
    };

    const startKeyboardStatePicker = (side) => {
        if (classificationModeActive) {
            return;
        }
        const card = selectedPaperCard();
        if (!card) {
            return;
        }
        const currentStatus = normalizeStatus(card.dataset.paperStatus);
        const reachableStates = classificationModeActive
            ? classificationDirectionalTargets(card)[side]
            : side === "right"
                ? workflowTargetsFromState(card.dataset.logicalFeedId, currentStatus)
                : [];
        const options = wheelOptionsForReachableStates(card.dataset.logicalFeedId, reachableStates);
        const activeIndex = options.findIndex((entry) => entry.type === "state");
        if (activeIndex < 0) {
            clearKeyboardStatePicker();
            return;
        }
        keyboardStatePicker = {
            card,
            paperId: card.dataset.paperId,
            logicalFeedId: card.dataset.logicalFeedId,
            currentStatus,
            side,
            options,
            activeIndex
        };
        renderKeyboardStatePicker();
    };

    const immediateKeyboardTransition = async (side, card, nextStatus, previousStatus) => {
        if (classificationModeActive) {
            return performClassificationTransition(card, nextStatus, side);
        }
        const applied = await applyPaperStatusTransition(card, nextStatus, previousStatus, {
            advanceFocus: true,
            backgroundPersistence: classificationModeActive
        });
        if (!applied) {
            return false;
        }
        const nextCard = selectedPaperCard();
        if (workflowTargetsFromState(card.dataset.logicalFeedId, nextStatus).includes(previousStatus)) {
            showKeyboardStatusToast(
                "Moved to " + statusLabel(nextStatus) + ". Press " + (side === "left" ? "→" : "←") + " to undo.",
                {
                    side,
                    card,
                    previousStatus,
                    nextStatus,
                    nextCardId: nextCard?.dataset.paperId || null
                }
            );
        }
        return true;
    };

    const maybeStartImmediateKeyboardTransition = async (side) => {
        const card = selectedPaperCard();
        if (!card) {
            return false;
        }
        const currentStatus = normalizeStatus(card.dataset.paperStatus);
        const states = classificationModeActive
            ? classificationDirectionalTargets(card)[side]
            : side === "right"
                ? workflowTargetsFromState(card.dataset.logicalFeedId, currentStatus)
                : [];
        if (states.length !== 1) {
            return false;
        }
        await immediateKeyboardTransition(side, card, states[0], currentStatus);
        return true;
    };

    const tryUndoKeyboardTransition = async (pressedSide) => {
        if (!keyboardStatusUndo) {
            return false;
        }
        const oppositeSide = keyboardStatusUndo.side === "left" ? "right" : "left";
        if (pressedSide !== oppositeSide) {
            return false;
        }
        const { card, previousStatus, nextStatus } = keyboardStatusUndo;
        if (!workflowTargetsFromState(card.dataset.logicalFeedId, nextStatus).includes(previousStatus)) {
            clearKeyboardStatusToast();
            return false;
        }
        clearKeyboardStatusToast();
        await applyPaperStatusTransition(card, previousStatus, nextStatus, { advanceFocus: false });
        openPaperCard(findCardByPaperId(card.dataset.paperId) || card);
        return true;
    };

    const resetCardDragVisual = (element) => {
        element?.classList.remove("drag-armed", "drag-left", "drag-right");
    };

    const beginPaperStatusDrag = (card, event, visualTarget = card) => {
        clearKeyboardStatePicker();
        const workflow = workflowTreeForLogicalFeed(card.dataset.logicalFeedId);
        cardDragState = {
            card,
            paperId: card.dataset.paperId,
            startX: event.clientX,
            startY: event.clientY,
            workflow,
            currentStatus: normalizeStatus(card.dataset.paperStatus),
            currentTopStatus: card.dataset.paperTopStatus || normalizeStatus(card.dataset.paperStatus),
            side: null,
            options: [],
            activeIndex: 0,
            visualTarget
        };
        visualTarget.classList.add("drag-armed");
        hideStateWheels();
    };

    const beginPaperStatusDragAtPoint = (card, clientX, clientY, visualTarget = card) => {
        beginPaperStatusDrag(card, { clientX, clientY }, visualTarget);
    };

    const updatePaperStatusDrag = (clientX, clientY) => {
        if (!cardDragState) {
            return;
        }

        const card = cardDragState.card;
        const visualTarget = cardDragState.visualTarget || card;
        const deltaX = clientX - cardDragState.startX;
        const deltaY = clientY - cardDragState.startY;
        resetCardDragVisual(visualTarget);
        visualTarget.classList.add("drag-armed");

        if (deltaX > STATE_DRAG_TRIGGER_X) {
            const reachableStates = workflowTargetsFromState(card.dataset.logicalFeedId, cardDragState.currentStatus);
            const options = wheelOptionsForReachableStates(card.dataset.logicalFeedId, reachableStates);
            const selectableIndexes = options.map((entry, index) => entry.type === "state" ? index : -1).filter((index) => index >= 0);
            if (!selectableIndexes.length) {
                cardDragState.side = null;
                cardDragState.options = [];
                cardDragState.activeIndex = 0;
                hideStateWheels();
                return;
            }
            const activeIndex = selectableIndexes[Math.max(0, Math.min(selectableIndexes.length - 1, Math.round(deltaY / STATE_WHEEL_ROW_HEIGHT)))] ?? 0;
            cardDragState.side = "right";
            cardDragState.options = options;
            cardDragState.activeIndex = activeIndex;
            visualTarget.classList.add("drag-right");
            leftStateWheel.classList.remove("visible");
            rightStateWheel.classList.add("visible");
            renderStateWheel(rightStateOptions, options, activeIndex);
        } else if (deltaX < -STATE_DRAG_TRIGGER_X) {
            // Incoming graph edges are historical paths, not valid reverse transitions.
            cardDragState.side = null;
            cardDragState.options = [];
            cardDragState.activeIndex = 0;
            hideStateWheels();
        } else {
            cardDragState.side = null;
            cardDragState.options = [];
            cardDragState.activeIndex = 0;
            hideStateWheels();
        }
    };

    const finishPaperStatusDrag = async (clientX) => {
        if (!cardDragState) {
            return;
        }

        const { card, startX, side, options, activeIndex, currentStatus, visualTarget } = cardDragState;
        cardDragState = null;
        const deltaX = clientX - startX;
        let nextStatus = null;

        if ((side === "right" && deltaX > STATE_DRAG_TRIGGER_X) || (side === "left" && deltaX < -STATE_DRAG_TRIGGER_X)) {
            nextStatus = options[activeIndex]?.value || null;
        }

        hideStateWheels();
        resetCardDragVisual(visualTarget || card);

        if (!nextStatus || nextStatus === normalizeStatus(card.dataset.paperStatus)) {
            return;
        }

        try {
            await applyPaperStatusTransition(card, nextStatus, currentStatus, { advanceFocus: false });
        } catch (error) {
            window.alert(error.message || "Failed to update paper status");
        }
    };

    const renderPaperCardElement = (record) => {
        const article = document.createElement("article");
        article.className = "card drop-target selectable";
        article.dataset.logicalFeedName = record.logicalFeedName || "";
        article.dataset.logicalFeedId = record.logicalFeedId == null ? "" : String(record.logicalFeedId);
        article.dataset.paperStatus = record.paperStatus || "NEW";
        article.dataset.paperRecordType = record.paperRecordType || "PAPER";
        article.dataset.paperTopStatus = record.paperTopStatus || normalizeStatus(record.paperStatus);
        article.dataset.paperId = String(record.id);
        article.dataset.pdfUrl = record.pdfUrl || "";
        article.dataset.paperTitle = record.paperTitle || "";
        article.dataset.paperAuthors = record.paperAuthors || "Unknown authors";
        article.dataset.paperPublishedOn = record.paperPublishedOn || "unknown";
        article.dataset.paperVenue = record.paperVenue || "Unknown venue";
        article.dataset.paperFeedName = record.paperFeedName || "";
        article.dataset.paperSummary = record.paperSummary || "";
        article.dataset.paperSourceLink = record.paperSourceLink || "";
        article.dataset.paperOpenAccessLink = record.paperOpenAccessLink || "";
        article.dataset.paperTags = record.paperTags || "";
        article.dataset.paperCanEditTags = record.paperCanEditTags ? "true" : "false";
        article.dataset.paperNotes = record.paperNotes || "";
        article.dataset.paperDetailLoaded = record.paperNotes == null ? "false" : "true";
        article.dataset.paperIsNew = record.paperIsNew ? "true" : "false";
        article.dataset.paperIsFresh = record.paperIsFresh ? "true" : "false";
        article.innerHTML =
            '<div class="card-feed-row">'
                + '<div class="pill">' + escapeHtml(record.logicalFeedName || "") + '</div>'
                + (record.paperRecordType === "GRAY_LITERATURE"
                    ? '<span class="paper-status-badge">Gray literature</span>'
                    : '')
                + ((record.paperIsNew || record.paperIsFresh)
                    ? '<div class="paper-status-badges">'
                        + (record.paperIsNew ? '<span class="paper-status-badge new-badge">New</span>' : '')
                        + (record.paperIsFresh ? '<span class="paper-status-badge fresh-badge">Fresh</span>' : '')
                    + '</div>'
                    : '')
            + '</div>'
            + '<div class="card-header">'
                + '<div class="card-title-wrap">'
                    + '<h3 class="paper-title">' + escapeHtml(record.paperTitle || "") + '</h3>'
                + '</div>'
                + '<div class="pdf-presence ' + (record.pdfUrl ? 'has-pdf' : '') + '" title="' + (record.pdfUrl ? 'PDF attached' : 'No PDF attached') + '">'
                    + '<span aria-hidden="true">' + (record.pdfUrl ? '📄' : '○') + '</span>'
                + '</div>'
            + '</div>'
            + '<p class="meta paper-meta">'
                + escapeHtml(record.paperAuthors || "Unknown authors") + '<br>'
                + escapeHtml(record.paperVenue || "Unknown venue") + '<br>'
                + 'Published: ' + escapeHtml(record.paperPublishedOn || "unknown") + '<br>'
                + 'From feed: ' + escapeHtml(record.paperFeedName || "")
            + '</p>'
            + (((record.paperSummary || "").trim())
                ? '<p class="paper-summary">' + escapeHtml(record.paperSummary || "") + '</p>'
                : '')
            + '<p>' + paperCardLinksHtml(record) + '</p>';
        renderCardSearchHighlights(article);
        return article;
    };

    const paperCardLinksHtml = (record) => {
        const links = [];
        if (record.pdfUrl) {
            links.push('<a href="' + escapeHtml(record.pdfUrl) + '" target="_blank" rel="noreferrer">PDF</a>');
        }
        if (record.paperSourceLink && !record.paperSourceLink.startsWith("upload:")) {
            links.push('<a href="' + escapeHtml(record.paperSourceLink) + '" target="_blank" rel="noreferrer">Source</a>');
        }
        if (record.paperOpenAccessLink) {
            links.push('<a href="' + escapeHtml(record.paperOpenAccessLink) + '" target="_blank" rel="noreferrer">Open access</a>');
        }
        return links.join(" | ");
    };

    const bindPaperCard = (card) => {
        const paperId = card.dataset.paperId;

        card.addEventListener("click", (event) => {
            if (suppressCardClick) {
                suppressCardClick = false;
                return;
            }
            if (cardDragState) {
                return;
            }
            if (event.target.closest("a")) {
                return;
            }
            if (card.dataset.pdfUrl) {
                showPdfForCard(card);
                return;
            }
            focusPaperCard(card);
        });

        card.addEventListener("mousedown", (event) => {
            if (event.button !== 0 || event.target.closest("a")) {
                return;
            }
            beginPaperStatusDrag(card, event, card);
        });

        card.addEventListener("touchstart", (event) => {
            if (event.touches.length !== 1 || event.target.closest("a")) {
                return;
            }
            const touch = event.touches[0];
            activeTouchStatusDrag = {
                target: "card",
                paperId,
                startX: touch.clientX,
                startY: touch.clientY,
                captured: false
            };
            beginPaperStatusDragAtPoint(card, touch.clientX, touch.clientY, card);
        }, { passive: true });

        ["dragenter", "dragover"].forEach((eventName) => {
            card.addEventListener(eventName, (event) => {
                event.preventDefault();
                card.classList.add("dragover");
            });
        });

        ["dragleave", "dragend", "drop"].forEach((eventName) => {
            card.addEventListener(eventName, (event) => {
                event.preventDefault();
                card.classList.remove("dragover");
            });
        });

        card.addEventListener("drop", (event) => {
            const files = event.dataTransfer.files;
            if (!files || files.length === 0) {
                return;
            }
            uploadPdfForPaper(paperId, files[0], card);
        });
    };

    const filteredPaperRecords = () => {
        const selectedLogicalFeedId = logicalFeedFilter.value;
        const searchQuery = parseStateSearchQuery(stateSearchQuery);
        const hasDateFilter = searchQuery.dateFilters.length > 0;
        const filtered = paperRecords.filter((record) => {
            const feedMatches = activePrimaryTab === "tags"
                ? Boolean(selectedLogicalFeedId) && String(record.logicalFeedId) === String(selectedLogicalFeedId)
                : (!selectedLogicalFeedId || String(record.logicalFeedId) === String(selectedLogicalFeedId));
            if (!feedMatches) {
                return false;
            }
            if (activePrimaryTab === "tags") {
                return tagFilterMatchesRecord(record, selectedTagFilters);
            }
            const stateMatches = hasDateFilter || selectedStateMatchesRecord(record);
            if (!stateMatches) {
                return false;
            }
            return stateSearchMatchesRecord(record, searchQuery);
        });
        if (searchDebugEnabled && searchQuery.dateFilters.length) {
            console.log("[paper-monitor] Filtered paper records with date filters", {
                selectedLogicalFeedId,
                activePrimaryTab,
                activeStatusTab,
                selectedTagFilters,
                totalRecords: paperRecords.length,
                matchingRecords: filtered.length,
                sample: filtered.slice(0, 10).map((record) => ({
                    id: record.id,
                    title: record.paperTitle,
                    publishedOn: record.paperPublishedOn,
                    status: record.paperStatus,
                    topStatus: record.paperTopStatus
                }))
            });
        }
        return filtered;
    };

    const renderPaginatedPaperList = () => {
        const filtered = filteredPaperRecords();
        if (selectedPaperId) {
            const selectedIndex = filtered.findIndex((record) => String(record.id) === String(selectedPaperId));
            if (selectedIndex >= 0) {
                stateSearchVisibleLimit = Math.max(stateSearchVisibleLimit, selectedIndex + 1);
            }
        }
        const visible = serverRenderedShareMode ? filtered.slice(0, stateSearchVisibleLimit) : filtered;
        paperList.innerHTML = "";
        if (!visible.length) {
            const message = document.createElement("p");
            message.className = "meta";
            message.textContent = papersLoaded ? "No papers match the current filters." : "Loading papers...";
            paperList.appendChild(message);
        } else {
            visible.forEach((record) => {
                const card = renderPaperCardElement(record);
                paperList.appendChild(card);
                bindPaperCard(card);
            });
        }
        paperList.appendChild(searchLoadMoreRow);
        const hasMore = serverRenderedShareMode ? filtered.length > visible.length : Boolean(browserNextCursor);
        searchLoadMoreRow.classList.toggle("hidden", !hasMore);
        const activeCard = selectedPaperCard();
        if (activeCard) {
            activeCard.classList.add("active");
        }
        return { filtered, visible, hasMore };
    };

    const maybeLoadMoreVisiblePapers = () => {
        if (serverRenderedShareMode || !papersLoaded) {
            return;
        }
        if (!browserNextCursor || browserRequestInFlight) {
            return;
        }
        const nearBottom = window.innerHeight + window.scrollY >= document.body.offsetHeight - 240;
        if (!nearBottom) {
            return;
        }
        loadNextBrowserPage();
    };

    const applyLogicalFeedFilter = () => {
        if (!serverRenderedShareMode) {
            const selectedLogicalFeedId = logicalFeedFilter.value;
            const availableCounts = selectedWorkflowCounts();
            syncActiveStateSelection();
            updateReaderModeControls();

            const filterSignature = selectedLogicalFeedId
                ? currentBrowserFilterSignature(selectedLogicalFeedId)
                : "";
            if (selectedLogicalFeedId && filterSignature !== loadedBrowserFilterSignature
                    && filterSignature !== requestedBrowserFilterSignature) {
                requestedBrowserFilterSignature = filterSignature;
                browserRequestGeneration += 1;
                paperRecords = [];
                browserNextCursor = null;
                papersLoaded = false;
                scheduleBrowserReload(activePrimaryTab === "state" && stateSearchQuery ? 250 : 0);
            }

            const filtered = filteredPaperRecords();
            const selectedStillVisible = filtered.some((record) => String(record.id) === String(selectedPaperId));
            if (selectedPaperId && !selectedStillVisible) {
                closeViewer();
            }

            renderStateTabs(availableCounts);

            if (mobileStateMenuSummary) {
                mobileStateMenuSummary.textContent = activePrimaryTab === "tags"
                    ? "Tags"
                    : activeChildStatus
                        ? statusLabel(activeStatusTab) + " / " + statusLabel(activeChildStatus)
                        : activeStatusTab
                            ? statusLabel(activeStatusTab) + " (" + (availableCounts[activeStatusTab] || 0) + ")"
                        : "States";
            }

            renderTagBrowser();
            readerSearch.classList.toggle("hidden", activePrimaryTab !== "state" || Boolean(selectedPaperId));
            const { hasMore } = renderPaginatedPaperList();
            searchLoadMoreRow.classList.toggle("hidden", !hasMore);

            const toolsDisabled = !selectedLogicalFeedId;
            exportTabButton.classList.toggle("disabled", toolsDisabled);
            exportTabButton.title = toolsDisabled ? "Select a paper feed before using tools." : "Tools";
            if (readerImportPaperButton) readerImportPaperButton.disabled = toolsDisabled;
            if (readerOpenExportButton) readerOpenExportButton.disabled = toolsDisabled;
            updateReaderReviewAction();
            updatePaperExportControls();
            if (readerBatchUpdateButton) {
                const batchDisabled = !selectedLogicalFeedId || filtered.length === 0;
                readerBatchUpdateButton.disabled = batchDisabled;
                readerBatchUpdateButton.title = batchDisabled
                    ? "Select a paper feed with at least one matching paper first."
                    : "Apply a batch update to the currently filtered papers.";
            }
            if (readerDiagramButton) {
                const diagramDisabled = !selectedLogicalFeedId;
                readerDiagramButton.disabled = diagramDisabled;
                readerDiagramButton.title = diagramDisabled
                    ? "Select a paper feed first."
                    : "Open the live workflow diagram for this paper feed.";
            }
            if (readerMendeleySyncButton) {
                const syncDisabled = !selectedLogicalFeedId || !selectedLogicalFeedCanAdmin()
                    || selectedMendeleySyncRunning();
                readerMendeleySyncButton.disabled = syncDisabled;
                readerMendeleySyncButton.title = selectedMendeleySyncRunning()
                    ? "Mendeley synchronization is in progress."
                    : syncDisabled
                    ? "Select a paper feed you can manage first."
                    : "Queue a Mendeley synchronization for this paper feed.";
            }
            if (readerPublicUrlButton) {
                const canAdmin = selectedLogicalFeedCanAdmin();
                const isPublic = selectedLogicalFeedIsPublic();
                const shareDisabled = !selectedLogicalFeedId || !canAdmin;
                readerPublicUrlButton.disabled = shareDisabled;
                if (readerPublicUrlLabel) {
                    readerPublicUrlLabel.textContent = isPublic ? "Copy public link" : "Share paper feed";
                }
                readerPublicUrlButton.title = shareDisabled
                    ? "Select a paper feed you can manage first."
                    : isPublic
                        ? "Copy the public read-only URL for this paper feed."
                        : "Make this paper feed public, then copy its read-only URL.";
            }
            const activeCard = selectedPaperCard();
            if (activeCard) {
                populatePaperViewMeta(activeCard);
                if (selectedContentKind !== "pdf") {
                    renderPaperCardPreview(activeCard);
                }
            }
            renderReviewLauncher();
            updatePaperFeedHeading();
            if (batchPaperModal?.open) {
                updateBatchPaperSummary();
            }
            return;
        }

        const selectedLogicalFeedId = logicalFeedFilter.value;
        let activeCardStillVisible = false;
        const availableCounts = selectedWorkflowCounts();
        const searchQuery = parseStateSearchQuery(stateSearchQuery);
        const hasDateFilter = searchQuery.dateFilters.length > 0;
        const isStateSearchActive = activePrimaryTab === "state"
            && (searchQuery.textQuery.length > 0 || searchQuery.dateFilters.length > 0);
        let matchingSearchCards = [];
        syncActiveStateSelection();

        allPaperCards().forEach((card) => {
            renderCardSearchHighlights(card);
            const feedMatches = activePrimaryTab === "tags"
                ? Boolean(selectedLogicalFeedId) && card.dataset.logicalFeedId === selectedLogicalFeedId
                : (!selectedLogicalFeedId || card.dataset.logicalFeedId === selectedLogicalFeedId);
            const stateMatches = feedMatches && (hasDateFilter || selectedStateMatchesCard(card));
            const tagMatches = feedMatches && tagFilterMatchesCard(card, selectedTagFilters);
            const searchMatches = stateMatches && stateSearchMatchesCard(card, searchQuery);
            if (isStateSearchActive && searchMatches) {
                matchingSearchCards.push(card);
            }
        const visible = activePrimaryTab === "tags"
                ? tagMatches
                : stateMatches && !isStateSearchActive;
            card.hidden = !visible;
            if (visible && card.dataset.paperId === selectedPaperId) {
                activeCardStillVisible = true;
            }
        });

        if (isStateSearchActive) {
            const visibleSearchCards = new Set(matchingSearchCards.slice(0, stateSearchVisibleLimit));
            allPaperCards().forEach((card) => {
                const visible = visibleSearchCards.has(card);
                card.hidden = !visible;
                if (visible && card.dataset.paperId === selectedPaperId) {
                    activeCardStillVisible = true;
                }
            });
        }

        if (selectedPaperId && !activeCardStillVisible) {
            closeViewer();
        }

        renderStateTabs(availableCounts);

        if (mobileStateMenuSummary) {
            mobileStateMenuSummary.textContent = activePrimaryTab === "tags"
                ? "Tags"
                : activeChildStatus
                    ? statusLabel(activeStatusTab) + " / " + statusLabel(activeChildStatus)
                    : activeStatusTab
                        ? statusLabel(activeStatusTab) + " (" + (availableCounts[activeStatusTab] || 0) + ")"
                    : "States";
        }

        renderTagBrowser();

        readerSearch.classList.toggle("hidden", activePrimaryTab !== "state" || Boolean(selectedPaperId));
        searchLoadMoreRow.classList.toggle("hidden",
            !isStateSearchActive || matchingSearchCards.length <= stateSearchVisibleLimit);

        const toolsDisabled = !selectedLogicalFeedId;
        exportTabButton.classList.toggle("disabled", toolsDisabled);
        exportTabButton.title = toolsDisabled ? "Select a paper feed before using tools." : "Tools";
        if (readerImportPaperButton) readerImportPaperButton.disabled = toolsDisabled;
        if (readerOpenExportButton) readerOpenExportButton.disabled = toolsDisabled;
        updateReaderReviewAction();
        updatePaperExportControls();
        if (readerDiagramButton) {
            const diagramDisabled = !selectedLogicalFeedId;
            readerDiagramButton.disabled = diagramDisabled;
            readerDiagramButton.title = diagramDisabled
                ? "Select a paper feed first."
                : "Open the live workflow diagram for this paper feed.";
        }
        if (readerMendeleySyncButton) {
            const syncDisabled = !selectedLogicalFeedId || !selectedLogicalFeedCanAdmin()
                || selectedMendeleySyncRunning();
            readerMendeleySyncButton.disabled = syncDisabled;
            readerMendeleySyncButton.title = selectedMendeleySyncRunning()
                ? "Mendeley synchronization is in progress."
                : syncDisabled
                ? "Select a paper feed you can manage first."
                : "Queue a Mendeley synchronization for this paper feed.";
        }
        if (readerPublicUrlButton) {
            const canAdmin = selectedLogicalFeedCanAdmin();
            const isPublic = selectedLogicalFeedIsPublic();
            const shareDisabled = !selectedLogicalFeedId || !canAdmin;
            readerPublicUrlButton.disabled = shareDisabled;
            if (readerPublicUrlLabel) {
                readerPublicUrlLabel.textContent = isPublic ? "Copy public link" : "Share paper feed";
            }
            readerPublicUrlButton.title = shareDisabled
                ? "Select a paper feed you can manage first."
                : isPublic
                    ? "Copy the public read-only URL for this paper feed."
                    : "Make this paper feed public, then copy its read-only URL.";
        }
        const activeCard = selectedPaperCard();
        if (activeCard) {
            populatePaperViewMeta(activeCard);
            if (selectedContentKind !== "pdf") {
                renderPaperCardPreview(activeCard);
            }
        }
        renderReviewLauncher();
        updatePaperFeedHeading();
    };

    const closeViewer = () => {
        clearKeyboardStatePicker();
        clearKeyboardStatusToast();
        selectedPaperId = null;
        selectedPdfUrl = null;
        selectedDownloadUrl = null;
        selectedContentKind = null;
        currentPdfPageTexts = [];
        currentZoom = 1;
        currentRenderToken += 1;
        allPaperCards().forEach((item) => item.classList.remove("active"));
        pdfFrame.innerHTML = "";
        contentPanelLabel.textContent = "";
        pdfPanelTitle.textContent = "Select a paper";
        pdfPanelMeta.textContent = "The paper view will open here when you click a paper.";
        renderPaperTagEditor(null);
        notesEditor.value = "";
        notesStatus.textContent = "Saved";
        if (currentTtsUrl) {
            URL.revokeObjectURL(currentTtsUrl);
            currentTtsUrl = null;
        }
        ttsPlayer.pause();
        ttsPlayer.removeAttribute("src");
        ttsPlayer.classList.add("hidden");
        renderNotesPreview();
        leaveNotesEditMode();
        applyLayoutState();
    };

    const openPaperCard = (card) => {
        if (!card) {
            return;
        }
        clearKeyboardStatePicker();
        if (card.dataset.pdfUrl) {
            showPdfForCard(card);
            card.scrollIntoView({ behavior: "smooth", block: "center" });
            return;
        }
        focusPaperCard(card);
    };

    const visiblePaperCards = () => {
        return allPaperCards().filter((card) => !card.hidden);
    };

    const movePaperSelection = (direction) => {
        const visibleCards = visiblePaperCards();
        if (!visibleCards.length) {
            return;
        }
        const currentIndex = visibleCards.findIndex((card) => card.dataset.paperId === selectedPaperId);
        let nextIndex;
        if (currentIndex === -1) {
            nextIndex = direction > 0 ? 0 : visibleCards.length - 1;
        } else {
            nextIndex = Math.max(0, Math.min(visibleCards.length - 1, currentIndex + direction));
        }
        openPaperCard(visibleCards[nextIndex]);
    };

    const focusPaperCard = (card) => {
        clearKeyboardStatePicker();
        allPaperCards().forEach((item) => item.classList.remove("active"));
        card.classList.add("active");
        selectedPaperId = card.dataset.paperId;
        markPaperViewed(selectedPaperId, card);
        selectedPdfUrl = null;
        selectedDownloadUrl = card.dataset.pdfUrl || "";
        selectedContentKind = "card";
        currentZoom = 1;
        currentRenderToken += 1;
        pdfFrame.innerHTML = "";
        contentPanelLabel.textContent = "";
        populatePaperViewMeta(card);
        currentPdfPageTexts = [];
        notesEditor.value = card.dataset.paperNotes || "";
        ensurePaperDetail(card);
        renderPaperTagEditor(card);
        notesStatus.textContent = "Saved";
        updateNotesEditorView();
        renderNotesPreview();
        leaveNotesEditMode();
        renderPaperCardPreview(card);
        applyLayoutState();
        card.scrollIntoView({ behavior: "smooth", block: "center" });
    };

    const ensurePaperDetail = async (card) => {
        if (!card || card.dataset.paperDetailLoaded === "true" || card.dataset.paperDetailLoading === "true") return;
        card.dataset.paperDetailLoading = "true";
        const paperId = card.dataset.paperId;
        try {
            const detailUrl = sharedFeedToken
                ? "/api/share/feed/" + encodeURIComponent(sharedFeedToken) + "/papers/" + encodeURIComponent(paperId) + "/browser-detail"
                : "/api/papers/" + encodeURIComponent(paperId) + "/browser-detail";
            const response = await fetch(detailUrl, {
                headers: { Accept: "application/json" }
            });
            const body = await response.text();
            if (!response.ok) throw new Error(body || "Could not load paper notes.");
            const detail = JSON.parse(body);
            card.dataset.paperNotes = detail.paperNotes || "";
            card.dataset.paperDetailLoaded = "true";
            const record = paperRecordById(paperId);
            if (record) record.paperNotes = detail.paperNotes || "";
            if (String(selectedPaperId || "") === String(paperId)) {
                notesEditor.value = card.dataset.paperNotes;
                updateNotesEditorView();
                renderNotesPreview();
                notesStatus.textContent = "Saved";
            }
        } catch (error) {
            if (String(selectedPaperId || "") === String(paperId)) {
                notesStatus.textContent = error.message || "Could not load paper notes";
            }
        } finally {
            card.dataset.paperDetailLoading = "false";
        }
    };

    const openInitialPaper = async () => {
        if (!initialPaperId) {
            return;
        }

        let card = findCardByPaperId(initialPaperId);
        if (!card && !serverRenderedShareMode) {
            try {
                const response = await fetch("/api/papers/" + encodeURIComponent(initialPaperId) + "/browser-detail", {
                    headers: { Accept: "application/json" }
                });
                if (!response.ok) return;
                const detail = await response.json();
                activeStatusTab = detail.paperTopStatus || normalizeStatus(detail.paperStatus);
                activeChildStatus = normalizeStatus(detail.paperStatus).includes("/")
                    ? normalizeStatus(detail.paperStatus) : null;
                requestedBrowserFilterSignature = "";
                await reloadBrowserPapers();
                if (!paperRecordById(detail.id)) paperRecords.unshift(detail);
                applyLogicalFeedFilter();
                card = findCardByPaperId(initialPaperId);
            } catch (error) {
                return;
            }
        }
        if (!card) {
            return;
        }

        logicalFeedFilter.value = card.dataset.logicalFeedId || "";
        activeStatusTab = card.dataset.paperTopStatus || normalizeStatus(card.dataset.paperStatus);
        activeChildStatus = normalizeStatus(card.dataset.paperStatus).includes("/")
            ? normalizeStatus(card.dataset.paperStatus)
            : null;
        applyLogicalFeedFilter();

        if (card.hidden) {
            return;
        }

        if (card.dataset.pdfUrl && !isMobileViewport()) {
            showPdfForCard(card);
            card.scrollIntoView({ behavior: "smooth", block: "center" });
            return;
        }

        focusPaperCard(card);
    };

    const updatePaperStatus = async (paperId, nextStatus, params = null) => {
        const body = params instanceof URLSearchParams ? params : new URLSearchParams({ status: nextStatus });
        const response = await fetch("/papers/" + paperId + "/status", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body
        });

        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to update paper status");
        }
    };

    const enqueuePaperStatusUpdate = (paperId, nextStatus, params) => {
        const previous = paperStatusPersistenceQueues.get(paperId) || Promise.resolve();
        const queued = previous
            .catch(() => undefined)
            .then(() => updatePaperStatus(paperId, nextStatus, params));
        paperStatusPersistenceQueues.set(paperId, queued);
        queued.finally(() => {
            if (paperStatusPersistenceQueues.get(paperId) === queued) {
                paperStatusPersistenceQueues.delete(paperId);
            }
        }).catch(() => undefined);
        return queued;
    };

    let criteriaSelectionResolve = null;

    const closeCriteriaSelection = (params = null) => {
        const resolve = criteriaSelectionResolve;
        criteriaSelectionResolve = null;
        if (criteriaSelectionModal?.open) {
            criteriaSelectionModal.close();
        }
        resolve?.(params);
    };

    const addCriteriaSelectionGroup = (kind, rule, logicalFeedId) => {
        const choices = taxonomyLeaves(rule.taxonomy, logicalFeedId);
        const fieldset = document.createElement("fieldset");
        fieldset.dataset.criteriaKind = kind;
        fieldset.dataset.criteriaMin = String(rule.min || rule.exactly || 1);
        const legend = document.createElement("legend");
        legend.textContent = (kind === "exclusion" ? "Exclusion" : "Inclusion")
            + " criteria (choose at least " + fieldset.dataset.criteriaMin + ")";
        fieldset.appendChild(legend);
        choices.forEach((choice) => {
            const label = document.createElement("label");
            label.className = "review-state-option";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.value = choice.id;
            input.dataset.criteriaChoice = kind;
            const text = document.createElement("span");
            text.textContent = choice.label;
            label.append(input, text);
            fieldset.appendChild(label);
        });
        criteriaSelectionGroups.appendChild(fieldset);
    };

    const collectStateRequirementParams = async (logicalFeedId, nextStatus) => {
        const params = new URLSearchParams({ status: nextStatus });
        const workflowState = workflowStateById(logicalFeedId, nextStatus);
        const requires = workflowState?.requires || {};
        const exclusionRule = requires.exclusionCriteria || requires.exclusionCriterion;
        const inclusionRule = requires.inclusionCriteria;
        if (!exclusionRule && !inclusionRule) {
            return params;
        }
        if (!criteriaSelectionModal || !criteriaSelectionGroups) {
            window.alert("Criteria selection is unavailable. Please reopen the paper feed.");
            return null;
        }
        criteriaSelectionGroups.innerHTML = "";
        if (criteriaSelectionNotesInput) criteriaSelectionNotesInput.value = "";
        criteriaSelectionModal.dataset.nextStatus = nextStatus;
        criteriaSelectionError.classList.add("hidden");
        criteriaSelectionError.textContent = "";
        criteriaSelectionTitle.textContent = "Choose criteria for " + classificationStateLabel(
            { dataset: { logicalFeedId } }, nextStatus);
        criteriaSelectionStatus.textContent = "Choose all criteria that apply before moving this paper.";
        if (exclusionRule) addCriteriaSelectionGroup("exclusion", exclusionRule, logicalFeedId);
        if (inclusionRule) addCriteriaSelectionGroup("inclusion", inclusionRule, logicalFeedId);
        criteriaSelectionModal.showModal();
        return new Promise((resolve) => {
            criteriaSelectionResolve = resolve;
        });
    };

    const syncPaperStatusCounts = (card, previousStatus, nextStatus) => {
        const logicalFeedOption = Array.from(logicalFeedFilter.options)
                .find((option) => option.value === card.dataset.logicalFeedId);
        if (!logicalFeedOption || previousStatus === nextStatus) {
            return;
        }
        const counts = stateCountsFromToken(logicalFeedOption.dataset.stateCounts || "");
        const previousTopStatus = previousStatus.includes("/") ? previousStatus.split("/")[0] : previousStatus;
        const nextTopStatus = nextStatus.includes("/") ? nextStatus.split("/")[0] : nextStatus;
        counts[previousTopStatus] = Math.max(0, (counts[previousTopStatus] || 0) - 1);
        counts[nextTopStatus] = (counts[nextTopStatus] || 0) + 1;
        const workflowStates = workflowStatesForLogicalFeed(card.dataset.logicalFeedId);
        logicalFeedOption.dataset.stateCounts = workflowStates
            .map((status) => status + ":" + (counts[status] || 0))
            .join("|");
        refreshLogicalFeedOptionLabel(logicalFeedOption);
    };

    const applyPaperStatusTransition = async (
        card,
        nextStatus,
        previousStatus = normalizeStatus(card.dataset.paperStatus),
        options = {}
    ) => {
        if (!nextStatus || nextStatus === normalizeStatus(card.dataset.paperStatus)) {
            return false;
        }
        const advanceFocus = options.advanceFocus === true;
        const backgroundPersistence = options.backgroundPersistence === true;
        const selectedPaperIdBeforeChange = selectedPaperId;
        const visibleCardsBeforeChange = advanceFocus ? visiblePaperCards() : [];
        const currentIndex = advanceFocus
            ? visibleCardsBeforeChange.findIndex((item) => item.dataset.paperId === card.dataset.paperId)
            : -1;
        let nextPaperId = null;
        if (advanceFocus && visibleCardsBeforeChange.length > 1 && currentIndex >= 0) {
            const nextIndex = currentIndex === visibleCardsBeforeChange.length - 1 ? 0 : currentIndex + 1;
            nextPaperId = visibleCardsBeforeChange[nextIndex]?.dataset.paperId || null;
        }
        suppressCardClick = true;
        const paperId = card.dataset.paperId;
        const requestParams = await collectStateRequirementParams(card.dataset.logicalFeedId, nextStatus);
        if (requestParams == null) {
            return false;
        }
        const transitionToken = ++paperStatusTransitionSequence;
        paperStatusTransitionTokens.set(paperId, transitionToken);

        card.dataset.paperStatus = nextStatus;
        card.dataset.paperTopStatus = nextStatus.includes("/") ? nextStatus.split("/")[0] : nextStatus;
        const record = paperRecordById(card.dataset.paperId);
        if (record) {
            record.paperStatus = nextStatus;
            record.paperTopStatus = nextStatus.includes("/") ? nextStatus.split("/")[0] : nextStatus;
        }
        syncPaperStatusCounts(card, normalizeStatus(previousStatus), nextStatus);
        if (selectedPaperId === card.dataset.paperId && activeStatusTab !== nextStatus) {
            closeViewer();
        }
        applyLogicalFeedFilter();
        if (!options.skipStateFeedback) {
            showStateFeedbackPop(card.dataset.logicalFeedId, normalizeStatus(previousStatus), nextStatus);
        }

        if (advanceFocus) {
            let nextCard = nextPaperId ? findCardByPaperId(nextPaperId) : null;
            if (!nextCard || nextCard.hidden) {
                nextCard = visiblePaperCards()[0] || null;
            }
            if (classificationModeActive) {
                if (nextCard && selectPaperForClassification(nextCard)) {
                    renderClassificationPaper(nextCard);
                } else {
                    showClassificationEmpty();
                }
            } else if (nextCard) {
                openPaperCard(nextCard);
            }
        }

        const persistTransition = async () => {
            try {
                await enqueuePaperStatusUpdate(paperId, nextStatus, requestParams);
            } catch (error) {
                if (paperStatusTransitionTokens.get(paperId) !== transitionToken) {
                    return;
                }
                card.dataset.paperStatus = previousStatus;
                card.dataset.paperTopStatus = previousStatus.includes("/") ? previousStatus.split("/")[0] : previousStatus;
                if (record) {
                    record.paperStatus = previousStatus;
                    record.paperTopStatus = previousStatus.includes("/") ? previousStatus.split("/")[0] : previousStatus;
                }
                syncPaperStatusCounts(card, normalizeStatus(nextStatus), previousStatus);
                if (classificationQueueMode) {
                    const recordIndex = paperRecords.findIndex((item) => String(item.id) === String(paperId));
                    if (recordIndex > 0) {
                        paperRecords.unshift(paperRecords.splice(recordIndex, 1)[0]);
                    }
                    classificationQueueCompletedPaperIds.delete(String(paperId));
                    updateClassificationQueueProgress();
                }
                applyLogicalFeedFilter();
                if (!backgroundPersistence && selectedPaperIdBeforeChange === paperId) {
                    openPaperCard(findCardByPaperId(paperId) || card);
                }
                if (classificationModeActive && keyboardShortcutShiftHeld) {
                    const selectedCard = selectedPaperCard();
                    if (selectedCard) {
                        renderClassificationPaper(selectedCard);
                        startKeyboardShortcutPicker();
                    }
                }
                throw error;
            } finally {
                if (paperStatusTransitionTokens.get(paperId) === transitionToken) {
                    paperStatusTransitionTokens.delete(paperId);
                }
            }
        };

        if (backgroundPersistence) {
            persistTransition().catch((error) => {
                showKeyboardStatusError(
                    "Could not save “" + (card.dataset.paperTitle || "paper") + "”. Its previous state was restored."
                );
                console.error("[paper-monitor] Background state update failed", error);
            });
            return true;
        }

        await persistTransition();
        if (!advanceFocus) {
            return true;
        }
        if (!classificationModeActive && nextPaperId) {
            const nextCard = findCardByPaperId(nextPaperId);
            if (nextCard && !nextCard.hidden && selectedPaperId === selectedPaperIdBeforeChange) {
                openPaperCard(nextCard);
                return true;
            }
        }
        return true;
    };

    const renderPdf = (pdfUrl) => {
        if (!pdfUrl) {
            return;
        }

        pdfFrame.innerHTML = "<p class=\"meta\">Loading PDF...</p>";
        const renderToken = ++currentRenderToken;

        Promise.all([loadPdfJs(), fetch(pdfUrl, {
            headers: {
                Accept: "application/pdf"
            }
        })])
        .then(async ([pdfjsLib, response]) => {
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Failed to load PDF");
            }
            return [pdfjsLib, await response.arrayBuffer()];
        })
        .then(async ([pdfjsLib, buffer]) => [pdfjsLib, await pdfjsLib.getDocument({ data: buffer }).promise])
        .then(async ([pdfjsLib, pdfDocument]) => {
            if (renderToken !== currentRenderToken) {
                return;
            }

            pdfFrame.innerHTML = "";
            currentPdfPageTexts = [];

            for (let pageNumber = 1; pageNumber <= pdfDocument.numPages; pageNumber += 1) {
                const page = await pdfDocument.getPage(pageNumber);
                if (renderToken !== currentRenderToken) {
                    return;
                }

                const unscaledViewport = page.getViewport({ scale: 1 });
                const availableWidth = Math.max(pdfFrame.clientWidth - 2, 400);
                lastPdfFrameWidth = availableWidth;
                const fitScale = availableWidth / unscaledViewport.width;
                const viewport = page.getViewport({ scale: fitScale * currentZoom });

                const pageShell = document.createElement("div");
                pageShell.className = "pdf-page-shell";
                pageShell.dataset.pageNumber = String(pageNumber);
                pageShell.style.width = String(viewport.width) + "px";
                pageShell.style.height = String(viewport.height) + "px";

                const canvas = document.createElement("canvas");
                canvas.className = "pdf-page";
                canvas.width = viewport.width;
                canvas.height = viewport.height;
                canvas.style.width = String(viewport.width) + "px";
                canvas.style.height = String(viewport.height) + "px";
                pageShell.appendChild(canvas);

                const textLayerDiv = document.createElement("div");
                textLayerDiv.className = "textLayer";
                textLayerDiv.style.width = String(viewport.width) + "px";
                textLayerDiv.style.height = String(viewport.height) + "px";
                pageShell.appendChild(textLayerDiv);

                pdfFrame.appendChild(pageShell);

                const context = canvas.getContext("2d");
                await page.render({
                    canvasContext: context,
                    viewport
                }).promise;

                const textContent = await page.getTextContent();
                currentPdfPageTexts[pageNumber - 1] = textContent.items
                    .map((item) => ("str" in item ? item.str : ""))
                    .join(" ")
                    .replace(/\s+/g, " ")
                    .trim();

                const textLayer = new pdfjsLib.TextLayer({
                    textContentSource: textContent,
                    container: textLayerDiv,
                    viewport
                });
                await textLayer.render();
            }
        })
        .catch((error) => {
            if (renderToken !== currentRenderToken) {
                return;
            }
            pdfPanelMeta.textContent = error.message || "Failed to load PDF";
            pdfFrame.innerHTML = "<p class=\"meta\">Unable to render this PDF.</p>";
        });
    };

    const renderPaperCardPreview = (card) => {
        const summary = (card.dataset.paperSummary || "").trim();
        const query = parseStateSearchQuery(stateSearchQuery).textQuery;
        const abstractHtml = summary
            ? "<div class=\"paper-view-abstract\"><p>" + highlightedHtml(summary, query).replace(/\n+/g, "</p><p>") + "</p></div>"
            : "<p class=\"meta\">No abstract available for this paper.</p>";
        pdfFrame.innerHTML = abstractHtml + paperLinksHtml(card, true);
    };

    const populatePaperViewMeta = (card) => {
        const title = card.dataset.paperTitle || card.querySelector("h3")?.textContent || "Paper";
        const authors = card.dataset.paperAuthors || "Unknown authors";
        const publicationDate = card.dataset.paperPublishedOn || "unknown";
        const venue = card.dataset.paperVenue || "Unknown venue";
        const query = parseStateSearchQuery(stateSearchQuery).textQuery;
        pdfPanelTitle.innerHTML = highlightedHtml(title, query);
        pdfPanelMeta.innerHTML = ""
            + highlightedHtml(authors, query) + "<br>"
            + "Publication date: " + escapeHtml(publicationDate) + "<br>"
            + "Venue: " + highlightedHtml(venue, query);
    };

    const showPdfForCard = (card) => {
        if (isMobileViewport()) {
            focusPaperCard(card);
            return;
        }
        clearKeyboardStatePicker();
        allPaperCards().forEach((item) => item.classList.remove("active"));
        card.classList.add("active");
        selectedPaperId = card.dataset.paperId;
        markPaperViewed(selectedPaperId, card);
        if (currentTtsUrl) {
            URL.revokeObjectURL(currentTtsUrl);
            currentTtsUrl = null;
        }
        ttsPlayer.pause();
        ttsPlayer.removeAttribute("src");
        ttsPlayer.classList.add("hidden");

        const pdfUrl = card.dataset.pdfUrl;
        if (!pdfUrl) {
            closeViewer();
            return;
        }

        selectedPdfUrl = pdfUrl;
        selectedDownloadUrl = pdfUrl + "?disposition=attachment";
        selectedContentKind = "pdf";
        currentZoom = 1;

        contentPanelLabel.textContent = "";
        notesEditor.value = card.dataset.paperNotes || "";
        ensurePaperDetail(card);
        renderPaperTagEditor(card);
        notesStatus.textContent = "Saved";
        updateNotesEditorView();
        renderNotesPreview();
        leaveNotesEditMode();
        populatePaperViewMeta(card);
        applyLayoutState();
        renderPdf(pdfUrl);
    };

    const uploadPdfForPaper = async (paperId, file, card) => {
        if (!file || !file.name.toLowerCase().endsWith(".pdf")) {
            window.alert("Only PDF files are supported.");
            return;
        }

        const formData = new FormData();
        formData.append("pdf", file);
        card.classList.add("dragover");
        const shouldReturnToNoteView = selectedPaperId === String(paperId);

        try {
            const response = await fetch("/papers/" + paperId + "/pdf", {
                method: "POST",
                body: formData
            });
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Upload failed");
            }
            const currentCard = findCardByPaperId(paperId) || card;
            markCapturedPdfAvailable(paperId, "/papers/" + paperId + "/pdf");
            if (shouldReturnToNoteView) {
                showPdfForCard(currentCard);
                notesStatus.textContent = "PDF uploaded";
            }
        } catch (error) {
            window.alert(error.message || "Upload failed");
        } finally {
            card.classList.remove("dragover");
        }
    };

    const markPaperViewed = async (paperId, card) => {
        if (!paperId || !card || card.dataset.paperIsFresh !== "true") {
            return;
        }
        try {
            await fetch("/papers/" + paperId + "/viewed", {
                method: "POST"
            });
            card.dataset.paperIsFresh = "false";
            const freshBadge = card.querySelector(".fresh-badge");
            freshBadge?.remove();
            const badgeWrap = card.querySelector(".paper-status-badges");
            if (badgeWrap && !badgeWrap.children.length) {
                badgeWrap.remove();
            }
        } catch {
        }
    };

    const saveNotes = async () => {
        if (!selectedPaperId) {
            return;
        }

        notesStatus.textContent = "Saving...";

        try {
            const response = await fetch("/papers/" + selectedPaperId + "/notes", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: new URLSearchParams({ notes: notesEditor.value })
            });

            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Failed to save notes");
            }

            const activeCard = allPaperCards().find((card) => card.dataset.paperId === selectedPaperId);
            if (activeCard) {
                activeCard.dataset.paperNotes = notesEditor.value;
            }
            const record = paperRecordById(selectedPaperId);
            if (record) {
                record.paperNotes = notesEditor.value;
            }
            renderNotesPreview();
            notesStatus.textContent = "Saved";
        } catch (error) {
            notesStatus.textContent = error.message || "Failed to save notes";
        }
    };

    const queueNotesSave = () => {
        notesStatus.textContent = "Editing...";
        updateNotesEditorView();
        window.clearTimeout(notesSaveTimer);
        notesSaveTimer = window.setTimeout(saveNotes, 2000);
    };

    const insertTextAtCursor = (text) => {
        const start = notesEditor.selectionStart ?? notesEditor.value.length;
        const end = notesEditor.selectionEnd ?? notesEditor.value.length;
        notesEditor.setRangeText(text, start, end, "end");
        queueNotesSave();
    };

    const uploadPastedImage = async (file) => {
        if (!selectedPaperId) {
            return null;
        }

        const formData = new FormData();
        formData.append("image", file, file.name || "pasted-image.png");
        const response = await fetch("/papers/" + selectedPaperId + "/notes/images", {
            method: "POST",
            body: formData
        });

        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to upload image");
        }

        return response.text();
    };

    const findPageNumberFromNode = (node) => {
        if (!node) {
            return null;
        }
        const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
        const pageShell = element?.closest(".pdf-page-shell");
        return pageShell?.dataset.pageNumber || null;
    };

    const formatCopiedQuote = (text, startPage, endPage) => {
        const normalized = text
            .replace(/\r\n/g, "\n")
            .split("\n")
            .map((line) => line.trim())
            .filter((line, index, lines) => line.length > 0 || (index > 0 && index < lines.length - 1));

        const quoted = normalized.map((line) => "> " + line).join("\n");
        const pageLabel = startPage && endPage && startPage !== endPage
            ? "pp. " + startPage + "-" + endPage
            : "p. " + (startPage || "?");

        return quoted + "\n>\n> " + pageLabel;
    };

    const setSpeakingButtonState = (button, busy) => {
        if (!button) {
            return;
        }
        const label = button.querySelector("span");
        if (!button.dataset.defaultLabel) {
            button.dataset.defaultLabel = (label?.textContent || button.textContent).trim();
        }
        button.disabled = busy;
        if (label) {
            label.textContent = busy ? "Generating audio..." : button.dataset.defaultLabel;
        } else {
            button.textContent = busy ? "Generating audio..." : button.dataset.defaultLabel;
        }
    };

    const speakText = async (text, triggerButton = paperSpeakButton) => {
        const normalized = (text || "").replace(/\s+/g, " ").trim();
        if (!normalized) {
            window.alert("No readable text found.");
            return;
        }

        setSpeakingButtonState(triggerButton, true);
        if (triggerButton !== paperSpeakButton) {
            paperSpeakButton.disabled = true;
        }
        if (selectedPdfUrl) {
            pdfPanelMeta.textContent = "Generating speech...";
        } else {
            notesStatus.textContent = "Generating speech...";
        }

        try {
            const response = await fetch("/tts/speak", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: new URLSearchParams({ text: normalized })
            });
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || "Failed to generate speech");
            }

            const audioBlob = await response.blob();
            if (currentTtsUrl) {
                URL.revokeObjectURL(currentTtsUrl);
            }
            currentTtsUrl = URL.createObjectURL(audioBlob);
            ttsPlayer.src = currentTtsUrl;
            ttsPlayer.classList.remove("hidden");
            await ttsPlayer.play();
            const activeCard = findCardByPaperId(selectedPaperId);
            if (selectedPdfUrl) {
                pdfPanelMeta.textContent = activeCard?.dataset.logicalFeedName || "Speech ready";
            } else {
                notesStatus.textContent = "Speech ready";
            }
        } catch (error) {
            if (selectedPdfUrl) {
                pdfPanelMeta.textContent = error.message || "Failed to generate speech";
            } else {
                notesStatus.textContent = error.message || "Failed to generate speech";
            }
        } finally {
            setSpeakingButtonState(triggerButton, false);
            applyLayoutState();
        }
    };

    const selectedPdfText = () => {
        const selection = window.getSelection();
        if (!selection) {
            return lastPdfSelectionText;
        }
        const text = selection.toString().trim();
        const anchorInViewer = selection.anchorNode && pdfFrame.contains(selection.anchorNode);
        const focusInViewer = selection.focusNode && pdfFrame.contains(selection.focusNode);
        if (text && (anchorInViewer || focusInViewer)) {
            return text;
        }
        return lastPdfSelectionText;
    };

    const visiblePdfPageNumber = () => {
        const pageShells = Array.from(pdfFrame.querySelectorAll(".pdf-page-shell"));
        if (pageShells.length === 0) {
            return null;
        }

        const viewportCenter = pdfScroll.scrollTop + (pdfScroll.clientHeight / 2);
        let bestPage = null;
        let bestDistance = Number.POSITIVE_INFINITY;

        pageShells.forEach((pageShell) => {
            const pageCenter = pageShell.offsetTop + (pageShell.offsetHeight / 2);
            const distance = Math.abs(pageCenter - viewportCenter);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPage = Number.parseInt(pageShell.dataset.pageNumber || "", 10);
            }
        });

        return Number.isFinite(bestPage) ? bestPage : 1;
    };

    const currentPaperLink = async () => {
        if (!selectedPaperId) {
            return "";
        }
        const response = await fetch("/papers/" + selectedPaperId + "/share-link", {
            method: "POST"
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to create paper share link");
        }
        return response.text();
    };

    const closePaperActionsMenu = () => {
        if (paperActionsMenu) {
            paperActionsMenu.open = false;
        }
        if (paperPdfActionsMenu) {
            paperPdfActionsMenu.open = false;
        }
    };

    const rerenderSelectedPdf = () => {
        if (selectedContentKind === "pdf" && selectedPdfUrl) {
            renderPdf(selectedPdfUrl);
        }
    };

    const installPdfWidthObserver = () => {
        if (typeof ResizeObserver === "undefined") {
            return;
        }
        pdfResizeObserver = new ResizeObserver((entries) => {
            if (selectedContentKind !== "pdf" || !selectedPdfUrl) {
                return;
            }
            const width = Math.round(entries[0]?.contentRect?.width || 0);
            if (!width || Math.abs(width - lastPdfFrameWidth) < 8) {
                return;
            }
            lastPdfFrameWidth = width;
            queuePdfRefit();
        });
        pdfResizeObserver.observe(pdfFrame);
    };

    const removeSelectedPaperPdf = async () => {
        const card = selectedPaperCard();
        if (!card || !card.dataset.pdfUrl || !authenticated || card.dataset.paperCanEditTags !== "true") {
            return;
        }
        const response = await fetch("/papers/" + card.dataset.paperId + "/pdf/delete", {
            method: "POST"
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to remove PDF");
        }
        card.dataset.pdfUrl = "";
        const record = paperRecordById(card.dataset.paperId);
        if (record) {
            record.pdfUrl = "";
        }
        const pdfPresence = card.querySelector(".pdf-presence");
        if (pdfPresence) {
            pdfPresence.classList.remove("has-pdf");
            pdfPresence.title = "No PDF attached";
            pdfPresence.innerHTML = "<span aria-hidden=\"true\">○</span>";
        }
        selectedPdfUrl = null;
        selectedDownloadUrl = "";
        selectedContentKind = "card";
        currentZoom = 1;
        currentPdfPageTexts = [];
        contentPanelLabel.textContent = "";
        renderPaperCardPreview(card);
        populatePaperViewMeta(card);
        applyLayoutState();
    };

    const importSupportedSourcePdf = async () => {
        const card = selectedPaperCard();
        if (!card || !authenticated || card.dataset.paperCanEditTags !== "true") {
            return;
        }
        const response = await fetch("/papers/" + card.dataset.paperId + "/pdf/import-supported", {
            method: "POST"
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to import remote PDF");
        }
        card.dataset.pdfUrl = "/papers/" + card.dataset.paperId + "/pdf";
        const record = paperRecordById(card.dataset.paperId);
        if (record) {
            record.pdfUrl = "/papers/" + card.dataset.paperId + "/pdf";
        }
        const pdfPresence = card.querySelector(".pdf-presence");
        if (pdfPresence) {
            pdfPresence.classList.add("has-pdf");
            pdfPresence.title = "PDF attached";
            pdfPresence.innerHTML = "<span aria-hidden=\"true\">📄</span>";
        }
        showPdfForCard(card);
    };

    const stopPdfCapturePolling = () => {
        if (pdfCapturePollTimer) {
            window.clearTimeout(pdfCapturePollTimer);
            pdfCapturePollTimer = null;
        }
    };

    const rememberActivePdfCapture = () => {
        if (!activePdfCapture) {
            window.localStorage.removeItem(pdfCaptureStorageKey);
            return;
        }
        window.localStorage.setItem(pdfCaptureStorageKey, JSON.stringify({
            captureId: activePdfCapture.captureId,
            paperId: activePdfCapture.paperId,
            expiresAt: activePdfCapture.expiresAt
        }));
    };

    const markCapturedPdfAvailable = (paperId, pdfUrl) => {
        const card = findCardByPaperId(paperId);
        const normalizedPdfUrl = pdfUrl || "/papers/" + paperId + "/pdf";
        if (!card) {
            return;
        }
        card.dataset.pdfUrl = normalizedPdfUrl;
        const record = paperRecordById(paperId);
        if (record) {
            record.pdfUrl = normalizedPdfUrl;
        }
        const pdfPresence = card.querySelector(".pdf-presence");
        if (pdfPresence) {
            pdfPresence.classList.add("has-pdf");
            pdfPresence.title = "PDF attached";
            pdfPresence.innerHTML = "<span aria-hidden=\"true\">📄</span>";
        }
        if (selectedPaperId === String(paperId)) {
            selectedDownloadUrl = normalizedPdfUrl;
            applyLayoutState();
        }
    };

    const handlePdfCaptureStatus = (status) => {
        if (!activePdfCapture || String(status.captureId) !== String(activePdfCapture.captureId)) {
            return;
        }
        if (status.status === "UPLOADED") {
            stopPdfCapturePolling();
            markCapturedPdfAvailable(activePdfCapture.paperId, status.pdfUrl);
            notesStatus.textContent = "Provider PDF captured";
            paperCapturePdfLabel.textContent = "Capture provider PDF";
            activePdfCapture = null;
            rememberActivePdfCapture();
            return;
        }
        if (status.status === "FAILED" || status.status === "EXPIRED") {
            stopPdfCapturePolling();
            notesStatus.textContent = status.error || (status.status === "EXPIRED"
                ? "PDF capture expired"
                : "PDF capture failed");
            paperCapturePdfLabel.textContent = "Capture provider PDF";
            activePdfCapture = null;
            rememberActivePdfCapture();
            return;
        }
        notesStatus.textContent = status.message || (status.status === "UPLOADING"
            ? "Uploading captured PDF..."
            : "Capture armed. Download the PDF in the provider tab.");
    };

    const pollPdfCaptureStatus = async () => {
        if (!activePdfCapture) {
            return;
        }
        try {
            const response = await fetch(
                "/papers/" + activePdfCapture.paperId + "/pdf-captures/" + activePdfCapture.captureId,
                { headers: { Accept: "application/json" } }
            );
            if (response.ok) {
                handlePdfCaptureStatus(await response.json());
            } else if (response.status === 403 || response.status === 404) {
                stopPdfCapturePolling();
                activePdfCapture = null;
                rememberActivePdfCapture();
                notesStatus.textContent = "PDF capture is no longer available";
            }
        } catch (error) {
            console.warn("[paper-monitor] Could not poll PDF capture status", error);
        }
        if (activePdfCapture) {
            pdfCapturePollTimer = window.setTimeout(pollPdfCaptureStatus, 2000);
        }
    };

    const startProviderPdfCapture = async () => {
        const card = selectedPaperCard();
        if (!card || !canClassifyCard(card) || !providerPageUrl(card)) {
            return;
        }
        stopPdfCapturePolling();
        const response = await fetch("/papers/" + card.dataset.paperId + "/pdf-captures", {
            method: "POST",
            headers: { Accept: "application/json" }
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || "Failed to create PDF capture");
        }
        const capture = await response.json();
        if (!capture.providerUrl) {
            throw new Error("No provider page is available for this paper");
        }
        capture.uploadUrl = new URL(capture.uploadUrl, window.location.origin).href;
        activePdfCapture = capture;
        rememberActivePdfCapture();
        paperCapturePdfLabel.textContent = "Capture armed";
        notesStatus.textContent = pdfCaptureExtensionReady
            ? "Opening provider page..."
            : "Waiting for the MIAGE Review Factory Firefox extension...";
        window.postMessage({
            bridge: "paper-monitor-pdf-capture",
            type: "ARM_CAPTURE",
            capture
        }, window.location.origin);
        window.setTimeout(() => {
            if (activePdfCapture && !pdfCaptureExtensionReady) {
                notesStatus.textContent = "MIAGE Review Factory Firefox extension not detected";
            }
        }, 1500);
        pdfCapturePollTimer = window.setTimeout(pollPdfCaptureStatus, 1000);
    };

    const restoreActivePdfCapture = () => {
        if (!authenticated || activePdfCapture) {
            return;
        }
        try {
            const stored = JSON.parse(window.localStorage.getItem(pdfCaptureStorageKey) || "null");
            if (!stored || Date.parse(stored.expiresAt) <= Date.now()) {
                window.localStorage.removeItem(pdfCaptureStorageKey);
                return;
            }
            activePdfCapture = stored;
            notesStatus.textContent = "Checking provider PDF capture...";
            pollPdfCaptureStatus();
        } catch {
            window.localStorage.removeItem(pdfCaptureStorageKey);
        }
    };

    window.addEventListener("message", (event) => {
        if (event.source !== window || event.origin !== window.location.origin) {
            return;
        }
        const message = event.data;
        if (!message || message.bridge !== "paper-monitor-pdf-capture-extension") {
            return;
        }
        pdfCaptureExtensionReady = true;
        if (message.type === "READY") {
            return;
        }
        if (message.type === "CAPTURE_STATUS") {
            handlePdfCaptureStatus(message.status || {});
        }
    });

    allPaperCards().forEach(bindPaperCard);

    classificationLeftAction?.addEventListener("click", () => handleClassificationDirection("left"));
    classificationRightAction?.addEventListener("click", () => handleClassificationDirection("right"));
    classificationInfoAction?.addEventListener("click", openClassificationPaperViewer);
    classificationViewerReturn?.addEventListener("click", returnToClassification);

    classificationNotesToggle?.addEventListener("click", () => {
        setClassificationNotesExpanded(!classificationNotes.classList.contains("expanded"));
    });

    classificationNotesEditor?.addEventListener("input", () => {
        const card = selectedPaperCard();
        if (!card) {
            return;
        }
        classificationNoteDrafts.set(card.dataset.paperId, classificationNotesEditor.value);
        classificationNotesStatus.textContent = "Editing...";
        updateClassificationNotesIndicator();
        window.clearTimeout(classificationNotesSaveTimer);
        classificationNotesSaveTimer = window.setTimeout(() => {
            saveClassificationNotes(card).catch(() => undefined);
        }, 1200);
    });

    classificationCard?.addEventListener("pointerdown", (event) => {
        if (event.button !== 0 || classificationBusy || !selectedPaperId) {
            return;
        }
        if (event.target.closest("a, button, textarea, input, select")) {
            return;
        }
        const card = selectedPaperCard();
        if (!card) {
            return;
        }
        if (isMobileViewport()) {
            beginClassificationMobileLongPress(event, card);
            return;
        }
        classificationDragState = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            lastX: event.clientX,
            captured: false,
            card,
            multiTarget: classificationTargetCount(classificationDirectionalTargets(card)) > 2,
            radialTargets: [],
            radialDeadZoneRadius: CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS
        };
    });

    window.addEventListener("pointermove", (event) => {
        if (updateClassificationMobileLongPress(event)) {
            return;
        }
        if (!classificationDragState || event.pointerId !== classificationDragState.pointerId) {
            return;
        }
        const deltaX = event.clientX - classificationDragState.startX;
        const deltaY = event.clientY - classificationDragState.startY;
        if (!classificationDragState.captured) {
            if (Math.max(Math.abs(deltaX), Math.abs(deltaY)) < 8) {
                return;
            }
            if (!classificationDragState.multiTarget && Math.abs(deltaY) > Math.abs(deltaX)) {
                return;
            }
            classificationDragState.captured = true;
            classificationCard.setPointerCapture?.(event.pointerId);
            if (classificationDragState.multiTarget) {
                const radialLayout = showClassificationDropZones(classificationDragState.card);
                classificationDragState.radialTargets = radialLayout.targets;
                classificationDragState.radialDeadZoneRadius = radialLayout.deadZoneRadius;
                showMadProfessorFeedback("questions");
            }
        }
        event.preventDefault();
        classificationDragState.lastX = event.clientX;
        const progress = Math.min(1, Math.abs(deltaX) / CLASSIFICATION_DRAG_TRIGGER_X);
        const rotation = Math.max(-9, Math.min(9, deltaX / 22));
        const verticalOffset = classificationDragState.multiTarget
            ? deltaY
            : Math.max(-24, Math.min(24, deltaY * 0.16));
        classificationCard.classList.add("dragging");
        classificationCard.classList.toggle("dragging-left", deltaX < 0);
        classificationCard.classList.toggle("dragging-right", deltaX > 0);
        classificationCard.style.setProperty("--classification-drag-x", deltaX + "px");
        classificationCard.style.setProperty("--classification-drag-y", verticalOffset + "px");
        classificationCard.style.setProperty("--classification-drag-rotation", rotation + "deg");
        classificationCard.style.setProperty("--classification-drag-progress", String(progress));
        classificationDragState.dropTarget = classificationDragState.multiTarget
            ? classificationDropTargetForDrag(classificationDragState, deltaX, deltaY)
            : null;
    }, { passive: false });

    const finishClassificationPointerDrag = async (event, cancelled = false) => {
        if (!classificationDragState || event.pointerId !== classificationDragState.pointerId) {
            return;
        }
        const dragState = classificationDragState;
        classificationDragState = null;
        const deltaX = (event.clientX ?? dragState.lastX) - dragState.startX;
        const deltaY = (event.clientY ?? dragState.startY) - dragState.startY;
        const dragDistance = Math.hypot(deltaX, deltaY);
        const side = deltaX < 0 ? "left" : "right";
        const swipeTarget = classificationSwipeChoices(dragState.card)[side];
        const dropTarget = cancelled || !dragState.captured
            ? null
            : dragState.multiTarget
                ? classificationDropTargetForDrag(dragState, deltaX, deltaY) || dragState.dropTarget
                : null;
        if (classificationCard.hasPointerCapture?.(event.pointerId)) {
            classificationCard.releasePointerCapture(event.pointerId);
        }
        if (dropTarget?.target) {
            await performClassificationTransition(
                dragState.card,
                dropTarget.target,
                dropTarget.side,
                { preserveDragVisual: true }
            );
            return;
        }
        if (cancelled || !dragState.captured
                || (dragState.multiTarget
                    ? dragDistance < (dragState.radialDeadZoneRadius || CLASSIFICATION_RADIAL_DEAD_ZONE_RADIUS)
                    : Math.abs(deltaX) < CLASSIFICATION_DRAG_TRIGGER_X)) {
            resetClassificationDragVisual();
            return;
        }
        if (!swipeTarget) {
            resetClassificationDragVisual();
            revealClassificationChoices();
            return;
        }
        await performClassificationTransition(dragState.card, swipeTarget, side, { preserveDragVisual: true });
    };

    window.addEventListener("pointerup", async (event) => {
        if (await finishClassificationMobileLongPress(event)) {
            return;
        }
        await finishClassificationPointerDrag(event);
    });
    window.addEventListener("pointercancel", async (event) => {
        if (await finishClassificationMobileLongPress(event, true)) {
            return;
        }
        await finishClassificationPointerDrag(event, true);
    });

    window.addEventListener("wheel", (event) => {
        if (isMobileViewport() || !classificationModeActive || !classificationQueueMode
                || classificationBusy || event.deltaY <= 0 || !classificationView.contains(event.target)) {
            return;
        }
        if (event.target instanceof Element && event.target.closest("textarea, input, select")) {
            return;
        }
        event.preventDefault();
        const delta = event.deltaY * (event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 28
            : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? window.innerHeight : 1);
        classificationQueueWheelDelta += delta;
        window.clearTimeout(classificationQueueWheelTimer);
        classificationQueueWheelTimer = window.setTimeout(() => {
            classificationQueueWheelDelta = 0;
        }, 180);
        if (classificationQueueWheelDelta >= 80) {
            classificationQueueWheelDelta = 0;
            window.clearTimeout(classificationQueueWheelTimer);
            classificationQueueWheelTimer = null;
            void deferClassificationPaper();
        }
    }, { passive: false });

    window.addEventListener("mousemove", (event) => {
        updatePaperStatusDrag(event.clientX, event.clientY);
    });

    window.addEventListener("mouseup", async (event) => {
        if (event.button !== 0 || !cardDragState) {
            return;
        }
        await finishPaperStatusDrag(event.clientX);
    });

    window.addEventListener("touchmove", (event) => {
        if (!activeTouchStatusDrag || !cardDragState || event.touches.length !== 1) {
            return;
        }
        const touch = event.touches[0];
        const deltaX = touch.clientX - activeTouchStatusDrag.startX;
        const deltaY = touch.clientY - activeTouchStatusDrag.startY;
        if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 20) {
            activeTouchStatusDrag.captured = true;
            event.preventDefault();
        }
        if (activeTouchStatusDrag.captured) {
            updatePaperStatusDrag(touch.clientX, touch.clientY);
        }
    }, { passive: false });

    window.addEventListener("touchend", async (event) => {
        if (!activeTouchStatusDrag || !cardDragState) {
            activeTouchStatusDrag = null;
            return;
        }
        const touch = event.changedTouches?.[0];
        if (!touch) {
            activeTouchStatusDrag = null;
            return;
        }
        if (activeTouchStatusDrag.captured) {
            event.preventDefault();
        }
        activeTouchStatusDrag = null;
        await finishPaperStatusDrag(touch.clientX);
    }, { passive: false });

    window.addEventListener("touchcancel", () => {
        activeTouchStatusDrag = null;
        if (!cardDragState) {
            return;
        }
        hideStateWheels();
        resetCardDragVisual(cardDragState.visualTarget || cardDragState.card);
        cardDragState = null;
    });

    pdfPanel.addEventListener("mousedown", (event) => {
        if (event.button !== 0 || !selectedPaperId) {
            return;
        }
        if (event.target.closest("a, button, summary, input, textarea, select, audio, .paper-tag-editor, .inline-menu-panel")) {
            return;
        }
        if (event.target.closest(".pdf-scroll") || event.target.closest(".pdf-close-button")) {
            return;
        }
        const card = findCardByPaperId(selectedPaperId);
        if (!card) {
            return;
        }
        beginPaperStatusDrag(card, event, pdfPanel);
    });

    pdfPanel.addEventListener("touchstart", (event) => {
        if (!selectedPaperId || event.touches.length !== 1) {
            return;
        }
        if (event.target.closest("a, button, summary, input, textarea, select, audio, .paper-tag-editor, .inline-menu-panel")) {
            return;
        }
        if (event.target.closest(".pdf-scroll") || event.target.closest(".pdf-close-button")) {
            return;
        }
        const card = findCardByPaperId(selectedPaperId);
        if (!card) {
            return;
        }
        const touch = event.touches[0];
        activeTouchStatusDrag = {
            target: "pdf",
            paperId: selectedPaperId,
            startX: touch.clientX,
            startY: touch.clientY,
            captured: false
        };
        beginPaperStatusDragAtPoint(card, touch.clientX, touch.clientY, pdfPanel);
    }, { passive: true });

    notesEditor.addEventListener("input", () => {
        if (!authenticated) {
            return;
        }
        queueNotesSave();
    });

    notesEditor.addEventListener("scroll", () => {
        notesHighlight.scrollTop = notesEditor.scrollTop;
        notesHighlight.scrollLeft = notesEditor.scrollLeft;
    });

    notesEditor.addEventListener("paste", async (event) => {
        if (!authenticated) {
            return;
        }
        const items = Array.from(event.clipboardData?.items || []);
        const imageItem = items.find((item) => item.type.startsWith("image/"));
        if (!imageItem) {
            return;
        }

        event.preventDefault();
        const file = imageItem.getAsFile();
        if (!file) {
            return;
        }

        notesStatus.textContent = "Uploading image...";

        try {
            const assetPath = await uploadPastedImage(file);
            const markdown = "\n![](" + assetPath + ")\n";
            insertTextAtCursor(markdown);
        } catch (error) {
            notesStatus.textContent = error.message || "Failed to upload image";
        }
    });

    notesEditor.addEventListener("blur", () => {
        if (!authenticated) {
            return;
        }
        leaveNotesEditMode();
    });

    notesPreview.addEventListener("click", () => {
        if (!authenticated) {
            return;
        }
        enterNotesEditMode();
    });

    notesCollapseButton?.addEventListener("click", () => {
        setNotesCollapsed(true);
    });

    notesExpandButton?.addEventListener("click", () => {
        setNotesCollapsed(false);
    });

    if (paperTagAddButton) {
        paperTagAddButton.addEventListener("click", async () => {
            const card = selectedPaperCard();
            if (!card || !authenticated || card.dataset.paperCanEditTags !== "true") {
                return;
            }
            const nextTag = normalizeTagInput(paperTagInput.value || "");
            if (!nextTag) {
                return;
            }
            try {
                await savePaperTags(card, [...parsePaperTags(card), nextTag]);
            } catch (error) {
                pdfPanelMeta.textContent = error.message || "Failed to save tags";
            }
        });
    }

    if (paperTagInput) {
        paperTagInput.addEventListener("keydown", async (event) => {
            if (event.key !== "Enter") {
                return;
            }
            event.preventDefault();
            paperTagAddButton?.click();
        });
    }

    if (clearTagFiltersButton) {
        clearTagFiltersButton.addEventListener("click", () => {
            selectedTagFilters = [];
            applyLogicalFeedFilter();
        });
    }

    if (stateSearchInput) {
        stateSearchInput.addEventListener("input", () => {
            stateSearchQuery = stateSearchInput.value || "";
            if (searchDebugEnabled) {
                console.log("[paper-monitor] Search input changed", stateSearchQuery);
            }
            stateSearchVisibleLimit = 10;
            applyLogicalFeedFilter();
        });
    }

    if (searchLoadMoreButton) {
        searchLoadMoreButton.addEventListener("click", () => {
            if (serverRenderedShareMode) {
                stateSearchVisibleLimit += 10;
                applyLogicalFeedFilter();
            } else {
                loadNextBrowserPage().catch((error) => {
                    paperList.innerHTML = "<p class=\"meta\">" + escapeHtml(error.message || "Failed to load more papers.") + "</p>";
                    paperList.appendChild(searchLoadMoreRow);
                });
            }
        });
    }

    ["dragenter", "dragover"].forEach((eventName) => {
        notesPanel.addEventListener(eventName, (event) => {
            if (!authenticated) {
                return;
            }
            event.preventDefault();
            notesPanel.classList.add("dragover");
        });
    });

    ["dragenter", "dragover"].forEach((eventName) => {
        pdfPanel.addEventListener(eventName, (event) => {
            if (!authenticated || !selectedPaperId) {
                return;
            }
            event.preventDefault();
            pdfPanel.classList.add("dragover");
        });
    });

    ["dragleave", "dragend", "drop"].forEach((eventName) => {
        notesPanel.addEventListener(eventName, (event) => {
            if (!authenticated) {
                return;
            }
            event.preventDefault();
            notesPanel.classList.remove("dragover");
        });
    });

    ["dragleave", "dragend", "drop"].forEach((eventName) => {
        pdfPanel.addEventListener(eventName, (event) => {
            if (!authenticated || !selectedPaperId) {
                return;
            }
            event.preventDefault();
            pdfPanel.classList.remove("dragover");
        });
    });

    notesPanel.addEventListener("drop", (event) => {
        if (!authenticated || !selectedPaperId) {
            return;
        }
        const files = event.dataTransfer?.files;
        if (!files || files.length === 0) {
            return;
        }
        const card = findCardByPaperId(selectedPaperId);
        if (!card) {
            return;
        }
        uploadPdfForPaper(selectedPaperId, files[0], card);
    });

    pdfPanel.addEventListener("drop", (event) => {
        if (!authenticated || !selectedPaperId) {
            return;
        }
        const files = event.dataTransfer?.files;
        if (!files || files.length === 0) {
            return;
        }
        const card = findCardByPaperId(selectedPaperId);
        if (!card) {
            return;
        }
        uploadPdfForPaper(selectedPaperId, files[0], card);
    });

    paperSpeakButton.addEventListener("click", async () => {
        if (!selectedPaperId) {
            return;
        }
        closePaperActionsMenu();
        if (selectedPdfUrl) {
            const text = selectedPdfText();
            if (!text) {
                window.alert("Select text inside the PDF first.");
                return;
            }
            await speakText(text, paperSpeakButton);
            return;
        }

        const activeCard = findCardByPaperId(selectedPaperId);
        await speakText(activeCard?.dataset.paperSummary || "", paperSpeakButton);
    });

    paperSpeakButton.addEventListener("mousedown", (event) => {
        if (selectedPdfUrl) {
            event.preventDefault();
        }
    });

    paperAnalyzeButton.addEventListener("click", async () => {
        if (!selectedPaperId) return;
        closePaperActionsMenu();
        if (notesStatus.textContent === "Editing...") {
            window.clearTimeout(notesSaveTimer);
            await saveNotes();
        }
        const activeCard = findCardByPaperId(selectedPaperId);
        await paperAnalysisProgress.start({
            paperId: selectedPaperId,
            trigger: "paper-menu",
            paperTitle: activeCard?.dataset.paperTitle || "Selected paper"
        });
    });

    paperZoomOutButton.addEventListener("click", () => {
        if (!selectedPdfUrl) {
            return;
        }
        closePaperActionsMenu();
        currentZoom = Math.max(0.5, Math.round((currentZoom - 0.1) * 10) / 10);
        rerenderSelectedPdf();
    });

    paperZoomInButton.addEventListener("click", () => {
        if (!selectedPdfUrl) {
            return;
        }
        closePaperActionsMenu();
        currentZoom = Math.min(3, Math.round((currentZoom + 0.1) * 10) / 10);
        rerenderSelectedPdf();
    });

    paperDownloadButton.addEventListener("click", () => {
        if (!selectedPaperId) {
            return;
        }
        closePaperActionsMenu();
        const activeCard = findCardByPaperId(selectedPaperId);
        const downloadUrl = selectedDownloadUrl || activeCard?.dataset.pdfUrl || activeCard?.dataset.paperOpenAccessLink || activeCard?.dataset.paperSourceLink;
        if (!downloadUrl) {
            window.alert("No downloadable paper file is available.");
            return;
        }
        window.open(downloadUrl, "_blank", "noopener,noreferrer");
    });

    paperImportPdfButton.addEventListener("click", async () => {
        closePaperActionsMenu();
        try {
            await importSupportedSourcePdf();
        } catch (error) {
            pdfPanelMeta.textContent = error.message || "Failed to import remote PDF";
        }
    });

    paperCapturePdfButton.addEventListener("click", async () => {
        closePaperActionsMenu();
        try {
            await startProviderPdfCapture();
        } catch (error) {
            notesStatus.textContent = error.message || "Failed to arm PDF capture";
            paperCapturePdfLabel.textContent = "Capture provider PDF";
        }
    });

    paperRemovePdfButton.addEventListener("click", async () => {
        closePaperActionsMenu();
        if (!selectedPdfUrl || !selectedPaperId || !authenticated) {
            return;
        }
        try {
            await removeSelectedPaperPdf();
        } catch (error) {
            pdfPanelMeta.textContent = error.message || "Failed to remove PDF";
        }
    });

    paperShareButton.addEventListener("click", async () => {
        closePaperActionsMenu();
        try {
            const link = await currentPaperLink();
            if (!link) {
                return;
            }
            if (navigator.share) {
                await navigator.share({ url: link });
                return;
            }
            await navigator.clipboard.writeText(link);
            notesStatus.textContent = "Paper link copied";
        } catch {
            window.prompt("Copy this paper link:", link);
        }
    });

    pdfClose.addEventListener("click", () => {
        if (classificationViewerReturnPaperId) {
            returnToClassification();
            return;
        }
        closeViewer();
    });

    pdfFrame.addEventListener("copy", (event) => {
        const selection = window.getSelection();
        const text = selection ? selection.toString().trim() : "";
        if (!text) {
            return;
        }

        const anchorInViewer = selection.anchorNode && pdfFrame.contains(selection.anchorNode);
        const focusInViewer = selection.focusNode && pdfFrame.contains(selection.focusNode);
        if (!anchorInViewer && !focusInViewer) {
            return;
        }

        const startPage = findPageNumberFromNode(selection.anchorNode);
        const endPage = findPageNumberFromNode(selection.focusNode) || startPage;
        const markdownQuote = formatCopiedQuote(text, startPage, endPage);

        event.preventDefault();
        event.clipboardData.setData("text/plain", markdownQuote);
    });

    document.addEventListener("selectionchange", () => {
        const selection = window.getSelection();
        if (!selection) {
            return;
        }
        const text = selection.toString().trim();
        const anchorInViewer = selection.anchorNode && pdfFrame.contains(selection.anchorNode);
        const focusInViewer = selection.focusNode && pdfFrame.contains(selection.focusNode);
        if (text && (anchorInViewer || focusInViewer)) {
            lastPdfSelectionText = text;
        }
    });

    pdfScroll.addEventListener("mousedown", (event) => {
        if (event.button !== 1) {
            return;
        }

        event.preventDefault();
        handToolState = {
            startX: event.clientX,
            startY: event.clientY,
            scrollLeft: pdfScroll.scrollLeft,
            scrollTop: pdfScroll.scrollTop
        };
        pdfScroll.classList.add("hand-tool-active");
    });

    window.addEventListener("mousemove", (event) => {
        if (!handToolState) {
            return;
        }

        const deltaX = event.clientX - handToolState.startX;
        const deltaY = event.clientY - handToolState.startY;
        pdfScroll.scrollLeft = handToolState.scrollLeft - deltaX;
        pdfScroll.scrollTop = handToolState.scrollTop - deltaY;
    });

    window.addEventListener("mouseup", (event) => {
        if (event.button !== 1 || !handToolState) {
            return;
        }

        handToolState = null;
        pdfScroll.classList.remove("hand-tool-active");
    });

    pdfScroll.addEventListener("auxclick", (event) => {
        if (event.button === 1) {
            event.preventDefault();
        }
    });

    window.addEventListener("keydown", async (event) => {
        if (event.defaultPrevented) {
            return;
        }
        if (event.target instanceof HTMLElement) {
            const tagName = event.target.tagName;
            if (event.target.isContentEditable
                    || tagName === "TEXTAREA"
                    || tagName === "INPUT"
                    || tagName === "SELECT") {
                return;
            }
        }

        if (event.key === "Escape" && classificationViewerReturnPaperId) {
            event.preventDefault();
            returnToClassification();
            return;
        }

        if (event.key === "Escape" && (keyboardStatePicker || keyboardShortcutPicker || classificationModeActive)) {
            event.preventDefault();
            keyboardShortcutShiftHeld = false;
            clearKeyboardStatePicker();
            clearKeyboardShortcutPicker();
            if (classificationModeActive && !classificationModeTemporary && classificationQueueMode) {
                navigateToListMode(logicalFeedFilter?.value || "");
                return;
            }
            exitClassificationMode();
            return;
        }

        if (event.key === "Shift" && !event.repeat) {
            keyboardShortcutShiftHeld = true;
            if (enterClassificationMode({ temporary: true })) {
                startKeyboardShortcutPicker();
            } else {
                keyboardShortcutShiftHeld = false;
            }
            return;
        }

        if (keyboardShortcutPicker) {
            const shortcut = shortcutDigitFromEvent(event);
            if (shortcut) {
                event.preventDefault();
                const transition = keyboardShortcutPicker.transitions
                    .find((entry) => entry.shortcut === shortcut);
                if (!transition) {
                    return;
                }
                const { card, currentStatus } = keyboardShortcutPicker;
                const side = transition.side || (keyboardShortcutPicker.rightOptions.includes(transition) ? "right" : "left");
                clearKeyboardShortcutPicker();
                try {
                    await immediateKeyboardTransition(side, card, transition.value, currentStatus);
                } catch (error) {
                    window.alert(error.message || "Failed to update paper status");
                } finally {
                    if (keyboardShortcutShiftHeld) {
                        startKeyboardShortcutPicker();
                    }
                }
            }
            return;
        }

        if (event.key === "ArrowUp" && !keyboardStatePicker) {
            event.preventDefault();
            movePaperSelection(-1);
            return;
        }

        if (event.key === "ArrowDown" && !keyboardStatePicker) {
            event.preventDefault();
            movePaperSelection(1);
            return;
        }

        if (!selectedPaperId) {
            return;
        }

        if (event.key === "ArrowLeft") {
            event.preventDefault();
            if (await tryUndoKeyboardTransition("left")) {
                return;
            }
            if (keyboardStatePicker?.side === "right") {
                clearKeyboardStatePicker();
                return;
            }
            if (keyboardStatePicker?.side === "left") {
                const nextStatus = keyboardStatePicker.options[keyboardStatePicker.activeIndex]?.value;
                const card = keyboardStatePicker.card;
                const previousStatus = keyboardStatePicker.currentStatus;
                clearKeyboardStatePicker();
                try {
                    if (classificationModeActive) {
                        await performClassificationTransition(card, nextStatus, "left");
                    } else {
                        await applyPaperStatusTransition(card, nextStatus, previousStatus, { advanceFocus: true });
                    }
                } catch (error) {
                    window.alert(error.message || "Failed to update paper status");
                }
                return;
            }
            if (await maybeStartImmediateKeyboardTransition("left")) {
                return;
            }
            startKeyboardStatePicker("left");
            return;
        }

        if (event.key === "ArrowRight") {
            event.preventDefault();
            if (await tryUndoKeyboardTransition("right")) {
                return;
            }
            if (keyboardStatePicker?.side === "left") {
                clearKeyboardStatePicker();
                return;
            }
            if (keyboardStatePicker?.side === "right") {
                const nextStatus = keyboardStatePicker.options[keyboardStatePicker.activeIndex]?.value;
                const card = keyboardStatePicker.card;
                const previousStatus = keyboardStatePicker.currentStatus;
                clearKeyboardStatePicker();
                try {
                    if (classificationModeActive) {
                        await performClassificationTransition(card, nextStatus, "right");
                    } else {
                        await applyPaperStatusTransition(card, nextStatus, previousStatus, { advanceFocus: true });
                    }
                } catch (error) {
                    window.alert(error.message || "Failed to update paper status");
                }
                return;
            }
            if (await maybeStartImmediateKeyboardTransition("right")) {
                return;
            }
            startKeyboardStatePicker("right");
            return;
        }

        if (!keyboardStatePicker) {
            return;
        }

        if (event.key === "ArrowUp") {
            event.preventDefault();
            keyboardStatePicker.activeIndex = Math.max(0, keyboardStatePicker.activeIndex - 1);
            while (keyboardStatePicker.activeIndex > 0
                    && keyboardStatePicker.options[keyboardStatePicker.activeIndex]?.type !== "state") {
                keyboardStatePicker.activeIndex -= 1;
            }
            renderKeyboardStatePicker();
            return;
        }

        if (event.key === "ArrowDown") {
            event.preventDefault();
            keyboardStatePicker.activeIndex = Math.min(
                keyboardStatePicker.options.length - 1,
                keyboardStatePicker.activeIndex + 1
            );
            while (keyboardStatePicker.activeIndex < keyboardStatePicker.options.length - 1
                    && keyboardStatePicker.options[keyboardStatePicker.activeIndex]?.type !== "state") {
                keyboardStatePicker.activeIndex += 1;
            }
            renderKeyboardStatePicker();
            return;
        }
    });

    window.addEventListener("keyup", (event) => {
        if (event.key === "Shift") {
            keyboardShortcutShiftHeld = false;
            if (keyboardShortcutPicker) {
                clearKeyboardShortcutPicker();
            }
            exitClassificationMode();
        }
    });

    window.addEventListener("blur", () => {
        keyboardShortcutShiftHeld = false;
        if (keyboardShortcutPicker) {
            clearKeyboardShortcutPicker();
        }
        exitClassificationMode();
    });

    fullscreenToggle.addEventListener("click", () => {
        const fullBleed = document.body.classList.toggle("full-bleed");
        fullscreenToggleLabel.textContent = fullBleed ? "Centered width" : "Full width";
        queuePdfRefit();
    });

    window.addEventListener("resize", () => {
        applyNotesCollapsedState();
        queuePdfRefit();
        fitClassificationTypography();
    });

    themeToggle.addEventListener("click", () => {
        const nextTheme = document.body.classList.contains("dark-mode") ? "light" : "dark";
        window.localStorage.setItem(themeStorageKey, nextTheme);
        applyTheme(nextTheme);
    });

    Array.from(logicalFeedFilter?.options || []).forEach(refreshLogicalFeedOptionLabel);
    const openLogicalFeed = async (logicalFeedId, options = {}) => {
        if (!logicalFeedFilter) {
            return;
        }
        logicalFeedFilter.value = logicalFeedId ? String(logicalFeedId) : "";
        const selectedFeedId = syncSelectedLogicalFeed();
        selectedTagFilters = Array.isArray(options.tagFilters) ? [...options.tagFilters] : [];
        if (options.primaryTab) {
            activePrimaryTab = options.primaryTab;
        }
        if (!selectedFeedId && browserReaderSection && !shareMode) {
            browserReaderSection.classList.add("hidden");
            paperRecords = [];
            papersLoaded = false;
            closeViewer();
            renderPaginatedPaperList();
            renderFrontPagePanels();
            return;
        }
        if (!shareMode && selectedFeedId && String(loadedLogicalFeedId) !== String(selectedFeedId)) {
            try {
                await loadBrowserPapers(selectedFeedId);
                loadedLogicalFeedId = selectedFeedId;
            } catch (error) {
                papersLoaded = true;
                paperRecords = [];
                paperList.innerHTML = "<p class=\"meta\">" + escapeHtml(error.message || "Failed to load papers.") + "</p>";
                paperList.appendChild(searchLoadMoreRow);
            }
        }
        if (browserReaderSection && (!shareMode || selectedFeedId)) {
            browserReaderSection.classList.remove("hidden");
        }
        applyLogicalFeedFilter();
        if (options.scroll) {
            browserReaderSection?.scrollIntoView({ behavior: "smooth", block: "start" });
        }
    };

    const syncClassificationLink = () => {
        if (!classifyRssLink) {
            return;
        }
        const selectedFeedId = logicalFeedFilter?.value || "";
        classifyRssLink.href = selectedFeedId
            ? "/classify?logicalFeedId=" + encodeURIComponent(selectedFeedId)
            : "/classify";
    };

    const syncSelectedLogicalFeed = () => {
        selectedTagFilters = [];
        stateSearchVisibleLimit = 10;
        renderExportStateOptions();
        renderFrontPagePanels();
        syncClassificationLink();
        updateReaderModeControls();
        if (!doiImportLogicalFeed) {
            renderReviewLauncher();
            return logicalFeedFilter?.value || "";
        }
        const selectedFeedId = logicalFeedFilter.value;
        syncDoiImportLogicalFeed();
        renderReviewLauncher();
        return selectedFeedId;
    };
    syncClassificationLink();
    syncDoiImportLogicalFeed();
    updateReaderModeControls();
    readerSwipeMode?.addEventListener("click", () => {
        navigateToSwipeMode(logicalFeedFilter?.value || "");
    });
    classificationListMode?.addEventListener("click", () => {
        navigateToListMode(logicalFeedFilter?.value || "");
    });
    feedDashboard?.addEventListener("dragstart", (event) => {
        const card = event.target.closest("[data-feed-card]");
        if (!card || feedDashboardOrderSaving) {
            event.preventDefault();
            return;
        }
        draggedFeedDashboardCard = card;
        event.dataTransfer?.setData("text/plain", card.dataset.logicalFeedId || "");
        if (event.dataTransfer) {
            event.dataTransfer.effectAllowed = "move";
        }
        window.requestAnimationFrame(() => card.classList.add("dragging"));
    });
    feedDashboard?.addEventListener("dragover", (event) => {
        const card = event.target.closest("[data-feed-card]");
        if (!draggedFeedDashboardCard || !card || card === draggedFeedDashboardCard) {
            return;
        }
        event.preventDefault();
        if (event.dataTransfer) {
            event.dataTransfer.dropEffect = "move";
        }
        feedDashboardCards().forEach((candidate) => candidate.classList.toggle("drag-over", candidate === card));
    });
    feedDashboard?.addEventListener("dragleave", (event) => {
        const card = event.target.closest("[data-feed-card]");
        if (card && !card.contains(event.relatedTarget)) {
            card.classList.remove("drag-over");
        }
    });
    feedDashboard?.addEventListener("drop", (event) => {
        const target = event.target.closest("[data-feed-card]");
        const source = draggedFeedDashboardCard;
        if (!source || !target || source === target) {
            clearFeedDashboardDragState();
            return;
        }
        event.preventDefault();
        const previousOrder = feedDashboardOrder();
        const targetBounds = target.getBoundingClientRect();
        if (event.clientY < targetBounds.top + targetBounds.height / 2) {
            target.before(source);
        } else {
            target.after(source);
        }
        clearFeedDashboardDragState();
        void saveFeedDashboardOrder(previousOrder);
    });
    feedDashboard?.addEventListener("dragend", clearFeedDashboardDragState);
    feedDashboard?.querySelectorAll("[data-open-feed]").forEach((button) => {
        button.addEventListener("click", async () => {
            const logicalFeedId = button.dataset.openFeed;
            if (!logicalFeedFilter || !logicalFeedId) {
                return;
            }
            await openLogicalFeed(logicalFeedId, { scroll: true });
        });
    });
    feedDashboard?.querySelectorAll("[data-open-new-papers]").forEach((button) => {
        button.addEventListener("click", () => {
            const logicalFeedId = button.dataset.openNewPapers;
            if (!logicalFeedFilter || !logicalFeedId) {
                return;
            }
            window.location.href = "/classify?logicalFeedId=" + encodeURIComponent(logicalFeedId);
        });
    });
    feedDashboard?.querySelectorAll("[data-open-add-feed]").forEach((button) => {
        button.addEventListener("click", () => {
            const logicalFeedId = button.dataset.openAddFeed;
            const logicalFeedName = button.dataset.addFeedName || "";
            openFeedCreateModal(logicalFeedId, logicalFeedName);
        });
    });
    feedDashboard?.querySelectorAll("[data-delete-logical-feed]").forEach((button) => {
        button.addEventListener("click", () => openFeedDeleteModal(
            button.dataset.deleteLogicalFeed,
            button.dataset.deleteLogicalFeedName || ""));
    });
    feedCreateModalClose?.addEventListener("click", closeFeedCreateModal);
    feedCreateModal?.addEventListener("click", (event) => {
        if (event.target === feedCreateModal) {
            closeFeedCreateModal();
        }
    });
    feedDeleteModalClose?.addEventListener("click", closeFeedDeleteModal);
    feedDeleteCancel?.addEventListener("click", closeFeedDeleteModal);
    feedDeleteModal?.addEventListener("click", (event) => {
        if (event.target === feedDeleteModal) closeFeedDeleteModal();
    });
    feedDeleteExpectedName?.addEventListener("input", () => {
        if (feedDeleteSubmit) feedDeleteSubmit.disabled = feedDeleteExpectedName.value !== deletingLogicalFeedName;
    });
    readerOpenReviewButton?.addEventListener("click", () => {
        const review = reviewForLogicalFeed(logicalFeedFilter?.value || "");
        if (!review) {
            return;
        }
        exportTabMenu.open = false;
        window.location.href = "/reviews/" + encodeURIComponent(review.id);
    });
    readerImportPaperButton?.addEventListener("click", () => {
        exportTabMenu.open = false;
        openPaperImportModal();
    });
    readerOpenExportButton?.addEventListener("click", () => {
        exportTabMenu.open = false;
        openPaperExportModal();
    });
    readerBatchUpdateButton?.addEventListener("click", () => {
        exportTabMenu.open = false;
        openBatchPaperModal();
    });
    readerDiagramButton?.addEventListener("click", () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        if (!logicalFeedId) {
            window.alert("Select a paper feed first.");
            return;
        }
        exportTabMenu.open = false;
        window.open("/logical-feeds/" + encodeURIComponent(logicalFeedId) + "/diagram", "_blank", "noopener,noreferrer");
    });
    mendeleySyncMinimize?.addEventListener("click", () => {
        mendeleySyncMinimized = true;
        renderMendeleySyncProgress(mendeleySyncCurrentJob);
    });
    mendeleySyncModal?.addEventListener("cancel", (event) => {
        event.preventDefault();
        mendeleySyncMinimized = true;
        renderMendeleySyncProgress(mendeleySyncCurrentJob);
    });
    mendeleySyncWatchButton?.addEventListener("click", () => {
        mendeleySyncMinimized = false;
        renderMendeleySyncProgress(mendeleySyncCurrentJob);
    });
    mendeleySyncWatchClose?.addEventListener("click", (event) => {
        event.stopPropagation();
        if (!mendeleySyncCurrentJob || mendeleySyncCurrentJob.running) return;
        try {
            window.localStorage.setItem(mendeleySyncDismissedKey(mendeleySyncActiveFeedId),
                mendeleySyncJobIdentity(mendeleySyncCurrentJob));
        } catch (ignored) {
            // The server status remains authoritative when browser storage is unavailable.
        }
        hideMendeleySyncProgress();
        applyLogicalFeedFilter();
    });
    readerMendeleySyncButton?.addEventListener("click", async () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        if (!logicalFeedId || !selectedLogicalFeedCanAdmin()) {
            return;
        }
        exportTabMenu.open = false;
        readerMendeleySyncButton.disabled = true;
        stopMendeleySyncPolling();
        mendeleySyncActiveFeedId = String(logicalFeedId);
        mendeleySyncMinimized = false;
        try {
            window.localStorage.removeItem(mendeleySyncDismissedKey(logicalFeedId));
        } catch (ignored) {
            // Progress still works without browser storage.
        }
        renderMendeleySyncProgress({
            status: "QUEUED",
            phase: "Queueing Mendeley synchronization…",
            completed: 0,
            total: 0,
            running: true,
            startedAt: new Date().toISOString(),
            finishedAt: "",
            error: ""
        });
        try {
            const response = await fetch("/api/mendeley/feeds/" + encodeURIComponent(logicalFeedId) + "/sync", {
                method: "POST",
                headers: { Accept: "application/json" }
            });
            const body = await response.text();
            if (!response.ok) throw new Error(body || "Unable to start Mendeley synchronization.");
            renderMendeleySyncProgress(JSON.parse(body));
            pollMendeleySyncProgress();
        } catch (error) {
            const finishedAt = new Date().toISOString();
            renderMendeleySyncProgress({
                status: "FAILED",
                phase: "Synchronization could not be started",
                completed: 0,
                total: 0,
                running: false,
                startedAt: finishedAt,
                finishedAt,
                error: error.message || "Unable to start Mendeley synchronization."
            });
        } finally {
            applyLogicalFeedFilter();
        }
    });
    readerPublicUrlButton?.addEventListener("click", async () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        const selectedOption = selectedLogicalFeedOption();
        if (!logicalFeedId || !selectedOption) {
            window.alert("Select a paper feed first.");
            return;
        }
        if (!selectedLogicalFeedCanAdmin()) {
            window.alert("You need admin access on this paper feed to share it publicly.");
            return;
        }
        exportTabMenu.open = false;
        let publicUrl = selectedLogicalFeedPublicUrl();
        try {
            if (!selectedLogicalFeedIsPublic() || !publicUrl) {
                readerPublicUrlButton.disabled = true;
                const response = await fetch("/logical-feeds/" + encodeURIComponent(logicalFeedId) + "/public-url", {
                    method: "POST",
                    headers: { Accept: "text/plain" }
                });
                const body = (await response.text()).trim();
                if (!response.ok) {
                    throw new Error(body || "Failed to make the paper feed public.");
                }
                publicUrl = body;
                selectedOption.dataset.publicReadable = "true";
                selectedOption.dataset.publicUrl = publicUrl;
            }
            await copyTextWithFallback(publicUrl, "Copy this public paper feed URL:");
        } catch (error) {
            window.alert(error.message || "Failed to copy the public URL.");
        } finally {
            applyLogicalFeedFilter();
        }
    });
    paperImportModalClose?.addEventListener("click", closePaperImportModal);
    paperImportModal?.addEventListener("click", (event) => {
        if (event.target === paperImportModal) {
            closePaperImportModal();
        }
    });
    paperImportModal?.addEventListener("cancel", (event) => {
        event.preventDefault();
        closePaperImportModal();
    });
    paperImportDoiTab?.addEventListener("click", () => showPaperImportTab("doi"));
    paperImportWebTab?.addEventListener("click", () => showPaperImportTab("web"));
    [paperImportDoiTab, paperImportWebTab].forEach((tab, index, tabs) => {
        tab?.addEventListener("keydown", (event) => {
            let nextIndex = null;
            if (event.key === "ArrowLeft" || event.key === "ArrowUp") nextIndex = (index + tabs.length - 1) % tabs.length;
            if (event.key === "ArrowRight" || event.key === "ArrowDown") nextIndex = (index + 1) % tabs.length;
            if (event.key === "Home") nextIndex = 0;
            if (event.key === "End") nextIndex = tabs.length - 1;
            if (nextIndex == null) return;
            event.preventDefault();
            const nextTab = tabs[nextIndex];
            showPaperImportTab(nextTab === paperImportWebTab ? "web" : "doi", false);
            nextTab?.focus();
        });
    });
    paperExportModalClose?.addEventListener("click", closePaperExportModal);
    paperExportModal?.addEventListener("click", (event) => {
        if (event.target === paperExportModal) {
            closePaperExportModal();
        }
    });
    paperExportModal?.addEventListener("cancel", (event) => {
        event.preventDefault();
        closePaperExportModal();
    });
    paperExportSelectAll?.addEventListener("click", () => {
        exportStateList?.querySelectorAll("input[name='export-selected-state']")
            .forEach((input) => input.checked = true);
        updatePaperExportControls();
    });
    paperExportClear?.addEventListener("click", () => {
        exportStateList?.querySelectorAll("input[name='export-selected-state']")
            .forEach((input) => input.checked = false);
        updatePaperExportControls();
    });
    exportStateList?.addEventListener("change", updatePaperExportControls);
    urlImportForm?.addEventListener("submit", async (event) => {
        event.preventDefault();
        const logicalFeedId = logicalFeedFilter?.value || "";
        const status = urlImportStatus?.value || "";
        const requirementParams = await collectStateRequirementParams(logicalFeedId, status);
        if (!requirementParams) {
            return;
        }
        const params = new URLSearchParams(new FormData(urlImportForm));
        params.set("logicalFeedId", logicalFeedId);
        requirementParams.forEach((value, key) => {
            if (key !== "status") {
                params.append(key, value);
            }
        });
        urlImportSubmit.disabled = true;
        setUrlImportFeedback("Importing...");
        try {
            const response = await fetch("/papers/import-url", {
                method: "POST",
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: params
            });
            const body = await response.text();
            if (!response.ok) {
                throw new Error(body || "Failed to import the resource.");
            }
            paperRecords.push(JSON.parse(body));
            closePaperImportModal();
            applyLogicalFeedFilter();
        } catch (error) {
            setUrlImportFeedback(error.message || "Failed to import the resource.", true);
        } finally {
            urlImportSubmit.disabled = false;
        }
    });
    batchPaperModalClose?.addEventListener("click", closeBatchPaperModal);
    batchPaperModal?.addEventListener("click", (event) => {
        if (event.target === batchPaperModal) {
            closeBatchPaperModal();
        }
    });
    criteriaSelectionClose?.addEventListener("click", () => closeCriteriaSelection());
    criteriaSelectionCancel?.addEventListener("click", () => closeCriteriaSelection());
    criteriaSelectionModal?.addEventListener("cancel", (event) => {
        event.preventDefault();
        closeCriteriaSelection();
    });
    criteriaSelectionForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        const params = new URLSearchParams({ status: criteriaSelectionModal.dataset.nextStatus || "" });
        let valid = true;
        criteriaSelectionGroups.querySelectorAll("fieldset[data-criteria-kind]").forEach((fieldset) => {
            const selected = Array.from(fieldset.querySelectorAll("input:checked")).map((input) => input.value);
            const minimum = Number(fieldset.dataset.criteriaMin || "1");
            if (selected.length < minimum) {
                valid = false;
                return;
            }
            const key = fieldset.dataset.criteriaKind === "exclusion"
                ? "eligibilityExclusionCriterionIds"
                : "eligibilityInclusionCriterionIds";
            selected.forEach((value) => params.append(key, value));
        });
        if (!valid) {
            criteriaSelectionError.textContent = "Choose at least the required number of criteria in each section.";
            criteriaSelectionError.classList.remove("hidden");
            return;
        }
        const note = criteriaSelectionNotesInput?.value.trim() || "";
        if (note) {
            params.set("eligibilityCriteriaNotes", note);
        }
        closeCriteriaSelection(params);
    });
    batchPaperOperation?.addEventListener("change", () => {
        syncBatchPaperOperation();
        setBatchPaperFeedback("");
    });
    logicalFeedFilter?.addEventListener("change", async () => {
        await openLogicalFeed(logicalFeedFilter.value);
        await restoreMendeleySyncProgress(logicalFeedFilter.value);
    });
    syncManualImportControls();

    if (manualImportDoi) {
        manualImportDoi.addEventListener("input", () => {
            setManualImportStatus("");
            resetManualImportPreview();
            const { dois, isBatch } = syncManualImportControls();
            if (isBatch) {
                setManualImportStatus("Batch import ready for " + dois.length + " DOIs. PDF attachment is available only for single DOI imports.");
            }
        });
    }

    if (manualImportPdf) {
        manualImportPdf.addEventListener("change", updateManualImportFileLabel);
    }

    if (manualImportFileButton && manualImportPdf) {
        manualImportFileButton.addEventListener("click", () => manualImportPdf.click());
    }

    if (manualImportDropzone && manualImportPdf) {
        ["dragenter", "dragover"].forEach((eventName) => {
            manualImportDropzone.addEventListener(eventName, (event) => {
                event.preventDefault();
                manualImportDropzone.classList.add("drag-over");
            });
        });
        ["dragleave", "drop"].forEach((eventName) => {
            manualImportDropzone.addEventListener(eventName, (event) => {
                event.preventDefault();
                manualImportDropzone.classList.remove("drag-over");
            });
        });
        manualImportDropzone.addEventListener("drop", (event) => {
            const files = Array.from(event.dataTransfer?.files || []).filter((file) => file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf"));
            if (!files.length) {
                setManualImportStatus("Drop a PDF file.", true);
                return;
            }
            const transfer = new DataTransfer();
            transfer.items.add(files[0]);
            manualImportPdf.files = transfer.files;
            updateManualImportFileLabel();
            setManualImportStatus("");
        });
    }

    if (manualImportFetch && manualImportDoi) {
        manualImportFetch.addEventListener("click", async () => {
            if (!syncDoiImportLogicalFeed()) {
                setManualImportStatus("Select a paper feed first.", true);
                resetManualImportPreview();
                return;
            }
            const { dois, isBatch } = syncManualImportControls();
            if (!dois.length) {
                setManualImportStatus("Enter a DOI first.", true);
                resetManualImportPreview();
                return;
            }
            const doi = dois[0];
            manualImportFetch.disabled = true;
            resetManualImportPreview();
            if (isBatch) {
                setManualImportStatus("Previewing the first DOI. Submitting will import all " + dois.length + " DOIs without a PDF attachment.");
            } else {
                setManualImportStatus("Fetching metadata...");
            }
            try {
                const response = await fetch("/papers/import-doi/preview?doi=" + encodeURIComponent(doi), {
                    headers: { Accept: "application/json" }
                });
                const body = await response.text();
                if (!response.ok) {
                    throw new Error(body || "Failed to fetch DOI metadata.");
                }
                const metadata = JSON.parse(body);
                renderManualImportPreview(metadata);
                if (isBatch) {
                    setManualImportStatus("Preview loaded for the first DOI. Submitting will import all " + dois.length + " DOIs.");
                    return;
                }
                const fetchedPdf = await tryAutoFetchManualImportPdf(metadata);
                if (!fetchedPdf) {
                    setManualImportStatus(metadata.openAccessUrl
                        ? "Metadata loaded. Open access PDF could not be fetched automatically."
                        : "Metadata loaded.");
                }
            } catch (error) {
                setManualImportStatus(error.message || "Failed to fetch DOI metadata.", true);
                resetManualImportPreview();
            } finally {
                manualImportFetch.disabled = false;
            }
        });
    }

    if (manualImportForm) {
        manualImportForm.addEventListener("submit", (event) => {
            const { dois, isBatch } = syncManualImportControls();
            if (!syncDoiImportLogicalFeed()) {
                event.preventDefault();
                setManualImportStatus("Select a paper feed first.", true);
                return;
            }
            if (!dois.length) {
                event.preventDefault();
                setManualImportStatus("Enter at least one DOI.", true);
                return;
            }
            if (isBatch && manualImportPdf?.files?.length) {
                event.preventDefault();
                setManualImportStatus("Remove the PDF attachment before importing multiple DOIs.", true);
            }
        });
    }

    if (batchPaperForm) {
        batchPaperForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const logicalFeedId = logicalFeedFilter?.value || "";
            const records = currentBatchPaperRecords();
            const operation = batchPaperOperation?.value || "change_state";
            const status = batchPaperStatus?.value || "";
            const tag = normalizeTagInput(batchPaperTag?.value || "");
            if (!logicalFeedId) {
                setBatchPaperFeedback("Select a paper feed first.", true);
                return;
            }
            if ((shareMode ? records.length : browserTotal) === 0) {
                setBatchPaperFeedback("No papers match the current filters.", true);
                return;
            }
            if (operation === "change_state" && !status) {
                setBatchPaperFeedback("Select a target state.", true);
                return;
            }
            if (operation !== "change_state" && !tag) {
                setBatchPaperFeedback("Enter a tag first.", true);
                return;
            }

            const params = new URLSearchParams();
            params.set("logicalFeedId", logicalFeedId);
            params.set("operation", operation);
            params.set("selection", "filter");
            const browserParams = currentBrowserParameters(logicalFeedId);
            if (browserParams.get("status")) params.set("filterStatus", browserParams.get("status"));
            browserParams.getAll("tag").forEach((value) => params.append("filterTag", value));
            if (browserParams.get("search")) params.set("filterSearch", browserParams.get("search"));
            if (operation === "change_state") {
                params.set("status", status);
                const requirementParams = await collectStateRequirementParams(logicalFeedId, status);
                if (requirementParams == null) {
                    setBatchPaperFeedback("State change cancelled.", true);
                    return;
                }
                requirementParams.forEach((value, key) => {
                    if (key !== "status") {
                        params.append(key, value);
                    }
                });
            } else {
                params.set("tag", tag);
            }
            setBatchPaperFeedback("Applying batch update...");
            batchPaperApply.disabled = true;
            try {
                const response = await fetch("/papers/batch", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: params.toString()
                });
                const body = await response.text();
                if (!response.ok) {
                    throw new Error(body || "Failed to apply batch update.");
                }
                applyBatchOperationToRecords(records, operation, operation === "change_state" ? status : tag);
                closeBatchPaperModal();
                requestedBrowserFilterSignature = "";
                await reloadBrowserPapers();
            } catch (error) {
                setBatchPaperFeedback(error.message || "Failed to apply batch update.", true);
            } finally {
                batchPaperApply.disabled = false;
            }
        });
    }

    if (rqAddButton) {
        rqAddButton.addEventListener("click", () => {
            if (rqEditorList.children.length >= 50) {
                reviewMenuStatus.textContent = "A review can contain at most 50 research questions.";
                return;
            }
            rqEditorList.append(createRqRow());
            refreshRqOrdinals();
            rqEditorList.lastElementChild.querySelector(".rq-question").focus();
        });
    }

    reviewModeWizard?.addEventListener("click", startGuidedReviewWizard);
    reviewModePde?.addEventListener("click", startPdeReviewFlow);
    reviewWizardQuestionsBack?.addEventListener("click", prepareReviewWizard);

    reviewWizardQuestionsNext?.addEventListener("click", () => {
        if (!validateReviewQuestions()) return;
        setReviewWizardStep("states");
        reviewMenuStatus.textContent = "Choose the workflow states included in this review.";
    });
    reviewPdeBack?.addEventListener("click", prepareReviewWizard);
    reviewPdeRefresh?.addEventListener("click", async () => {
        reviewPdeRefresh.disabled = true;
        reviewMenuStatus.textContent = "Refreshing PDE review models...";
        try {
            await refreshReviewTemplates();
            reviewMenuStatus.textContent = reviewTemplates.length
                ? "PDE review models refreshed."
                : "No PDE review models are available. Create one in PDE, then refresh the list.";
        } catch (error) {
            reviewMenuStatus.textContent = error.message || "Could not load PDE review models.";
        } finally {
            reviewPdeRefresh.disabled = false;
        }
    });
    reviewPdeNext?.addEventListener("click", () => {
        if (!reviewTemplateSelect?.value) {
            reviewMenuStatus.textContent = "Select a PDE review model.";
            reviewTemplateSelect?.focus();
            return;
        }
        setReviewWizardStep("states");
        reviewMenuStatus.textContent = "Choose the workflow states included in this review.";
    });
    reviewWizardStatesBack?.addEventListener("click", () => {
        setReviewWizardStep(reviewCreationMode === "pde" ? "model" : "questions");
        reviewMenuStatus.textContent = reviewCreationMode === "pde"
            ? "Choose a PDE review model."
            : "Tailor the research questions, then choose the review scope.";
    });
    reviewWizardStatesNext?.addEventListener("click", () => {
        const questions = reviewCreationMode === "wizard" ? validateReviewQuestions() : [];
        const selectedStates = selectedReviewStates();
        if (!reviewCreationMode) {
            prepareReviewWizard();
            return;
        }
        if (reviewCreationMode === "pde" && !reviewTemplateSelect?.value) {
            setReviewWizardStep("model");
            reviewMenuStatus.textContent = "Select a PDE review model.";
            return;
        }
        if ((reviewCreationMode === "wizard" && !questions) || !selectedStates.length) {
            reviewMenuStatus.textContent = selectedStates.length
                ? reviewMenuStatus.textContent : "Select at least one state.";
            return;
        }
        if (reviewCreationMode === "pde") {
            const selectedModel = reviewTemplateSelect.selectedOptions?.[0]?.textContent || "the selected PDE model";
            reviewWizardSummary.textContent = reviewSchemeTitle() + " will use " + selectedModel
                + " and cover " + selectedStates.map(statusLabel).join(", ") + ".";
        } else {
            const changes = reviewQuestionsChanged(questions);
            reviewWizardSummary.textContent = reviewSchemeTitle() + " will cover "
                + selectedStates.map(statusLabel).join(", ") + ". "
                + (changes
                    ? "Your edited questions will be saved as a private version 1 review scheme."
                    : "The built-in five-question scheme will be used unchanged.");
        }
        setReviewWizardStep("confirm");
        reviewMenuStatus.textContent = "Check the review details, then create it.";
    });
    reviewWizardConfirmBack?.addEventListener("click", () => setReviewWizardStep("states"));

    if (reviewCreateForm) {
        reviewCreateForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const logicalFeedId = logicalFeedFilter.value;
            const questions = reviewCreationMode === "wizard" ? validateReviewQuestions() : [];
            const selectedStates = selectedReviewStates();
            if (!logicalFeedId) {
                reviewMenuStatus.textContent = "Select a paper feed first.";
                return;
            }
            if (!reviewCreationMode) {
                prepareReviewWizard();
                return;
            }
            if ((reviewCreationMode === "wizard" && !questions) || !selectedStates.length) {
                reviewMenuStatus.textContent = "Select at least one state.";
                return;
            }
            reviewMenuStatus.textContent = "Creating review...";
            try {
                let templateId = reviewCreationMode === "pde"
                    ? reviewTemplateSelect?.value
                    : builtInReviewTemplateId;
                if (!templateId) {
                    setReviewWizardStep("model");
                    reviewMenuStatus.textContent = "Select a PDE review model.";
                    return;
                }
                if (reviewCreationMode === "wizard" && reviewQuestionsChanged(questions)) {
                    reviewMenuStatus.textContent = "Saving your private review scheme...";
                    const designResponse = await fetch("/api/review-templates/" + builtInReviewTemplateId + "/derivations", {
                        method: "POST",
                        headers: {"Content-Type": "application/json", "Accept": "application/json"},
                        body: JSON.stringify({ title: reviewSchemeTitle(), research_questions: questions })
                    });
                    const designBody = await designResponse.text();
                    if (!designResponse.ok) throw new Error(designBody || "Failed to save the private review scheme.");
                    templateId = JSON.parse(designBody).id;
                }
                reviewMenuStatus.textContent = "Creating review...";
                const response = await fetch("/api/reviews", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                        "Accept": "application/json"
                    },
                    body: new URLSearchParams([
                        ["logicalFeedId", logicalFeedId],
                        ["templateId", templateId],
                        ...selectedStates.map((state) => ["selectedStates", state])
                    ])
                });
                const body = await response.text();
                if (!response.ok) {
                    throw new Error(body || "Failed to create review.");
                }
                const createdReview = JSON.parse(body);
                await loadReviewWorkspace();
                closeReviewCreateModal();
                window.location.href = "/reviews/" + createdReview.id;
            } catch (error) {
                reviewMenuStatus.textContent = error.message || "Failed to create review.";
            }
        });
    }

    if (reviewModalClose) {
        reviewModalClose.addEventListener("click", () => {
            closeReviewCreateModal();
        });
    }

    const syncClassificationQueueState = () => {
        if (!classificationQueueMode || !paperRecords.length) {
            return;
        }
        const firstRecord = paperRecords[0];
        const firstStatus = normalizeStatus(firstRecord.paperStatus);
        activeStatusTab = firstRecord.paperTopStatus || (firstStatus.includes("/") ? firstStatus.split("/")[0] : firstStatus);
        activeChildStatus = classificationQueueStates.length === 1 && firstStatus !== activeStatusTab
            ? firstStatus
            : null;
        activePrimaryTab = "state";
        stateSearchQuery = "";
        selectedTagFilters = [];
    };

    const currentBrowserParameters = (logicalFeedId, cursor = null) => {
        if (activePrimaryTab === "state") syncActiveStateSelection();
        const params = new URLSearchParams({ logicalFeedId, limit: "30" });
        params.set("mode", activePrimaryTab === "tags" ? "tags" : "state");
        if (classificationQueueMode) {
            params.set("classificationQueue", "true");
            classificationQueueStates.forEach((state) => params.append("state", state));
        } else if (activePrimaryTab === "state") {
            const status = activeChildStatus || activeStatusTab;
            if (status) params.set("status", status);
        } else {
            selectedTagFilters.forEach((tag) => params.append("tag", tag));
        }
        if ((stateSearchQuery || "").trim()) params.set("search", stateSearchQuery.trim());
        if (cursor) params.set("cursor", cursor);
        return params;
    };

    const currentBrowserFilterSignature = (logicalFeedId) => {
        const params = currentBrowserParameters(logicalFeedId);
        params.delete("limit");
        return params.toString();
    };

    const loadBrowserPapers = async (logicalFeedId, { append = false } = {}) => {
        if (serverRenderedShareMode || (!canEdit && !sharedFeedToken)) {
            return;
        }
        if (!logicalFeedId) {
            paperRecords = [];
            papersLoaded = false;
            browserNextCursor = null;
            browserTotal = 0;
            browserFacets = [];
            return;
        }
        if (append && browserRequestInFlight) return;
        const cursor = append ? browserNextCursor : null;
        if (append && !cursor) return;
        const generation = append ? browserRequestGeneration : ++browserRequestGeneration;
        const signature = currentBrowserFilterSignature(logicalFeedId);
        browserRequestInFlight = true;
        try {
            const browserUrl = sharedFeedToken
                ? "/api/share/feed/" + encodeURIComponent(sharedFeedToken) + "/papers/browser"
                : "/api/papers/browser";
            const response = await fetch(browserUrl + "?" + currentBrowserParameters(logicalFeedId, cursor).toString(), {
                headers: { Accept: "application/json" }
            });
            const body = await response.text();
            if (!response.ok) throw new Error(body || "Failed to load papers.");
            if (generation !== browserRequestGeneration || signature !== currentBrowserFilterSignature(logicalFeedId)) return;
            const page = JSON.parse(body);
            const incoming = Array.isArray(page.items) ? page.items : [];
            if (append) {
                const known = new Set(paperRecords.map((record) => String(record.id)));
                paperRecords.push(...incoming.filter((record) => !known.has(String(record.id))));
            } else {
                paperRecords = incoming;
                browserTotal = Number(page.total || 0);
                browserFacets = Array.isArray(page.facets) ? page.facets : [];
                loadedBrowserFilterSignature = signature;
                requestedBrowserFilterSignature = signature;
            }
            browserNextCursor = page.nextCursor || null;
            papersLoaded = true;
            if (classificationQueueMode && !append) {
                classificationQueueInitialTotal = browserTotal;
                classificationQueueCompletedPaperIds.clear();
            }
            syncClassificationQueueState();
            const firstRecord = paperRecords[0];
            const feedName = firstRecord?.logicalFeedName || logicalFeedFilter?.selectedOptions?.[0]?.dataset?.feedName;
            if (feedName) {
                window.localStorage.setItem(storageKey, feedName);
                renderLastSeenLogicalFeed(feedName);
            }
        } finally {
            if (generation === browserRequestGeneration) browserRequestInFlight = false;
        }
    };

    const reloadBrowserPapers = async () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        if (!logicalFeedId || serverRenderedShareMode) return;
        paperRecords = [];
        browserNextCursor = null;
        papersLoaded = false;
        await loadBrowserPapers(logicalFeedId);
        applyLogicalFeedFilter();
    };

    const scheduleBrowserReload = (delay = 0) => {
        window.clearTimeout(browserFilterDebounceTimer);
        browserFilterDebounceTimer = window.setTimeout(() => {
            reloadBrowserPapers().catch((error) => {
                papersLoaded = true;
                requestedBrowserFilterSignature = loadedBrowserFilterSignature;
                paperList.innerHTML = "<p class=\"meta\">" + escapeHtml(error.message || "Failed to load papers.") + "</p>";
                paperList.appendChild(searchLoadMoreRow);
            });
        }, delay);
    };

    const loadNextBrowserPage = async () => {
        const logicalFeedId = logicalFeedFilter?.value || "";
        if (!logicalFeedId || !browserNextCursor || browserRequestInFlight) return;
        await loadBrowserPapers(logicalFeedId, { append: true });
        applyLogicalFeedFilter();
        window.requestAnimationFrame(maybeLoadMoreVisiblePapers);
    };

    installPdfWidthObserver();

    const triggerTabExport = (kind, format) => {
        const logicalFeedId = logicalFeedFilter.value;
        if (!logicalFeedId) {
            setPaperExportError("Select a paper feed before exporting.");
            return;
        }
        const statuses = selectedExportStates();
        if (!statuses.length) {
            setPaperExportError("Select at least one state to export.");
            exportStateList?.querySelector("input")?.focus();
            return;
        }
        const params = new URLSearchParams({ logicalFeedId, kind, format });
        params.set("statuses", statuses.join(","));
        if (selectedTagFilters.length) {
            params.set("tags", selectedTagFilters.join(","));
        }
        closePaperExportModal();
        window.location.href = "/exports/tab?" + params.toString();
    };

    exportFormatButtons.forEach((button) => {
        button.addEventListener("click", () => {
            triggerTabExport(button.dataset.kind || "report", button.dataset.format || "md");
        });
    });

    exportTabButton.addEventListener("click", (event) => {
        if (exportTabButton.classList.contains("disabled")) {
            event.preventDefault();
        }
    });
    window.addEventListener("scroll", () => {
        maybeLoadMoreVisiblePapers();
    }, { passive: true });
    if ("IntersectionObserver" in window && searchLoadMoreRow) {
        new IntersectionObserver((entries) => {
            if (entries.some((entry) => entry.isIntersecting)) {
                loadNextBrowserPage().catch(() => {});
            }
        }, { rootMargin: "600px 0px" }).observe(searchLoadMoreRow);
    }
    loadReviewWorkspace();
    renderFrontPagePanels();
    let browserPapersReady = serverRenderedShareMode;
    if (!serverRenderedShareMode) {
        const requestedLogicalFeedId = readerBootstrap?.dataset.initialLogicalFeedId || logicalFeedFilter.value;
        if (requestedLogicalFeedId) {
            logicalFeedFilter.value = requestedLogicalFeedId;
            syncSelectedLogicalFeed();
            try {
                await loadBrowserPapers(requestedLogicalFeedId);
                loadedLogicalFeedId = requestedLogicalFeedId;
                browserPapersReady = true;
                browserReaderSection?.classList.remove("hidden");
            } catch (error) {
                papersLoaded = true;
                paperList.innerHTML = "<p class=\"meta\">" + escapeHtml(error.message || "Failed to load papers.") + "</p>";
                paperList.appendChild(searchLoadMoreRow);
            }
        } else {
            paperRecords = [];
            papersLoaded = false;
            renderPaginatedPaperList();
        }
    }
    if (browserPapersReady) {
        applyLogicalFeedFilter();
    }
    applyLayoutState();
    if (browserPapersReady) {
        if (startClassificationMode) {
            if (enterClassificationMode()) {
                startKeyboardShortcutPicker();
            } else if (classificationQueueMode) {
                showEmptyClassificationMode();
            }
        } else {
            openInitialPaper();
        }
        maybeLoadMoreVisiblePapers();
    }
    restoreActivePdfCapture();
    await restoreMendeleySyncProgress(logicalFeedFilter?.value || "");
    await paperAnalysisProgress.restore(selectedPaperId);
