from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Any

import yaml
from fastapi import FastAPI, File, Form, HTTPException, Response, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.requests import Request
from starlette.types import ASGIApp

from paper_data_extractor.classifications import create_classification, read_classification
from paper_data_extractor.llm_prompt import DEFAULT_TAXONOMY_EXTRACTION_PROMPT
from paper_data_extractor.models import (
    ClassificationRequest,
    ClassificationResponse,
    ModelSummary,
    ReviewDesignRequest,
    ReviewDesignResponse,
    ReviewDesignSummary,
    TaxonomyExtractionResponse,
    YamlValidationRequest,
)
from paper_data_extractor.openai_taxonomy_extractor import OpenAITaxonomyExtractor, parse_yaml_mapping
from paper_data_extractor.paths import CONTRIBUTED_MODELS_DIR, CUSTOM_MODELS_DIR, PROJECT_ROOT, ensure_data_dirs
from paper_data_extractor.review_schema import (
    compile_review_schema_artifacts,
    review_linkml_schema_to_owl_xml,
    review_linkml_schema_to_rdf_xml,
    review_linkml_schema_to_shacl_ttl,
)
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
    normalize_taxonomy,
    save_custom_model,
    taxonomy_validation_errors,
    taxonomy_to_form_schema,
    yaml_text,
    validate_taxonomy_shape,
)

ensure_data_dirs()

logger = logging.getLogger(__name__)


def normalize_base_path(value: str | None) -> str:
    normalized = (value or "").strip()
    if not normalized or normalized == "/":
        return ""
    if not normalized.startswith("/"):
        normalized = "/" + normalized
    return normalized.rstrip("/")


PDE_BASE_PATH = normalize_base_path(os.getenv("PAPER_DATA_EXTRACTOR_BASE_PATH", ""))


@dataclass(slots=True)
class CurrentUser:
    id: str
    username: str
    email: str
    display_name: str
    is_admin: bool


app = FastAPI(title="Paper Data Extractor", version="0.1.0", root_path=PDE_BASE_PATH)
app.mount("/static", StaticFiles(directory=PROJECT_ROOT / "static"), name="static")
templates = Jinja2Templates(directory=PROJECT_ROOT / "templates")
templates.env.globals["pde_base_path"] = PDE_BASE_PATH
templates.env.globals["pde_url"] = lambda path="": pde_url(path)
taxonomy_extractor = OpenAITaxonomyExtractor(
    api_key=os.getenv("PAPER_DATA_EXTRACTOR_OPENAI_API_KEY") or os.getenv("OPENAI_API_KEY"),
    model=os.getenv("PAPER_DATA_EXTRACTOR_OPENAI_MODEL", "gpt-5"),
    timeout_seconds=int(os.getenv("PAPER_DATA_EXTRACTOR_OPENAI_TIMEOUT_SECONDS", str(20 * 60))),
)
dev_auth_enabled = os.getenv("PAPER_DATA_EXTRACTOR_DEV_AUTH", "").strip().lower() in {"1", "true", "yes", "on"}
dev_auth_user = CurrentUser(
    id=os.getenv("PAPER_DATA_EXTRACTOR_DEV_USER_ID", "dev-user"),
    username=os.getenv("PAPER_DATA_EXTRACTOR_DEV_USERNAME", "dev"),
    email=os.getenv("PAPER_DATA_EXTRACTOR_DEV_EMAIL", "dev@localhost"),
    display_name=os.getenv("PAPER_DATA_EXTRACTOR_DEV_DISPLAY_NAME", "Local Dev"),
    is_admin=os.getenv("PAPER_DATA_EXTRACTOR_DEV_ADMIN", "true").strip().lower() in {"1", "true", "yes", "on"},
)


def is_public_path(path: str) -> bool:
    return path == "/health" or path.startswith("/static/")


def pde_url(path: str = "") -> str:
    if not path:
        return PDE_BASE_PATH or "/"
    normalized = path if path.startswith("/") else f"/{path}"
    return f"{PDE_BASE_PATH}{normalized}" if PDE_BASE_PATH else normalized


@app.middleware("http")
async def require_authenticated_user(request: Request, call_next):
    if is_public_path(request.url.path):
        return await call_next(request)

    if dev_auth_enabled:
        request.state.current_user = dev_auth_user
        return await call_next(request)

    forwarded_user_id = (request.headers.get("X-Forwarded-User-Id") or "").strip()
    forwarded_username = (request.headers.get("X-Forwarded-Username") or "").strip()
    if not forwarded_user_id or not forwarded_username:
        logger.warning(
            "Rejecting PDE request without forwarded auth headers: path=%s method=%s",
            request.url.path,
            request.method,
        )
        return PlainTextResponse("Authentication is required", status_code=401)

    request.state.current_user = CurrentUser(
        id=forwarded_user_id,
        username=forwarded_username,
        email=(request.headers.get("X-Forwarded-Email") or "").strip(),
        display_name=(request.headers.get("X-Forwarded-Display-Name") or forwarded_username).strip(),
        is_admin=(request.headers.get("X-Forwarded-Admin") or "").strip().lower() == "true",
    )
    return await call_next(request)


@app.get("/", response_class=HTMLResponse)
def index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(
        request,
        "index.html",
        {
            "default_taxonomy_extraction_prompt": DEFAULT_TAXONOMY_EXTRACTION_PROMPT,
            "taxonomy_extraction_enabled": taxonomy_extractor.is_configured(),
            "pde_base_path": PDE_BASE_PATH,
        },
    )


@app.get("/reviews", response_class=HTMLResponse)
def reviews_index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(
        request,
        "reviews.html",
        {"reviews": list_review_designs(), "pde_base_path": PDE_BASE_PATH},
    )


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


@app.post("/api/models/extract-from-paper", response_model=TaxonomyExtractionResponse)
async def extract_model_from_paper(
    file: UploadFile = File(...),
    prompt: str = Form(...),
) -> TaxonomyExtractionResponse:
    logger.info(
        "Taxonomy extraction request received: filename=%s content_type=%s prompt_chars=%d extractor_configured=%s",
        file.filename,
        file.content_type,
        len(prompt or ""),
        taxonomy_extractor.is_configured(),
    )
    if not taxonomy_extractor.is_configured():
        logger.warning("Taxonomy extraction rejected because OpenAI extractor is not configured")
        raise HTTPException(status_code=503, detail="OpenAI extraction is not configured on this server")
    filename = file.filename or "paper.pdf"
    if not filename.lower().endswith(".pdf"):
        logger.warning("Taxonomy extraction rejected because uploaded file is not a PDF: filename=%s", filename)
        raise HTTPException(status_code=400, detail="Upload a PDF file")
    pdf_bytes = await file.read()
    logger.info("Taxonomy extraction upload read: filename=%s bytes=%d", filename, len(pdf_bytes))
    raw_yaml = taxonomy_extractor.extract_yaml(pdf_bytes, filename, prompt)
    logger.info("Taxonomy extraction OpenAI call completed: filename=%s raw_yaml_chars=%d", filename, len(raw_yaml))
    try:
        taxonomy = parse_yaml_mapping(raw_yaml)
    except HTTPException as exc:
        logger.warning("Taxonomy extraction YAML parsing failed: filename=%s detail=%s", filename, exc.detail)
        return TaxonomyExtractionResponse(raw_yaml=raw_yaml, validation_errors=[str(exc.detail)])

    normalized_taxonomy = normalize_taxonomy(taxonomy)
    if normalized_taxonomy != taxonomy:
        logger.info(
            "Taxonomy extraction normalized parsed YAML: filename=%s original_dimensions=%d normalized_dimensions=%d",
            filename,
            len(taxonomy.get("dimensions") or []),
            len(normalized_taxonomy.get("dimensions") or []),
        )
    taxonomy = normalized_taxonomy

    logger.info(
        "Taxonomy extraction YAML parsed: filename=%s keys=%s",
        filename,
        sorted(taxonomy.keys()),
    )
    errors = taxonomy_validation_errors(taxonomy)
    if errors:
        logger.warning(
            "Taxonomy extraction validation failed: filename=%s error_count=%d errors=%s",
            filename,
            len(errors),
            errors,
        )
        return TaxonomyExtractionResponse(raw_yaml=yaml_text(taxonomy), taxonomy=taxonomy, validation_errors=errors)

    logger.info(
        "Taxonomy extraction validated successfully: filename=%s taxonomy_id=%s taxonomy_title=%s dimensions=%d",
        filename,
        taxonomy.get("id"),
        taxonomy.get("title"),
        len(taxonomy.get("dimensions") or []),
    )
    return TaxonomyExtractionResponse(
        raw_yaml=yaml_text(taxonomy),
        taxonomy=taxonomy,
        form_schema=taxonomy_to_form_schema(taxonomy),
        validation_errors=[],
    )


@app.post("/api/models/validate-yaml", response_model=TaxonomyExtractionResponse)
def validate_model_yaml(request: YamlValidationRequest) -> TaxonomyExtractionResponse:
    raw_yaml = request.raw_yaml or ""
    logger.info("Taxonomy YAML validation requested: chars=%d", len(raw_yaml))
    try:
        taxonomy = parse_yaml_mapping(raw_yaml)
    except HTTPException as exc:
        logger.warning("Taxonomy YAML validation parse failed: detail=%s", exc.detail)
        return TaxonomyExtractionResponse(raw_yaml=raw_yaml, validation_errors=[str(exc.detail)])

    normalized_taxonomy = normalize_taxonomy(taxonomy)
    if normalized_taxonomy != taxonomy:
        logger.info(
            "Taxonomy YAML validation normalized YAML: original_dimensions=%d normalized_dimensions=%d",
            len(taxonomy.get("dimensions") or []),
            len(normalized_taxonomy.get("dimensions") or []),
        )
    taxonomy = normalized_taxonomy
    errors = taxonomy_validation_errors(taxonomy)
    if errors:
        logger.warning("Taxonomy YAML validation failed: error_count=%d errors=%s", len(errors), errors)
        return TaxonomyExtractionResponse(raw_yaml=yaml_text(taxonomy), taxonomy=taxonomy, validation_errors=errors)

    logger.info(
        "Taxonomy YAML validation succeeded: taxonomy_id=%s taxonomy_title=%s dimensions=%d",
        taxonomy.get("id"),
        taxonomy.get("title"),
        len(taxonomy.get("dimensions") or []),
    )
    return TaxonomyExtractionResponse(
        raw_yaml=yaml_text(taxonomy),
        taxonomy=taxonomy,
        form_schema=taxonomy_to_form_schema(taxonomy),
        validation_errors=[],
    )


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


@app.get("/review/{review_id}")
def review_template_endpoint(
    review_id: str,
    request: Request,
    format: str | None = None,
    download: bool = False,
) -> Response:
    preview = review_design_to_preview(load_review_design(review_id))
    selected_format = resolve_review_format(request, format)
    filename_base = slug_filename(preview["review_design"]["id"])

    if selected_format == "html":
        if download:
            raise HTTPException(status_code=400, detail="HTML review form preview is not a downloadable artifact.")
        return templates.TemplateResponse(
            request,
            "review_template.html",
            {
                "review_design": preview["review_design"],
                "form_schema": preview["form_schema"],
                "graph_url": pde_url(f"/review/{review_id}/graph"),
                "taxonomy_download_url": review_artifact_url(review_id, "yaml", download=True),
                "linkml_download_url": review_artifact_url(review_id, "linkml", download=True),
                "json_schema_download_url": review_artifact_url(review_id, "json-schema", download=True),
                "rdf_download_url": review_artifact_url(review_id, "rdf", download=True),
                "owl_download_url": review_artifact_url(review_id, "owl", download=True),
                "shacl_download_url": review_artifact_url(review_id, "shacl", download=True),
                "pde_base_path": PDE_BASE_PATH,
            },
        )
    if selected_format == "yaml":
        return artifact_response(
            yaml_text(preview["review_design"]),
            "text/yaml",
            f"{filename_base}.yaml",
            download,
        )
    if selected_format == "linkml":
        return artifact_response(
            yaml_text(preview["review_linkml_schema"]),
            "text/linkml",
            f"{filename_base}.review.linkml.yaml",
            download,
        )
    if selected_format == "json-schema":
        return artifact_response(
            json.dumps(preview["review_json_schema"], indent=2, sort_keys=True),
            "application/schema+json",
            f"{filename_base}.review.schema.json",
            download,
        )
    if selected_format == "rdf":
        return artifact_response(
            review_linkml_schema_to_rdf_xml(preview["review_linkml_schema"]),
            "application/rdf+xml",
            f"{filename_base}.review.rdf",
            download,
            extra_headers={"Link": f'<{review_artifact_url(review_id, "shacl", download=True)}>; rel="describedby"; type="text/turtle"'},
        )
    if selected_format == "owl":
        return artifact_response(
            review_linkml_schema_to_owl_xml(preview["review_linkml_schema"]),
            "application/owl+xml",
            f"{filename_base}.review.owl",
            download,
        )
    if selected_format == "shacl":
        return artifact_response(
            review_linkml_schema_to_shacl_ttl(preview["review_linkml_schema"]),
            "text/turtle",
            f"{filename_base}.review.shacl.ttl",
            download,
        )
    raise HTTPException(status_code=406, detail=f"Unsupported review artifact format: {selected_format}")


@app.get("/review/{review_id}/shacl")
def review_template_shacl(review_id: str, download: bool = False) -> Response:
    preview = review_design_to_preview(load_review_design(review_id))
    filename_base = slug_filename(preview["review_design"]["id"])
    return artifact_response(
        review_linkml_schema_to_shacl_ttl(preview["review_linkml_schema"]),
        "text/turtle",
        f"{filename_base}.review.shacl.ttl",
        download,
    )


@app.get("/review/{review_id}/graph", response_class=HTMLResponse)
def review_graph_endpoint(review_id: str, request: Request) -> HTMLResponse:
    loaded = load_review_design(review_id)
    preview = review_design_to_preview(loaded)
    selected_models = [load_model(model_id) for model_id in loaded.get("selected_model_ids") or []]
    return templates.TemplateResponse(
        request,
        "review_graph.html",
        {
            "review_design": preview["review_design"],
            "selected_models": selected_models,
            "form_url": pde_url(f"/review/{review_id}"),
            "taxonomy_download_url": review_artifact_url(review_id, "yaml", download=True),
            "linkml_download_url": review_artifact_url(review_id, "linkml", download=True),
            "json_schema_download_url": review_artifact_url(review_id, "json-schema", download=True),
            "pde_base_path": PDE_BASE_PATH,
        },
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


def resolve_review_format(request: Request, requested_format: str | None) -> str:
    if requested_format:
        normalized = requested_format.strip().lower()
        if normalized in {"json", "json-schema", "schema"}:
            return "json-schema"
        return normalized

    accept = (request.headers.get("accept") or "").lower()
    if "application/owl+xml" in accept:
        return "owl"
    if "application/rdf+xml" in accept:
        return "rdf"
    if "application/schema+json" in accept:
        return "json-schema"
    if "text/linkml" in accept:
        return "linkml"
    if "text/yaml" in accept or "application/yaml" in accept or "application/x-yaml" in accept:
        return "yaml"
    return "html"


def artifact_response(
    content: str,
    media_type: str,
    filename: str,
    download: bool,
    extra_headers: dict[str, str] | None = None,
) -> Response:
    headers = dict(extra_headers or {})
    if download:
        headers["Content-Disposition"] = f'attachment; filename="{filename}"'
    return Response(content=content, media_type=media_type, headers=headers)


def review_artifact_url(review_id: str, artifact_format: str, download: bool = False) -> str:
    if artifact_format == "shacl":
        return pde_url(f"/review/{review_id}/shacl") + ("?download=true" if download else "")
    suffix = "&download=true" if download else ""
    return pde_url(f"/review/{review_id}?format={artifact_format}{suffix}")


def slug_filename(value: str) -> str:
    return "".join(character if character.isalnum() or character in {"-", "_", "."} else "-" for character in value)
