from __future__ import annotations

import json
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


@app.get("/reviews", response_class=HTMLResponse)
def reviews_index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(request, "reviews.html", {"reviews": list_review_designs()})


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
                "graph_url": f"/review/{review_id}/graph",
                "taxonomy_download_url": review_artifact_url(review_id, "yaml", download=True),
                "linkml_download_url": review_artifact_url(review_id, "linkml", download=True),
                "json_schema_download_url": review_artifact_url(review_id, "json-schema", download=True),
                "rdf_download_url": review_artifact_url(review_id, "rdf", download=True),
                "owl_download_url": review_artifact_url(review_id, "owl", download=True),
                "shacl_download_url": review_artifact_url(review_id, "shacl", download=True),
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
            "form_url": f"/review/{review_id}",
            "taxonomy_download_url": review_artifact_url(review_id, "yaml", download=True),
            "linkml_download_url": review_artifact_url(review_id, "linkml", download=True),
            "json_schema_download_url": review_artifact_url(review_id, "json-schema", download=True),
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
        return f"/review/{review_id}/shacl" + ("?download=true" if download else "")
    suffix = "&download=true" if download else ""
    return f"/review/{review_id}?format={artifact_format}{suffix}"


def slug_filename(value: str) -> str:
    return "".join(character if character.isalnum() or character in {"-", "_", "."} else "-" for character in value)
