let currentReviewDesign = null;
let formSchema = null;
let reviewLinkmlSchema = null;
let reviewJsonSchema = null;
let designerBaselineSnapshot = "";
let designerDirty = false;
let lastDesignerPickerValue = "";
let workspaceModels = [];
let selectedReviewModelIds = [];
let reviewPreviewRequestId = 0;
let extractedTaxonomyDraft = null;

const allModels = document.querySelector("#all-models");
const reviewDesigns = document.querySelector("#review-designs");
const modelStatus = document.querySelector("#model-status");
const openSaveReviewTemplateButton = document.querySelector("#open-save-review-template");
const selectedModelDropbox = document.querySelector("#selected-model-dropbox");
const downloadTaxonomyButton = document.querySelector("#download-taxonomy");
const downloadReviewHtmlButton = document.querySelector("#download-review-html");
const downloadReviewLinkmlSchemaButton = document.querySelector("#download-review-linkml-schema");
const downloadReviewJsonSchemaButton = document.querySelector("#download-review-json-schema");
const downloadReviewRdfButton = document.querySelector("#download-review-rdf");
const downloadReviewShaclButton = document.querySelector("#download-review-shacl");
const downloadReviewOwlButton = document.querySelector("#download-review-owl");
const uploadForm = document.querySelector("#custom-upload-form");
const importModelModalElement = document.querySelector("#import-model-modal");
const importModelModal = importModelModalElement ? bootstrap.Modal.getOrCreateInstance(importModelModalElement) : null;
const customModelFileInput = document.querySelector("#custom-model-file");
const saveReviewTemplateModalElement = document.querySelector("#save-review-template-modal");
const saveReviewTemplateModal = saveReviewTemplateModalElement ? bootstrap.Modal.getOrCreateInstance(saveReviewTemplateModalElement) : null;
const saveReviewTemplateForm = document.querySelector("#save-review-template-form");
const saveReviewTemplateTitleInput = document.querySelector("#save-review-template-title");
const dynamicFields = document.querySelector("#dynamic-fields");
const composedModelId = document.querySelector("#composed-model-id");
const composedModelTitles = document.querySelector("#composed-model-titles");
const tabButtons = document.querySelectorAll(".tab-button");
const appViews = document.querySelectorAll(".app-view");
const designerForm = document.querySelector("#designer-form");
const designerStatus = document.querySelector("#designer-status");
const saveDesignedModelButton = document.querySelector("#save-designed-model");
const clearDesignedModelButton = document.querySelector("#clear-designed-model");
const sourceCitationKeyInput = document.querySelector("#source-citation-key");
const sourceDetails = document.querySelector("#source-details");
const scaleList = document.querySelector("#scale-list");
const dimensionList = document.querySelector("#dimension-list");
const ruleList = document.querySelector("#rule-list");
const addScaleButton = document.querySelector("#add-scale");
const addDimensionSecondaryButton = document.querySelector("#add-dimension-secondary");
const addRuleButton = document.querySelector("#add-rule");
const designerModelPicker = document.querySelector("#designer-model-picker");
const downloadDataExtractionMetamodelButton = document.querySelector("#download-data-extraction-metamodel");
const downloadDataExtractionMetamodelJsonSchemaButton = document.querySelector("#download-data-extraction-metamodel-json-schema");
const downloadReviewDesignMetamodelButton = document.querySelector("#download-review-design-metamodel");
const downloadReviewDesignMetamodelJsonSchemaButton = document.querySelector("#download-review-design-metamodel-json-schema");
const extractModelForm = document.querySelector("#extract-model-form");
const extractModelFileInput = document.querySelector("#extract-model-file");
const extractModelDropzone = document.querySelector("#extract-model-dropzone");
const extractModelFileLabel = document.querySelector("#extract-model-file-label");
const extractModelPrompt = document.querySelector("#extract-model-prompt");
const extractModelStatus = document.querySelector("#extract-model-status");
const extractModelSubmitButton = document.querySelector("#extract-model-submit");
const extractModelValidation = document.querySelector("#extract-model-validation");
const extractModelTitle = document.querySelector("#extract-model-title");
const extractModelSaveButton = document.querySelector("#extract-model-save");
const extractModelLoadButton = document.querySelector("#extract-model-load");
const extractModelFormPreview = document.querySelector("#extract-model-form-preview");
const extractModelYaml = document.querySelector("#extract-model-yaml");
const extractModelErrors = document.querySelector("#extract-model-errors");
const extractModelProgress = document.querySelector("#extract-model-progress");

async function api(path, options = {}) {
    const response = await fetch(path, options);
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.detail || response.statusText);
    }
    if (response.status === 204) {
        return null;
    }
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("application/json")) {
        return null;
    }
    return response.json();
}

async function loadWorkspace() {
    modelStatus.textContent = "Loading workspace...";
    const [models, designs] = await Promise.all([
        api("/api/data-extraction-models"),
        api("/api/review-designs")
    ]);
    workspaceModels = models;
    const availableIds = new Set(models.map((model) => model.id));
    selectedReviewModelIds = selectedReviewModelIds.filter((id) => availableIds.has(id));
    renderModelWorkspace(models);
    renderReviewDesignList(reviewDesigns, designs);
    populateDesignerPicker(models);
    modelStatus.textContent = `${models.length} Data Extraction Model(s), ${designs.length} Review Template(s) available.`;
}

function renderModelWorkspace(models) {
    const selectedSet = new Set(selectedReviewModelIds);
    renderModelGrid(allModels, models.filter((model) => !selectedSet.has(model.id)), false);
    renderModelGrid(selectedModelDropbox, models.filter((model) => selectedSet.has(model.id)), true);
    initializeDragSurface(allModels, "available");
    initializeDragSurface(selectedModelDropbox, "selected");
    initializeTooltips();
}

function renderModelGrid(container, models, selected) {
    container.innerHTML = "";
    container.classList.toggle("empty-state", models.length === 0);

    if (models.length === 0) {
        container.textContent = selected
            ? "Drag Data Extraction Model cards here to build the review design preview."
            : "No unselected Data Extraction Models available.";
        return;
    }

    for (const model of models) {
        container.append(renderDemCard(model, selected));
    }
}

function renderDemCard(model, selected) {
    const item = document.createElement("article");
    const tint = modelTint(model.title || model.id);
    item.className = `model-option dem-card card border shadow-sm${selected ? " is-selected" : ""}`;
    item.draggable = true;
    item.dataset.modelId = model.id;
    item.dataset.source = model.source;
    item.style.backgroundColor = tint.background;
    item.style.borderColor = tint.border;
    if (model.preview_text) {
        item.setAttribute("data-bs-toggle", "tooltip");
        item.setAttribute("data-bs-title", model.preview_text);
        item.setAttribute("data-bs-placement", "top");
    }
    item.innerHTML = `
        <div class="card-body d-grid">
            <div class="dem-card-header">
                <div class="dem-card-label">
                    <span class="dem-card-grip" aria-hidden="true">⋮⋮</span>
                    <div class="dem-card-meta">
                        <strong>${escapeHtml(model.title)}</strong>
                        <span>${escapeHtml(model.id)} · ${model.dimension_count} dimension(s)</span>
                    </div>
                </div>
                <span class="dem-card-source">${escapeHtml(model.source)}</span>
            </div>
            <div class="dem-card-summary">${escapeHtml(model.preview_text || "Drag to include in the current review design preview.")}</div>
            <div class="model-option-actions d-flex flex-wrap gap-2">
                <button class="btn btn-outline-secondary btn-sm" type="button" data-action="load-model">Load</button>
                <button class="btn btn-outline-danger btn-sm" type="button" data-action="delete-model">Delete</button>
            </div>
        </div>
    `;
    item.addEventListener("dragstart", (event) => {
        event.dataTransfer?.setData("text/plain", model.id);
        event.dataTransfer?.setData("application/x-dem-source", selected ? "selected" : "available");
        item.classList.add("opacity-50");
    });
    item.addEventListener("dragend", () => item.classList.remove("opacity-50"));
    item.querySelector("[data-action='load-model']").addEventListener("click", async () => {
        if (!await confirmDiscardDesignerChanges()) {
            return;
        }
        loadModelIntoDesigner(model.source, model.id);
    });
    item.querySelector("[data-action='delete-model']").addEventListener("click", () => removeDataExtractionModel(model.source, model.id));
    return item;
}

function renderReviewDesignList(container, designs) {
    container.innerHTML = "";
    if (designs.length === 0) {
        container.innerHTML = "<p class=\"status-line small mb-0\">No Review Templates yet.</p>";
        return;
    }
    for (const design of designs) {
        const item = document.createElement("div");
        item.className = "saved-review-item";
        item.innerHTML = `
            <button class="saved-review-name" type="button" data-action="open-review-design" title="${escapeHtml(design.title)}">
                ${escapeHtml(design.title)}
            </button>
            <div class="model-option-actions d-flex flex-wrap gap-2 justify-content-end">
                <button class="btn btn-outline-danger btn-sm" type="button" data-action="delete-review-design">Delete</button>
            </div>
        `;
        item.querySelector("[data-action='open-review-design']").addEventListener("click", () => openReviewDesign(design.id));
        item.querySelector("[data-action='delete-review-design']").addEventListener("click", () => removeReviewDesign(design.id));
        container.append(item);
    }
}

function selectedModelIds() {
    return [...selectedReviewModelIds];
}

function selectedModelTitles() {
    const byId = new Map(workspaceModels.map((model) => [model.id, model.title]));
    return selectedReviewModelIds.map((id) => byId.get(id)).filter(Boolean);
}

function populateDesignerPicker(models) {
    designerModelPicker.innerHTML = "<option value=\"\">Load model...</option>";
    for (const model of models) {
        const option = document.createElement("option");
        option.value = `${model.source}:${model.id}`;
        option.textContent = `${model.title} (${model.id})`;
        designerModelPicker.append(option);
    }
}

openSaveReviewTemplateButton.addEventListener("click", () => {
    const modelIds = selectedModelIds();
    if (modelIds.length === 0) {
        modelStatus.textContent = "Select at least one Data Extraction Model.";
        return;
    }
    saveReviewTemplateTitleInput.value = String(currentReviewDesign?.title || buildDraftReviewDesignTitle(modelIds)).trim();
    saveReviewTemplateModal?.show();
});

saveReviewTemplateForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        const title = String(saveReviewTemplateTitleInput.value || "").trim();
        if (!title) {
            modelStatus.textContent = "Enter a Review Template name.";
            return;
        }
        const modelIds = selectedModelIds();
        if (modelIds.length === 0) {
            modelStatus.textContent = "Select at least one Data Extraction Model.";
            return;
        }
        const response = await api("/api/review-designs", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({title, model_ids: modelIds})
        });
        applyReviewDesignPreview(response, {saved: true});
        await loadWorkspace();
        saveReviewTemplateModal?.hide();
        modelStatus.textContent = "Review Template saved.";
    } catch (error) {
        modelStatus.textContent = error.message;
    }
});

downloadTaxonomyButton.addEventListener("click", () => {
    if (!currentReviewDesign) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "yaml", true);
});

downloadReviewHtmlButton.addEventListener("click", () => {
    if (!currentReviewDesign) {
        return;
    }
    window.open(reviewArtifactUrl(currentReviewDesign.id, "html"), "_blank", "noopener");
});

downloadReviewLinkmlSchemaButton.addEventListener("click", () => {
    if (!currentReviewDesign || !reviewLinkmlSchema) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "linkml", true);
});

downloadReviewJsonSchemaButton.addEventListener("click", () => {
    if (!currentReviewDesign || !reviewJsonSchema) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "json-schema", true);
});

downloadReviewRdfButton.addEventListener("click", () => {
    if (!currentReviewDesign) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "rdf", true);
});

downloadReviewShaclButton.addEventListener("click", () => {
    if (!currentReviewDesign) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "shacl", true);
});

downloadReviewOwlButton.addEventListener("click", () => {
    if (!currentReviewDesign) {
        return;
    }
    window.location.href = reviewArtifactUrl(currentReviewDesign.id, "owl", true);
});

uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = customModelFileInput.files[0];
    if (!file) {
        modelStatus.textContent = "Choose a Data Extraction Model YAML file first.";
        return;
    }
    const body = new FormData();
    body.append("file", file);
    try {
        await api("/api/models/custom", {method: "POST", body});
        await loadWorkspace();
        modelStatus.textContent = "Data Extraction Model uploaded.";
        customModelFileInput.value = "";
        importModelModal?.hide();
    } catch (error) {
        modelStatus.textContent = error.message;
    }
});

extractModelFileInput?.addEventListener("change", () => {
    const file = extractModelFileInput.files?.[0];
    extractModelFileLabel.textContent = file ? file.name : "or click to choose a paper PDF";
});

extractModelDropzone?.addEventListener("dragover", (event) => {
    event.preventDefault();
    extractModelDropzone.classList.add("is-dragover");
});

extractModelDropzone?.addEventListener("dragleave", () => {
    extractModelDropzone.classList.remove("is-dragover");
});

extractModelDropzone?.addEventListener("drop", (event) => {
    event.preventDefault();
    extractModelDropzone.classList.remove("is-dragover");
    const file = event.dataTransfer?.files?.[0];
    if (!file || !extractModelFileInput) {
        return;
    }
    const transfer = new DataTransfer();
    transfer.items.add(file);
    extractModelFileInput.files = transfer.files;
    extractModelFileInput.dispatchEvent(new Event("change"));
});

async function runModelExtraction() {
    const file = extractModelFileInput.files?.[0];
    if (!file) {
        extractModelStatus.textContent = "Choose a paper PDF first.";
        return false;
    }
    if (!String(file.name || "").toLowerCase().endsWith(".pdf")) {
        extractModelStatus.textContent = "Upload a PDF file.";
        return false;
    }
    resetExtractedPreview();
    setExtractionBusy(true);
    extractModelStatus.textContent = "Generating taxonomy from paper PDF. This can take several minutes.";
    try {
        const body = new FormData();
        body.append("file", file);
        body.append("prompt", extractModelPrompt.value || "");
        const response = await api("/api/models/extract-from-paper", {method: "POST", body});
        applyExtractedTaxonomyPreview(response);
    } catch (error) {
        extractModelStatus.textContent = error.message;
    } finally {
        setExtractionBusy(false);
    }
    return true;
}

extractModelForm?.addEventListener("submit", (event) => {
    event.preventDefault();
    void runModelExtraction();
});

extractModelSubmitButton?.addEventListener("click", () => {
    void runModelExtraction();
});

extractModelTitle?.addEventListener("input", syncExtractedDraftSaveState);

extractModelSaveButton?.addEventListener("click", async () => {
    if (!extractedTaxonomyDraft?.taxonomy) {
        return;
    }
    const title = String(extractModelTitle.value || "").trim();
    if (!title) {
        extractModelStatus.textContent = "Enter a model name before saving.";
        return;
    }
    const taxonomy = structuredClone(extractedTaxonomyDraft.taxonomy);
    taxonomy.title = title;
    taxonomy.id = deriveId(title, "Data Extraction Model id");
    try {
        extractModelStatus.textContent = "Saving generated model...";
        const response = await api("/api/models/custom/json", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(taxonomy)
        });
        extractedTaxonomyDraft.taxonomy = taxonomy;
        extractModelStatus.textContent = `Saved Data Extraction Model: ${response.title}.`;
        await loadWorkspace();
        designerModelPicker.value = `${response.source}:${response.id}`;
        lastDesignerPickerValue = designerModelPicker.value;
    } catch (error) {
        extractModelStatus.textContent = error.message;
    }
});

extractModelLoadButton?.addEventListener("click", async () => {
    if (!extractedTaxonomyDraft?.taxonomy) {
        return;
    }
    if (!await confirmDiscardDesignerChanges("Load the generated model into the designer? Unsaved changes will be discarded.")) {
        return;
    }
    const title = String(extractModelTitle.value || "").trim();
    if (!title) {
        extractModelStatus.textContent = "Enter a model name before loading it into the designer.";
        return;
    }
    const taxonomy = structuredClone(extractedTaxonomyDraft.taxonomy);
    taxonomy.title = title;
    taxonomy.id = deriveId(title, "Data Extraction Model id");
    resetDesignerFromTaxonomy(taxonomy);
    designerStatus.textContent = `Loaded generated Data Extraction Model: ${taxonomy.title}`;
    showView("designer-view");
});

for (const button of tabButtons) {
    button.addEventListener("click", () => showView(button.dataset.view));
}

addScaleButton.addEventListener("click", () => addScaleCard());
addDimensionSecondaryButton.addEventListener("click", () => addDimensionCard(dimensionList));
addRuleButton.addEventListener("click", () => addRuleCard());
clearDesignedModelButton.addEventListener("click", async () => {
    if (!await confirmDiscardDesignerChanges("Clear the current Data Extraction Model? Unsaved changes will be discarded.")) {
        return;
    }
    resetDesignerToBlank();
    designerStatus.textContent = "Cleared current Data Extraction Model.";
});

designerModelPicker.addEventListener("change", async () => {
    if (!designerModelPicker.value) {
        designerModelPicker.value = lastDesignerPickerValue;
        return;
    }
    if (!await confirmDiscardDesignerChanges()) {
        designerModelPicker.value = lastDesignerPickerValue;
        return;
    }
    const [source, modelId] = designerModelPicker.value.split(":");
    loadModelIntoDesigner(source, modelId);
});

designerForm.addEventListener("input", handleDesignerMutation);
designerForm.addEventListener("change", handleDesignerMutation);

designerForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    designerStatus.textContent = "Saving Data Extraction Model...";
    try {
        const model = collectDesignedTaxonomy();
        const response = await api("/api/models/custom/json", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(model)
        });
        designerStatus.textContent = `Saved Data Extraction Model: ${response.title}.`;
        markDesignerClean();
        loadWorkspace().catch((error) => {
            modelStatus.textContent = error.message;
            designerStatus.textContent = `Saved Data Extraction Model: ${response.title}, but refreshing the workspace failed: ${error.message}`;
        });
    } catch (error) {
        designerStatus.textContent = error.message;
    }
});

downloadDataExtractionMetamodelButton.addEventListener("click", () => {
    window.location.href = "/api/metamodels/data-extraction-model/download";
});

downloadDataExtractionMetamodelJsonSchemaButton.addEventListener("click", async () => {
    const schema = await api("/api/metamodels/data-extraction-model/json-schema");
    downloadJsonBlob(schema, "data-extraction-model.schema.json");
});

downloadReviewDesignMetamodelButton.addEventListener("click", () => {
    window.location.href = "/api/metamodels/review-design/download";
});

downloadReviewDesignMetamodelJsonSchemaButton.addEventListener("click", async () => {
    const schema = await api("/api/metamodels/review-design/json-schema");
    downloadJsonBlob(schema, "review-design.schema.json");
});

function applyReviewDesignPreview(response, options = {}) {
    currentReviewDesign = response.review_design;
    formSchema = response.form_schema;
    reviewLinkmlSchema = response.review_linkml_schema;
    reviewJsonSchema = response.review_json_schema;
    selectedReviewModelIds = [...(response.review_design.selected_model_ids || [])];
    renderModelWorkspace(workspaceModels);
    composedModelId.textContent = response.review_design.id;
    composedModelTitles.textContent = `Models: ${selectedModelTitles().join(", ")}`;
    renderClassificationFields(formSchema.fields);
    downloadTaxonomyButton.disabled = false;
    downloadReviewHtmlButton.disabled = false;
    downloadReviewLinkmlSchemaButton.disabled = false;
    downloadReviewJsonSchemaButton.disabled = false;
    downloadReviewRdfButton.disabled = false;
    downloadReviewShaclButton.disabled = false;
    downloadReviewOwlButton.disabled = false;
    if (!options.saved) {
        modelStatus.textContent = "Review Design preview updated.";
    } else {
        modelStatus.textContent = `Loaded Review Template: ${response.review_design.title}`;
    }
}

async function openReviewDesign(reviewDesignId) {
    modelStatus.textContent = "Loading Review Template...";
    try {
        const response = await api(`/api/review-designs/${encodeURIComponent(reviewDesignId)}`);
        applyReviewDesignPreview(response, {saved: true});
    } catch (error) {
        modelStatus.textContent = error.message;
    }
}

async function removeReviewDesign(reviewDesignId) {
    if (!window.confirm(`Delete Review Template ${reviewDesignId}?`)) {
        return;
    }
    try {
        await api(`/api/review-designs/${encodeURIComponent(reviewDesignId)}`, {method: "DELETE"});
        if (currentReviewDesign && currentReviewDesign.id === reviewDesignId) {
            resetReviewDesignPreview();
        }
        await loadWorkspace();
        modelStatus.textContent = `Deleted Review Template: ${reviewDesignId}`;
    } catch (error) {
        modelStatus.textContent = error.message;
    }
}

async function removeDataExtractionModel(source, modelId) {
    if (!window.confirm(`Delete Data Extraction Model ${modelId} from ${source}?`)) {
        return;
    }
    try {
        await api(`/api/models/${encodeURIComponent(source)}/${encodeURIComponent(modelId)}`, {method: "DELETE"});
        if (designerModelPicker.value === `${source}:${modelId}`) {
            designerModelPicker.value = "";
        }
        selectedReviewModelIds = selectedReviewModelIds.filter((item) => item !== modelId);
        await loadWorkspace();
        scheduleReviewPreview();
        modelStatus.textContent = `Deleted Data Extraction Model: ${modelId}`;
    } catch (error) {
        modelStatus.textContent = error.message;
    }
}

function resetReviewDesignPreview() {
    currentReviewDesign = null;
    formSchema = null;
    reviewLinkmlSchema = null;
    reviewJsonSchema = null;
    composedModelId.textContent = "";
    composedModelTitles.textContent = "";
    dynamicFields.classList.add("empty-state");
    dynamicFields.textContent = "The draft data extraction form preview will appear here once you select Data Extraction Models.";
    downloadTaxonomyButton.disabled = true;
    downloadReviewLinkmlSchemaButton.disabled = true;
    downloadReviewJsonSchemaButton.disabled = true;
}

function initializeDragSurface(container, mode) {
    if (container.dataset.dragSurfaceReady === "true") {
        return;
    }
    container.dataset.dragSurfaceReady = "true";
    container.addEventListener("dragover", (event) => {
        event.preventDefault();
        container.classList.add("is-dragover");
    });
    container.addEventListener("dragleave", () => {
        container.classList.remove("is-dragover");
    });
    container.addEventListener("drop", (event) => {
        event.preventDefault();
        container.classList.remove("is-dragover");
        const modelId = event.dataTransfer?.getData("text/plain");
        if (!modelId) {
            return;
        }
        moveReviewModel(modelId, mode === "selected");
    });
}

function moveReviewModel(modelId, selected) {
    const alreadySelected = selectedReviewModelIds.includes(modelId);
    if (selected && !alreadySelected) {
        selectedReviewModelIds = [...selectedReviewModelIds, modelId];
    }
    if (!selected && alreadySelected) {
        selectedReviewModelIds = selectedReviewModelIds.filter((item) => item !== modelId);
    }
    renderModelWorkspace(workspaceModels);
    scheduleReviewPreview();
}

function scheduleReviewPreview() {
    const modelIds = selectedModelIds();
    if (modelIds.length === 0) {
        resetReviewDesignPreview();
        modelStatus.textContent = "Drag at least one Data Extraction Model into the selected tray.";
        return;
    }
    const title = buildDraftReviewDesignTitle(modelIds);
    void updateReviewPreview(title, modelIds);
}

function buildDraftReviewDesignTitle(modelIds) {
    const titles = modelIds
        .map((id) => workspaceModels.find((model) => model.id === id)?.title)
        .filter(Boolean);
    if (titles.length === 0) {
        return "Draft review design";
    }
    if (titles.length === 1) {
        return `${titles[0]} review design`;
    }
    const primary = titles.slice(0, 2).join(" + ");
    const suffix = titles.length > 2 ? ` + ${titles.length - 2} more` : "";
    return `${primary}${suffix} review design`;
}

async function updateReviewPreview(title, modelIds) {
    const requestId = ++reviewPreviewRequestId;
    modelStatus.textContent = "Updating Review Design preview...";
    try {
        const response = await api("/api/models/compose", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({title, model_ids: modelIds})
        });
        if (requestId !== reviewPreviewRequestId) {
            return;
        }
        applyReviewDesignPreview(response, {saved: false});
    } catch (error) {
        if (requestId !== reviewPreviewRequestId) {
            return;
        }
        modelStatus.textContent = error.message;
    }
}

function initializeTooltips() {
    if (!window.bootstrap?.Tooltip) {
        return;
    }
    for (const trigger of document.querySelectorAll('[data-bs-toggle="tooltip"]')) {
        window.bootstrap.Tooltip.getOrCreateInstance(trigger);
    }
}

function modelTint(value) {
    const hash = stableNumberHash(value);
    const hue = hash % 360;
    return {
        background: `hsl(${hue} 70% 94%)`,
        border: `hsl(${hue} 38% 78%)`
    };
}

function stableNumberHash(value) {
    let hash = 0;
    for (const character of String(value || "")) {
        hash = ((hash << 5) - hash) + character.charCodeAt(0);
        hash |= 0;
    }
    return Math.abs(hash);
}

async function loadModelIntoDesigner(source, modelId) {
    designerStatus.textContent = "Loading Data Extraction Model into designer...";
    try {
        const taxonomy = await api(`/api/models/${encodeURIComponent(source)}/${encodeURIComponent(modelId)}`);
        resetDesignerFromTaxonomy(taxonomy);
        designerModelPicker.value = `${source}:${modelId}`;
        lastDesignerPickerValue = designerModelPicker.value;
        designerStatus.textContent = `Loaded Data Extraction Model: ${taxonomy.title}`;
        showView("designer-view");
    } catch (error) {
        designerStatus.textContent = error.message;
    }
}

function resetDesignerFromTaxonomy(taxonomy) {
    designerForm.reset();
    scaleList.innerHTML = "";
    dimensionList.innerHTML = "";
    ruleList.innerHTML = "";
    dimensionList.classList.remove("empty-state");

    designerForm.elements["model.title"].value = taxonomy.title || "";
    designerForm.elements["source.title"].value = taxonomy.source?.title || "";
    designerForm.elements["source.authors"].value = (taxonomy.source?.authors || []).join("; ");
    designerForm.elements["source.year"].value = taxonomy.source?.year || "";
    updateDerivedMetadata();
    sourceDetails.open = Boolean(
        taxonomy.source?.title ||
        (taxonomy.source?.authors || []).length > 0 ||
        taxonomy.source?.year
    );

    for (const scale of taxonomy.scales || []) {
        addScaleCard(scale);
    }
    for (const dimension of taxonomy.dimensions || []) {
        addDimensionCard(dimensionList, dimension);
    }
    for (const rule of taxonomy.rules || []) {
        addRuleCard(rule);
    }

    if ((taxonomy.scales || []).length === 0) {
        addScaleCard({id: "ordinal_0_2", scale_type: "ordinal"});
    }
    restoreDimensionEmptyState();
    markDesignerClean();
}

function resetDesignerToBlank() {
    designerForm.reset();
    scaleList.innerHTML = "";
    dimensionList.innerHTML = "";
    ruleList.innerHTML = "";
    designerModelPicker.value = "";
    lastDesignerPickerValue = "";
    sourceDetails.open = false;
    updateDerivedMetadata();
    addScaleCard({id: "ordinal_0_2", scale_type: "ordinal"});
    restoreDimensionEmptyState();
    markDesignerClean();
}

function resetExtractedPreview() {
    extractedTaxonomyDraft = null;
    extractModelTitle.value = "";
    extractModelTitle.disabled = true;
    extractModelSaveButton.disabled = true;
    extractModelLoadButton.disabled = true;
    extractModelValidation.textContent = "";
    extractModelErrors.classList.add("d-none");
    extractModelErrors.innerHTML = "";
    extractModelYaml.classList.add("empty-state");
    extractModelYaml.textContent = "The generated YAML will appear here after extraction.";
    extractModelFormPreview.classList.add("empty-state");
    extractModelFormPreview.textContent = "The generated form preview will appear here after extraction.";
}

function setExtractionBusy(isBusy) {
    if (extractModelFileInput) {
        extractModelFileInput.disabled = isBusy;
    }
    if (extractModelPrompt) {
        extractModelPrompt.disabled = isBusy;
    }
    if (extractModelSubmitButton) {
        extractModelSubmitButton.disabled = isBusy;
        extractModelSubmitButton.textContent = isBusy ? "Generating..." : "Generate taxonomy";
    }
    if (extractModelProgress) {
        extractModelProgress.classList.toggle("d-none", !isBusy);
    }
}

function applyExtractedTaxonomyPreview(response) {
    extractedTaxonomyDraft = response;
    extractModelYaml.classList.remove("empty-state");
    extractModelYaml.textContent = response.raw_yaml || "";

    if (response.taxonomy?.title) {
        extractModelTitle.value = response.taxonomy.title;
        extractModelTitle.disabled = false;
    } else {
        extractModelTitle.value = "";
        extractModelTitle.disabled = true;
    }

    if (response.form_schema?.fields?.length) {
        renderClassificationFieldsInto(extractModelFormPreview, response.form_schema.fields, response.form_schema);
    } else {
        extractModelFormPreview.classList.add("empty-state");
        extractModelFormPreview.textContent = "The generated taxonomy did not validate, so no form preview is available yet.";
    }

    const validationErrors = response.validation_errors || [];
    if (validationErrors.length > 0) {
        extractModelValidation.textContent = `${validationErrors.length} validation issue(s)`;
        extractModelErrors.innerHTML = `<ul class="mb-0">${validationErrors.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`;
        extractModelErrors.classList.remove("d-none");
        extractModelStatus.textContent = "Generation completed, but the model did not validate.";
    } else {
        extractModelValidation.textContent = "Valid against metamodel";
        extractModelErrors.classList.add("d-none");
        extractModelErrors.innerHTML = "";
        extractModelStatus.textContent = "Generation completed.";
    }
    syncExtractedDraftSaveState();
}

function syncExtractedDraftSaveState() {
    const canUse = Boolean(extractedTaxonomyDraft?.taxonomy) && (extractedTaxonomyDraft.validation_errors || []).length === 0;
    const hasTitle = Boolean(String(extractModelTitle?.value || "").trim());
    extractModelSaveButton.disabled = !(canUse && hasTitle);
    extractModelLoadButton.disabled = !(canUse && hasTitle);
}

function handleDesignerMutation(event) {
    if (event.target === designerModelPicker) {
        return;
    }
    if (event.target.name === "model.title") {
        updateDerivedMetadata();
    }
    syncDesignerDirtyState();
}

function updateDerivedMetadata() {
    sourceCitationKeyInput.value = deriveOptionalId(designerForm.elements["model.title"]?.value);
}

function serializeDesignerState() {
    const fields = Array.from(designerForm.querySelectorAll("input, textarea, select"))
        .filter((element) => element !== designerModelPicker)
        .map((element, index) => ({
            index,
            name: element.name || element.dataset.role || element.id || element.type || element.tagName,
            type: element.type || element.tagName,
            value: element.type === "checkbox" ? element.checked : element.value
        }));
    return JSON.stringify(fields);
}

function syncDesignerDirtyState() {
    setDesignerDirty(serializeDesignerState() !== designerBaselineSnapshot);
}

function setDesignerDirty(isDirty) {
    designerDirty = isDirty;
    saveDesignedModelButton.classList.toggle("d-none", !isDirty);
}

function markDesignerClean() {
    designerBaselineSnapshot = serializeDesignerState();
    setDesignerDirty(false);
}

async function confirmDiscardDesignerChanges(message = "Load another Data Extraction Model? Unsaved changes will be discarded.") {
    if (!designerDirty) {
        return true;
    }
    return window.confirm(message);
}

function renderClassificationFields(fields) {
    renderClassificationFieldsInto(dynamicFields, fields, formSchema);
}

function renderClassificationFieldsInto(container, fields, schema) {
    container.classList.remove("empty-state");
    container.innerHTML = "";
    for (const field of fields) {
        container.append(renderField(field, schema));
    }
}

function renderField(field, schema = formSchema) {
    const wrapper = document.createElement("section");
    wrapper.className = "classification-field card border-0 shadow-sm";
    const body = document.createElement("div");
    body.className = "card-body d-grid gap-2";
    const title = document.createElement("h3");
    title.className = "h5 mb-0";
    title.textContent = `${field.label}${field.required ? " *" : ""}`;
    body.append(title);
    if (field.description) {
        const description = document.createElement("p");
        description.className = "mb-0 text-secondary";
        description.textContent = field.description;
        body.append(description);
    }

    if (field.values && field.values.length > 0) {
        if (field.cardinality === "multiple") {
            const list = document.createElement("div");
            list.className = "checkbox-list";
            for (const option of flattenOptions(field.values)) {
                const label = document.createElement("label");
                label.className = "form-check";
                if (option.description) {
                    label.title = option.description;
                }
                label.innerHTML = `<input class="form-check-input" type="checkbox" data-field="${escapeHtml(field.id)}" data-option-criteria='${escapeHtml(JSON.stringify(option.criteria || []))}' value="${escapeHtml(option.id)}"><span class="form-check-label">${escapeHtml(option.label)}</span>`;
                list.append(label);
            }
            list.addEventListener("change", () => renderSelectedCriteria(field.id, criteriaContainer, schema));
            body.append(list);
        } else {
            const select = document.createElement("select");
            select.className = "form-select";
            select.dataset.field = field.id;
            select.required = Boolean(field.required);
            select.innerHTML = `<option value="">Select ${escapeHtml(field.label)}</option>`;
            for (const option of flattenOptions(field.values)) {
                const item = document.createElement("option");
                item.value = option.id;
                item.textContent = option.label;
                item.dataset.optionCriteria = JSON.stringify(option.criteria || []);
                if (option.description) {
                    item.title = option.description;
                }
                select.append(item);
            }
            select.addEventListener("change", () => renderSelectedCriteria(field.id, criteriaContainer, schema));
            body.append(select);
        }
        const criteriaContainer = document.createElement("div");
        criteriaContainer.className = "dynamic-fields";
        criteriaContainer.dataset.criteriaFor = field.id;
        body.append(criteriaContainer);
    } else if (field.value_type === "numeric") {
        const input = document.createElement("input");
        input.className = "form-control";
        input.type = "number";
        input.dataset.field = field.id;
        input.required = Boolean(field.required);
        body.append(input);
    } else {
        const textarea = document.createElement("textarea");
        textarea.className = "form-control";
        textarea.rows = 3;
        textarea.dataset.field = field.id;
        textarea.required = Boolean(field.required);
        body.append(textarea);
    }

    for (const subfield of field.subdimensions || []) {
        body.append(renderField(subfield, schema));
    }
    wrapper.append(body);
    return wrapper;
}

function flattenOptions(options, prefix = "") {
    const result = [];
    for (const option of options) {
        const label = prefix ? `${prefix} / ${option.label}` : option.label;
        result.push({id: option.id, label, description: option.description, criteria: option.criteria || []});
        result.push(...flattenOptions(option.children || [], label));
    }
    return result;
}

function renderSelectedCriteria(fieldId, container, schema = formSchema) {
    const criteria = selectedCriteriaFor(fieldId, container.parentElement || document);
    container.innerHTML = "";
    for (const criterion of criteria) {
        container.append(renderCriterion(criterion, schema));
    }
}

function selectedCriteriaFor(fieldId, scope = document) {
    const criteriaById = new Map();
    const checkboxes = Array.from(scope.querySelectorAll(`[data-field="${cssEscape(fieldId)}"]:checked`));
    for (const checkbox of checkboxes) {
        for (const criterion of JSON.parse(checkbox.dataset.optionCriteria || "[]")) {
            criteriaById.set(criterion.id, criterion);
        }
    }

    const select = scope.querySelector(`select[data-field="${cssEscape(fieldId)}"]`);
    if (select && select.selectedOptions.length > 0) {
        const selected = select.selectedOptions[0];
        for (const criterion of JSON.parse(selected.dataset.optionCriteria || "[]")) {
            criteriaById.set(criterion.id, criterion);
        }
    }
    return Array.from(criteriaById.values());
}

function renderCriterion(criterion, schema = formSchema) {
    const wrapper = document.createElement("div");
    wrapper.className = "classification-field card border-0";
    const body = document.createElement("div");
    body.className = "card-body d-grid gap-2";
    const title = document.createElement("strong");
    title.textContent = `${criterion.label || criterion.id}${criterion.required ? " *" : ""}`;
    body.append(title);
    if (criterion.question) {
        const question = document.createElement("p");
        question.className = "mb-0 text-secondary";
        question.textContent = criterion.question;
        body.append(question);
    }

    const scale = schema?.scales?.[criterion.scale];
    if (scale && scale.scale_values && scale.scale_values.length > 0) {
        const select = document.createElement("select");
        select.className = "form-select";
        select.dataset.criterion = criterion.id;
        select.required = Boolean(criterion.required);
        select.innerHTML = "<option value=\"\">Select value</option>";
        for (const scaleValue of scale.scale_values) {
            const option = document.createElement("option");
            option.value = scaleValue.value;
            option.textContent = scaleValue.label || scaleValue.value;
            select.append(option);
        }
        body.append(select);
        wrapper.append(body);
        return wrapper;
    }

    const input = document.createElement("input");
    input.className = "form-control";
    input.dataset.criterion = criterion.id;
    input.required = Boolean(criterion.required);
    input.type = scale?.scale_type === "numeric" ? "number" : "text";
    body.append(input);
    wrapper.append(body);
    return wrapper;
}

function showView(viewId) {
    for (const view of appViews) {
        view.classList.toggle("hidden", view.id !== viewId);
    }
    for (const button of tabButtons) {
        button.classList.toggle("active", button.dataset.view === viewId);
    }
}

function titleIcon(kind) {
    const icons = {
        scale: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M3 3h10v2H3V3Zm2 4h6v2H5V7Zm-2 4h10v2H3v-2Z\"/></svg></span>",
        values: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M3 4h10v2H3V4Zm0 3h10v2H3V7Zm0 3h10v2H3v-2Z\"/></svg></span>",
        dimension: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M2 3h5v4H2V3Zm7 0h5v4H9V3ZM2 9h5v4H2V9Zm7 0h5v4H9V9Z\"/></svg></span>",
        subdimension: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M2 3h5v3H2V3Zm7 0h5v3H9V3ZM4 7h2v2h4V7h2v3H9v3H7v-3H4V7Z\"/></svg></span>",
        taxa: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M3 3h10v3H3V3Zm0 4h4v6H3V7Zm5 0h5v2H8V7Zm0 3h5v3H8v-3Z\"/></svg></span>",
        taxon: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M8 1 2 4v3c0 4 2.6 6.6 6 8 3.4-1.4 6-4 6-8V4L8 1Zm0 2.1 4 2v2c0 3-1.8 5-4 6.2-2.2-1.2-4-3.2-4-6.2v-2l4-2Z\"/></svg></span>",
        criteria: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M2 3h12v10H2V3Zm1 1v8h10V4H3Zm2 1h6v1H5V5Zm0 2h6v1H5V7Zm0 2h4v1H5V9Z\"/></svg></span>",
        criterion: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M8 1a4 4 0 0 1 4 4c0 1.4-.8 2.3-1.6 3.1-.7.7-1.2 1.2-1.4 1.9H7c-.2-.7-.7-1.2-1.4-1.9C4.8 7.3 4 6.4 4 5a4 4 0 0 1 4-4Zm-1 10h2v2H7v-2Zm1-9a3 3 0 0 0-3 3c0 1 .6 1.7 1.3 2.4.6.6 1.4 1.3 1.7 2.6h.1c.3-1.3 1.1-2 1.7-2.6C10.4 6.7 11 6 11 5a3 3 0 0 0-3-3Z\"/></svg></span>",
        children: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M8 2h6v3H8V2ZM2 6h6v3H2V6Zm8 4h4v3h-4v-3ZM7 3H5v4H3v1h3a1 1 0 0 0 1-1V4h1V3Zm1 1v1h2v5a1 1 0 0 0 1 1h1v-1h-1V4H8Z\"/></svg></span>",
        rule: "<span class=\"title-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 16 16\" fill=\"currentColor\"><path d=\"M3 2h10v12H3V2Zm1 1v10h8V3H4Zm1 2h6v1H5V5Zm0 2h6v1H5V7Zm0 2h4v1H5V9Z\"/></svg></span>"
    };
    return icons[kind] || "";
}

function addScaleCard(initial = {}) {
    const card = document.createElement("section");
    card.className = "builder-card scale-card card border-0 shadow-sm";
    card.innerHTML = `
        <div class="card-body d-grid gap-3">
        <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
            <h3 class="h5 mb-0 title-with-icon">${titleIcon("scale")}<span>Scale</span></h3>
            <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
        </div>
        <p class="help-text">Use scales for criterion answers. Ordinal scales are ordered; nominal scales are named choices without order; numeric and free_text scales collect open values.</p>
        <div class="compact-grid">
            <label class="form-label mb-0">Scale name<span class="help-text">The scale id is generated automatically from this name.</span><input class="form-control" data-role="scale-label" value="${escapeHtml(initial.label || humanizeId(initial.id || ""))}" placeholder="Ordinal 0 2" required></label>
            <label class="form-label mb-0">Type
                <span class="help-text">Controls how criterion answers are interpreted.</span>
                <select class="form-select" data-role="scale-type">
                    ${selectOptions(["binary", "ordinal", "nominal", "numeric", "free_text"], initial.scale_type || "ordinal")}
                </select>
            </label>
        </div>
        <div class="builder-subsection card card-body bg-body-tertiary border-0">
            <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
                <h3 class="h6 mb-0 title-with-icon">${titleIcon("values")}<span>Values</span></h3>
                <button class="btn btn-outline-secondary btn-sm" data-action="add-scale-value" type="button">Add value</button>
            </div>
            <p class="help-text">Each value has a stored value and an optional label shown to users.</p>
            <div data-role="scale-values" class="builder-list"></div>
        </div>
        </div>
    `;
    card.querySelector(".remove-card").addEventListener("click", () => {
        card.remove();
        refreshCriterionScaleOptions();
        syncDesignerDirtyState();
    });
    card.querySelector("[data-action='add-scale-value']").addEventListener("click", () => addScaleValueRow(card.querySelector("[data-role='scale-values']")));
    card.querySelector("[data-role='scale-label']").addEventListener("input", refreshCriterionScaleOptions);
    scaleList.append(card);
    for (const value of initial.scale_values || []) {
        addScaleValueRow(card.querySelector("[data-role='scale-values']"), value);
    }
    if (!initial.scale_values) {
        addScaleValueRow(card.querySelector("[data-role='scale-values']"), {value: "0", label: "not_satisfied"});
        addScaleValueRow(card.querySelector("[data-role='scale-values']"), {value: "1", label: "partially_satisfied"});
        addScaleValueRow(card.querySelector("[data-role='scale-values']"), {value: "2", label: "satisfied"});
    }
    refreshCriterionScaleOptions();
    syncDesignerDirtyState();
}

function addScaleValueRow(container, initial = {}) {
    const row = document.createElement("div");
    row.className = "inline-row";
    row.innerHTML = `
        <input class="form-control" data-role="scale-value" value="${escapeHtml(initial.value || "")}" placeholder="value" required>
        <input class="form-control" data-role="scale-value-label" value="${escapeHtml(initial.label || "")}" placeholder="label">
        <input class="form-control" data-role="scale-value-description" value="${escapeHtml(initial.description || "")}" placeholder="description">
        <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
    `;
    row.querySelector(".remove-card").addEventListener("click", () => {
        row.remove();
        syncDesignerDirtyState();
    });
    container.append(row);
    syncDesignerDirtyState();
}

function addDimensionCard(container, initial = {}, nested = false) {
    clearEmptyState(container);
    const card = document.createElement("section");
    card.className = `builder-card dimension-card card border-0 shadow-sm${nested ? " nested-card" : ""}`;
    card.innerHTML = `
        <div class="card-body d-grid gap-3">
        <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
            <h3 class="h5 mb-0 title-with-icon">${titleIcon(nested ? "subdimension" : "dimension")}<span>${nested ? "Subdimension" : "Dimension"}</span></h3>
            <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
        </div>
        <p class="help-text">A dimension is a classification axis. Use subdimensions when a topic needs more detailed child axes.</p>
        <div class="compact-grid">
            <label class="form-label mb-0">Label<span class="help-text">Name displayed in the classification form. The dimension id is generated automatically from this label.</span><input class="form-control" data-role="dimension-label" value="${escapeHtml(initial.label || humanizeId(initial.id || ""))}" placeholder="Paper class" required></label>
            <label class="form-label mb-0">Value type
                <span class="help-text">category uses taxa; free_text and numeric collect direct input.</span>
                <select class="form-select" data-role="dimension-value-type">
                    ${selectOptions(["category", "criterion", "method", "free_text", "numeric"], initial.value_type || "category")}
                </select>
            </label>
            <label class="form-label mb-0">Cardinality
                <span class="help-text">single allows one answer; multiple allows several.</span>
                <select class="form-select" data-role="dimension-cardinality">
                    ${selectOptions(["single", "multiple"], initial.cardinality || "multiple")}
                </select>
            </label>
            <label class="checkbox-inline form-check"><input class="form-check-input" data-role="dimension-required" type="checkbox" ${initial.required ? "checked" : ""}> <span class="form-check-label">Required</span> <span class="help-text">Classifier must answer this dimension.</span></label>
        </div>
        <label class="form-label mb-0">Description<textarea class="form-control" data-role="dimension-description" rows="2">${escapeHtml(initial.description || "")}</textarea></label>
        <div class="builder-subsection card card-body bg-body-tertiary border-0">
            <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
                <h3 class="h6 mb-0 title-with-icon">${titleIcon("taxa")}<span>Taxa</span></h3>
                <button class="btn btn-outline-secondary btn-sm" data-action="add-taxon" type="button">Add taxon</button>
            </div>
            <p class="help-text">Taxa are controlled vocabulary values for category-like dimensions.</p>
            <div data-role="taxa" class="builder-list"></div>
        </div>
        <div class="builder-subsection card card-body bg-body-tertiary border-0">
            <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
                <h3 class="h6 mb-0 title-with-icon">${titleIcon("subdimension")}<span>Subdimensions</span></h3>
                <button class="btn btn-outline-secondary btn-sm" data-action="add-subdimension" type="button">Add subdimension</button>
            </div>
            <p class="help-text">Subdimensions appear as nested classification fields under this dimension.</p>
            <div data-role="subdimensions" class="builder-list"></div>
        </div>
        </div>
    `;
    card.querySelector(".remove-card").addEventListener("click", () => {
        card.remove();
        restoreDimensionEmptyState();
        syncDesignerDirtyState();
    });
    card.querySelector("[data-action='add-taxon']").addEventListener("click", () => addTaxonCard(card.querySelector("[data-role='taxa']")));
    card.querySelector("[data-action='add-subdimension']").addEventListener("click", () => addDimensionCard(card.querySelector("[data-role='subdimensions']"), {}, true));
    container.append(card);
    for (const taxon of initial.values || []) {
        addTaxonCard(card.querySelector("[data-role='taxa']"), taxon);
    }
    for (const subdimension of initial.subdimensions || []) {
        addDimensionCard(card.querySelector("[data-role='subdimensions']"), subdimension, true);
    }
    syncDesignerDirtyState();
}

function addTaxonCard(container, initial = {}) {
    const card = document.createElement("section");
    card.className = "builder-card taxon-card card border-0";
    card.innerHTML = `
        <div class="card-body d-grid gap-3">
        <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
            <h3 class="h6 mb-0 title-with-icon">${titleIcon("taxon")}<span>Taxon</span></h3>
            <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
        </div>
        <p class="help-text">A taxon is a selectable category value. It may have child taxa and criteria that become active when selected.</p>
        <div class="compact-grid">
            <label class="form-label mb-0">Label<span class="help-text">Name displayed to classifiers. The taxon id is generated automatically from this label.</span><input class="form-control" data-role="taxon-label" value="${escapeHtml(initial.label || humanizeId(initial.id || ""))}" placeholder="Evaluation research" required></label>
        </div>
        <label class="form-label mb-0">Description<textarea class="form-control" data-role="taxon-description" rows="2">${escapeHtml(initial.description || "")}</textarea></label>
        <div class="builder-subsection card card-body bg-body border">
            <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
                <h3 class="h6 mb-0 title-with-icon">${titleIcon("criteria")}<span>Criteria</span></h3>
                <button class="btn btn-outline-secondary btn-sm" data-action="add-criterion" type="button">Add criterion</button>
            </div>
            <p class="help-text">Criteria are follow-up questions attached to this taxon.</p>
            <div data-role="criteria" class="builder-list"></div>
        </div>
        <div class="builder-subsection card card-body bg-body border">
            <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
                <h3 class="h6 mb-0 title-with-icon">${titleIcon("children")}<span>Child taxa</span></h3>
                <button class="btn btn-outline-secondary btn-sm" data-action="add-child-taxon" type="button">Add child taxon</button>
            </div>
            <p class="help-text">Child taxa create a hierarchy inside this value.</p>
            <div data-role="children" class="builder-list"></div>
        </div>
        </div>
    `;
    card.querySelector(".remove-card").addEventListener("click", () => {
        card.remove();
        syncDesignerDirtyState();
    });
    card.querySelector("[data-action='add-criterion']").addEventListener("click", () => addCriterionRow(card.querySelector("[data-role='criteria']")));
    card.querySelector("[data-action='add-child-taxon']").addEventListener("click", () => addTaxonCard(card.querySelector("[data-role='children']")));
    container.append(card);
    for (const criterion of initial.criteria || []) {
        addCriterionRow(card.querySelector("[data-role='criteria']"), criterion);
    }
    for (const child of initial.children || []) {
        addTaxonCard(card.querySelector("[data-role='children']"), child);
    }
    syncDesignerDirtyState();
}

function addCriterionRow(container, initial = {}) {
    const card = document.createElement("section");
    card.className = "builder-card criterion-card card border-0";
    card.innerHTML = `
        <div class="card-body d-grid gap-3">
        <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
            <h3 class="h6 mb-0 title-with-icon">${titleIcon("criterion")}<span>Criterion</span></h3>
            <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
        </div>
        <p class="help-text">A criterion is an evaluation question. It references a scale so answers stay consistent across the model.</p>
        <div class="compact-grid">
            <label class="form-label mb-0">Label<span class="help-text">Short label shown above the question. The criterion id is generated automatically from this label.</span><input class="form-control" data-role="criterion-label" value="${escapeHtml(initial.label || humanizeId(initial.id || ""))}" placeholder="Problem clearly stated" required></label>
            <label class="form-label mb-0">Scale<span class="help-text">Choose one of the scales defined above.</span><select class="form-select" data-role="criterion-scale" data-selected="${escapeHtml(initial.scale || "")}" required></select></label>
            <label class="checkbox-inline form-check"><input class="form-check-input" data-role="criterion-required" type="checkbox" ${initial.required !== false ? "checked" : ""}> <span class="form-check-label">Required</span> <span class="help-text">Must be answered when this criterion is active.</span></label>
        </div>
        <label class="form-label mb-0">Question<textarea class="form-control" data-role="criterion-question" rows="2" required>${escapeHtml(initial.question || "")}</textarea></label>
        </div>
    `;
    card.querySelector(".remove-card").addEventListener("click", () => {
        card.remove();
        syncDesignerDirtyState();
    });
    container.append(card);
    refreshCriterionScaleOptions();
    syncDesignerDirtyState();
}

function addRuleCard(initial = {}) {
    const card = document.createElement("section");
    card.className = "builder-card rule-card card border-0 shadow-sm";
    card.innerHTML = `
        <div class="card-body d-grid gap-3">
        <div class="section-title d-flex justify-content-between align-items-center gap-3 mb-0">
            <h3 class="h5 mb-0 title-with-icon">${titleIcon("rule")}<span>Rule</span></h3>
            <button class="remove-card btn btn-outline-danger btn-sm" type="button">Remove</button>
        </div>
        <p class="help-text">Rules are explanatory guidance, such as whether multiple classes are allowed. They are not enforced automatically yet.</p>
        <div class="compact-grid">
            <label class="form-label mb-0">Applies to<span class="help-text">Dimension, taxon, or model part this rule concerns.</span><input class="form-control" data-role="rule-applies-to" value="${escapeHtml(initial.applies_to || "")}" placeholder="paper_class"></label>
        </div>
        <label class="form-label mb-0">Description<span class="help-text">The rule id is generated automatically from this description.</span><textarea class="form-control" data-role="rule-description" rows="2">${escapeHtml(initial.description || "")}</textarea></label>
        </div>
    `;
    card.querySelector(".remove-card").addEventListener("click", () => {
        card.remove();
        syncDesignerDirtyState();
    });
    ruleList.append(card);
    syncDesignerDirtyState();
}

function collectDesignedTaxonomy() {
    const form = new FormData(designerForm);
    const source = removeEmpty({
        citation_key: stringOrNull(sourceCitationKeyInput.value),
        title: stringOrNull(form.get("source.title")),
        authors: splitAuthors(form.get("source.authors")),
        year: numberOrNull(form.get("source.year"))
    });
    return removeEmpty({
        title: requiredString(form.get("model.title"), "Title"),
        id: deriveId(requiredString(form.get("model.title"), "Title"), "Data Extraction Model id"),
        target_entity: "paper",
        source: Object.keys(source).length > 0 ? source : undefined,
        dimensions: collectDimensionCards(dimensionList),
        scales: collectScaleCards(),
        rules: collectRuleCards()
    });
}

function collectScaleCards() {
    return Array.from(scaleList.querySelectorAll(":scope > .scale-card")).map((card) => removeEmpty({
        id: deriveId(card.querySelector("[data-role='scale-label']").value, "Scale id"),
        scale_type: card.querySelector("[data-role='scale-type']").value,
        scale_values: Array.from(card.querySelectorAll("[data-role='scale-values'] > .inline-row")).map((row) => removeEmpty({
            value: requiredString(row.querySelector("[data-role='scale-value']").value, "Scale value"),
            label: stringOrNull(row.querySelector("[data-role='scale-value-label']").value),
            description: stringOrNull(row.querySelector("[data-role='scale-value-description']").value)
        }))
    }));
}

function collectDimensionCards(container) {
    const dimensions = Array.from(container.querySelectorAll(":scope > .dimension-card")).map((card) => removeEmpty({
        label: requiredString(card.querySelector("[data-role='dimension-label']").value, "Dimension label"),
        id: deriveId(card.querySelector("[data-role='dimension-label']").value, "Dimension id"),
        description: stringOrNull(card.querySelector("[data-role='dimension-description']").value),
        value_type: card.querySelector("[data-role='dimension-value-type']").value,
        cardinality: card.querySelector("[data-role='dimension-cardinality']").value,
        required: card.querySelector("[data-role='dimension-required']").checked,
        values: collectTaxonCards(card.querySelector("[data-role='taxa']")),
        subdimensions: collectDimensionCards(card.querySelector("[data-role='subdimensions']"))
    }));
    if (container === dimensionList && dimensions.length === 0) {
        throw new Error("Add at least one dimension.");
    }
    return dimensions;
}

function collectTaxonCards(container) {
    return Array.from(container.querySelectorAll(":scope > .taxon-card")).map((card) => removeEmpty({
        label: requiredString(card.querySelector("[data-role='taxon-label']").value, "Taxon label"),
        id: deriveId(card.querySelector("[data-role='taxon-label']").value, "Taxon id"),
        description: stringOrNull(card.querySelector("[data-role='taxon-description']").value),
        criteria: collectCriterionCards(card.querySelector("[data-role='criteria']")),
        children: collectTaxonCards(card.querySelector("[data-role='children']"))
    }));
}

function collectCriterionCards(container) {
    return Array.from(container.querySelectorAll(":scope > .criterion-card")).map((card) => removeEmpty({
        label: requiredString(card.querySelector("[data-role='criterion-label']").value, "Criterion label"),
        id: deriveId(card.querySelector("[data-role='criterion-label']").value, "Criterion id"),
        question: requiredString(card.querySelector("[data-role='criterion-question']").value, "Criterion question"),
        scale: requiredString(card.querySelector("[data-role='criterion-scale']").value, "Criterion scale"),
        required: card.querySelector("[data-role='criterion-required']").checked
    }));
}

function collectRuleCards() {
    return Array.from(ruleList.querySelectorAll(":scope > .rule-card")).map((card) => removeEmpty({
        description: requiredString(card.querySelector("[data-role='rule-description']").value, "Rule description"),
        id: deriveId(card.querySelector("[data-role='rule-description']").value, "Rule id"),
        applies_to: stringOrNull(card.querySelector("[data-role='rule-applies-to']").value)
    }));
}

function refreshCriterionScaleOptions() {
    const scales = currentScaleEntries();
    for (const select of document.querySelectorAll("[data-role='criterion-scale']")) {
        const selected = select.value || select.dataset.selected || "";
        select.innerHTML = `<option value="">Select scale</option>${scales.map((scale) => `<option value="${escapeHtml(scale.id)}">${escapeHtml(scale.label)}</option>`).join("")}`;
        if (scales.some((scale) => scale.id === selected)) {
            select.value = selected;
        }
        select.dataset.selected = select.value;
    }
}

function currentScaleEntries() {
    return Array.from(scaleList.querySelectorAll(":scope > .scale-card"))
        .map((card) => String(card.querySelector("[data-role='scale-label']").value || "").trim())
        .filter(Boolean)
        .map((label) => ({id: deriveId(label, "Scale id"), label}));
}

function selectOptions(values, selected) {
    return values.map((value) => `<option value="${escapeHtml(value)}" ${value === selected ? "selected" : ""}>${escapeHtml(value)}</option>`).join("");
}

function clearEmptyState(container) {
    if (container.classList.contains("empty-state")) {
        container.classList.remove("empty-state");
        container.textContent = "";
    }
}

function restoreDimensionEmptyState() {
    if (dimensionList.querySelectorAll(":scope > .dimension-card").length === 0) {
        dimensionList.classList.add("empty-state");
        dimensionList.textContent = "Add at least one dimension.";
    }
}

function requiredString(value, label) {
    const stringValue = String(value || "").trim();
    if (!stringValue) {
        throw new Error(`${label} is required.`);
    }
    return stringValue;
}

function deriveId(value, label) {
    const derived = String(value || "")
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "");
    if (!derived) {
        throw new Error(`${label} is required.`);
    }
    return derived;
}

function deriveOptionalId(value) {
    return String(value || "")
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "");
}

function humanizeId(value) {
    return String(value || "")
        .replace(/[_-]+/g, " ")
        .replace(/\b\w/g, (character) => character.toUpperCase());
}

function stringOrNull(value) {
    const stringValue = String(value || "").trim();
    return stringValue || null;
}

function numberOrNull(value) {
    const stringValue = String(value || "").trim();
    return stringValue ? Number(stringValue) : null;
}

function splitAuthors(value) {
    return String(value || "").split(";").map((item) => item.trim()).filter(Boolean);
}

function removeEmpty(value) {
    return Object.fromEntries(Object.entries(value).filter(([, item]) => {
        if (item === undefined || item === null || item === "") {
            return false;
        }
        return !(Array.isArray(item) && item.length === 0);
    }));
}

function yamlStringify(value, indent = 0) {
    const prefix = "  ".repeat(indent);
    if (Array.isArray(value)) {
        return value.map((item) => {
            if (item && typeof item === "object") {
                const rendered = yamlStringify(item, indent + 1);
                const lines = rendered.split("\n");
                return `${prefix}- ${lines[0]}\n${lines.slice(1).map((line) => `${prefix}  ${line}`).join("\n")}`;
            }
            return `${prefix}- ${yamlScalar(item)}`;
        }).join("\n");
    }
    if (value && typeof value === "object") {
        return Object.entries(value).map(([key, item]) => {
            if (item && typeof item === "object") {
                const rendered = yamlStringify(item, indent + 1);
                return `${prefix}${key}:\n${rendered}`;
            }
            return `${prefix}${key}: ${yamlScalar(item)}`;
        }).join("\n");
    }
    return `${prefix}${yamlScalar(value)}`;
}

function yamlScalar(value) {
    if (typeof value === "string") {
        if (value === "" || /[:#\-\n]/.test(value)) {
            return JSON.stringify(value);
        }
        return value;
    }
    if (value === null) {
        return "null";
    }
    return String(value);
}

function downloadJsonBlob(value, filename) {
    downloadTextBlob(JSON.stringify(value, null, 2), filename, "application/json");
}

function reviewArtifactUrl(reviewId, format, download = false) {
    const params = new URLSearchParams();
    if (format && format !== "html") {
        params.set("format", format);
    }
    if (download) {
        params.set("download", "true");
    }
    if (format === "shacl") {
        return `/review/${encodeURIComponent(reviewId)}/shacl${params.toString() ? `?${params}` : ""}`;
    }
    return `/review/${encodeURIComponent(reviewId)}${params.toString() ? `?${params}` : ""}`;
}

function downloadTextBlob(text, filename, type) {
    const blob = new Blob([text], {type});
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

function cssEscape(value) {
    if (window.CSS && CSS.escape) {
        return CSS.escape(value);
    }
    return String(value).replaceAll("\"", "\\\"");
}

addScaleCard({id: "ordinal_0_2", scale_type: "ordinal"});

loadWorkspace().catch((error) => {
    modelStatus.textContent = error.message;
});
