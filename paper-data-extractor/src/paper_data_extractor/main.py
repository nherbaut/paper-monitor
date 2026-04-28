from __future__ import annotations

from typing import Any

import yaml
from fastapi import FastAPI, File, HTTPException, Response, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.requests import Request

from paper_data_extractor.classifications import create_classification, read_classification
from paper_data_extractor.models import (
    ClassificationRequest,
    ClassificationResponse,
    ModelSummary,
    ReviewDesignRequest,
    ReviewDesignResponse,
    ReviewDesignSummary,
)
from paper_data_extractor.paths import CONTRIBUTED_MODELS_DIR, CUSTOM_MODELS_DIR, PROJECT_ROOT, ensure_data_dirs
from paper_data_extractor.review_schema import compile_review_schema_artifacts
from paper_data_extractor.review_designs import (
    create_review_design,
    delete_review_design,
    download_metamodel_json_schema,
    download_metamodel_yaml,
    list_review_designs,
    load_review_design,
    review_design_to_preview,
    review_design_yaml_text,
)
from paper_data_extractor.taxonomy import (
    compose_taxonomies,
    delete_model,
    list_models,
    load_model,
    save_custom_model,
    taxonomy_to_form_schema,
    yaml_text,
    validate_taxonomy_shape,
)

ensure_data_dirs()

app = FastAPI(title="Paper Data Extractor", version="0.1.0")
app.mount("/static", StaticFiles(directory=PROJECT_ROOT / "static"), name="static")
templates = Jinja2Templates(directory=PROJECT_ROOT / "templates")


@app.get("/", response_class=HTMLResponse)
def index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(request, "index.html")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/models/contributed", response_model=list[ModelSummary])
def contributed_models() -> list[ModelSummary]:
    return list_models(CONTRIBUTED_MODELS_DIR, "contributed")


@app.get("/api/models/custom", response_model=list[ModelSummary])
def custom_models() -> list[ModelSummary]:
    return list_models(CUSTOM_MODELS_DIR, "custom")


@app.get("/api/models", response_model=list[ModelSummary])
def models() -> list[ModelSummary]:
    merged = [
        *list_models(CONTRIBUTED_MODELS_DIR, "contributed"),
        *list_models(CUSTOM_MODELS_DIR, "custom"),
    ]
    return sorted(merged, key=lambda model: (model.title.lower(), model.id.lower()))


@app.get("/api/data-extraction-models", response_model=list[ModelSummary])
def data_extraction_models() -> list[ModelSummary]:
    return models()


@app.get("/api/models/{source}/{model_id}")
def model(source: str, model_id: str) -> dict[str, Any]:
    return load_model(model_id, source)


@app.get("/api/models/{source}/{model_id}/download", response_class=PlainTextResponse)
def download_model(source: str, model_id: str) -> PlainTextResponse:
    taxonomy = load_model(model_id, source)
    return PlainTextResponse(
        content=yaml_text(taxonomy),
        headers={"Content-Disposition": f'attachment; filename="{model_id}.yaml"'},
    )


@app.delete("/api/models/{source}/{model_id}", status_code=204)
def remove_model(source: str, model_id: str) -> Response:
    delete_model(model_id, source)
    return Response(status_code=204)


@app.post("/api/models/custom", response_model=ModelSummary)
async def upload_custom_model(file: UploadFile = File(...)) -> ModelSummary:
    if not file.filename or not file.filename.endswith((".yaml", ".yml")):
        raise HTTPException(status_code=400, detail="Upload a YAML file")
    raw = await file.read()
    try:
        taxonomy = yaml.safe_load(raw) or {}
    except yaml.YAMLError as exc:
        raise HTTPException(status_code=400, detail=f"Invalid YAML: {exc}") from exc
    if not isinstance(taxonomy, dict):
        raise HTTPException(status_code=400, detail="Taxonomy must be a YAML mapping")
    return save_custom_model(taxonomy)


@app.post("/api/models/custom/json", response_model=ModelSummary)
def create_custom_model(taxonomy: dict[str, Any]) -> ModelSummary:
    return save_custom_model(taxonomy)


@app.get("/api/review-designs", response_model=list[ReviewDesignSummary])
def review_designs() -> list[ReviewDesignSummary]:
    return [ReviewDesignSummary(**item) for item in list_review_designs()]


@app.post("/api/review-designs", response_model=ReviewDesignResponse)
def create_review_design_endpoint(request: ReviewDesignRequest) -> ReviewDesignResponse:
    review_design = create_review_design(request.title, request.model_ids)
    preview = review_design_to_preview(review_design)
    return ReviewDesignResponse(
        id=review_design["id"],
        review_design=preview["review_design"],
        form_schema=preview["form_schema"],
        review_linkml_schema=preview["review_linkml_schema"],
        review_json_schema=preview["review_json_schema"],
    )


@app.get("/api/review-designs/{review_design_id}", response_model=ReviewDesignResponse)
def review_design(review_design_id: str) -> ReviewDesignResponse:
    loaded = load_review_design(review_design_id)
    preview = review_design_to_preview(loaded)
    return ReviewDesignResponse(
        id=loaded["id"],
        review_design=preview["review_design"],
        form_schema=preview["form_schema"],
        review_linkml_schema=preview["review_linkml_schema"],
        review_json_schema=preview["review_json_schema"],
    )


@app.get("/api/review-designs/{review_design_id}/download", response_class=PlainTextResponse)
def download_review_design(review_design_id: str) -> PlainTextResponse:
    return PlainTextResponse(
        content=review_design_yaml_text(review_design_id),
        headers={"Content-Disposition": f'attachment; filename="{review_design_id}.yaml"'},
    )


@app.delete("/api/review-designs/{review_design_id}", status_code=204)
def remove_review_design(review_design_id: str) -> Response:
    delete_review_design(review_design_id)
    return Response(status_code=204)


@app.get("/api/metamodels/{kind}/download", response_class=PlainTextResponse)
def download_metamodel(kind: str) -> PlainTextResponse:
    return PlainTextResponse(
        content=download_metamodel_yaml(kind),
        headers={"Content-Disposition": f'attachment; filename="{kind}.yaml"'},
    )


@app.get("/api/metamodels/{kind}/json-schema")
def metamodel_json_schema(kind: str) -> JSONResponse:
    return JSONResponse(download_metamodel_json_schema(kind))


@app.post("/api/models/compose", response_model=ReviewDesignResponse)
def compose_models_compat(request: ReviewDesignRequest) -> ReviewDesignResponse:
    taxonomy = compose_taxonomies(request.model_ids)
    validate_taxonomy_shape(taxonomy)
    review_linkml_schema, review_json_schema = compile_review_schema_artifacts(taxonomy)
    review_design = {
        "id": taxonomy["id"],
        "title": request.title,
        "target_entity": taxonomy.get("target_entity") or "paper",
        "selected_model_ids": request.model_ids,
        "composed_model": taxonomy,
    }
    return ReviewDesignResponse(
        id=taxonomy["id"],
        review_design=review_design,
        form_schema=taxonomy_to_form_schema(taxonomy),
        review_linkml_schema=review_linkml_schema,
        review_json_schema=review_json_schema,
    )


@app.post("/api/classifications", response_model=ClassificationResponse)
def classifications(request: ClassificationRequest) -> ClassificationResponse:
    return create_classification(request)


@app.get("/api/classifications/{classification_id}")
def classification(classification_id: str) -> dict[str, Any]:
    return read_classification(classification_id)


def run() -> None:
    import uvicorn

    uvicorn.run("paper_data_extractor.main:app", host="0.0.0.0", port=8091, reload=True)
