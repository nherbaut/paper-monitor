from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
from fastapi import HTTPException
from linkml.generators.jsonschemagen import JsonSchemaGenerator

from paper_data_extractor.paths import REVIEW_DESIGNS_DIR, SCHEMA_DIR
from paper_data_extractor.review_schema import compile_review_schema_artifacts
from paper_data_extractor.taxonomy import compose_taxonomies, dump_yaml, load_yaml, slugify, taxonomy_to_form_schema, yaml_text

REVIEW_DESIGN_METAMODEL_PATH = SCHEMA_DIR / "review_design_metamodel.yaml"


def list_review_designs() -> list[dict[str, Any]]:
    reviews: list[dict[str, Any]] = []
    for path in sorted(REVIEW_DESIGNS_DIR.glob("*.yaml")):
        design = load_yaml(path)
        reviews.append(
            {
                "id": str(design.get("id") or path.stem),
                "title": str(design.get("title") or path.stem),
                "target_entity": design.get("target_entity"),
                "selected_model_ids": design.get("selected_model_ids") or [],
            }
        )
    return reviews


def review_design_file(review_design_id: str) -> Path:
    path = REVIEW_DESIGNS_DIR / f"{slugify(review_design_id)}.yaml"
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"Unknown review design: {review_design_id}")
    return path


def load_review_design(review_design_id: str) -> dict[str, Any]:
    return load_yaml(review_design_file(review_design_id))


def save_review_design(review_design: dict[str, Any]) -> dict[str, Any]:
    path = REVIEW_DESIGNS_DIR / f"{slugify(str(review_design['id']))}.yaml"
    if path.exists():
        raise HTTPException(status_code=409, detail=f"A review design already exists for id: {review_design['id']}")
    dump_yaml(path, review_design)
    return review_design


def delete_review_design(review_design_id: str) -> None:
    review_design_file(review_design_id).unlink()


def create_review_design(
    title: str,
    model_ids: list[str],
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> dict[str, Any]:
    if not model_ids:
        raise HTTPException(status_code=400, detail="Select at least one DataExtractionModel.")
    composed_model = compose_taxonomies(model_ids, current_user_id=current_user_id, is_admin=is_admin)
    review_design = {
        "id": slugify(title),
        "title": title,
        "target_entity": composed_model.get("target_entity") or "paper",
        "selected_model_ids": model_ids,
        "composed_model": composed_model,
    }
    return save_review_design(review_design)


def review_design_to_preview(review_design: dict[str, Any]) -> dict[str, Any]:
    composed_model = review_design["composed_model"]
    review_linkml_schema, review_json_schema = compile_review_schema_artifacts(composed_model)
    return {
        "review_design": review_design,
        "form_schema": taxonomy_to_form_schema(composed_model),
        "review_linkml_schema": review_linkml_schema,
        "review_json_schema": review_json_schema,
    }


def review_design_yaml_text(review_design_id: str) -> str:
    return yaml_text(load_review_design(review_design_id))


def download_metamodel_yaml(kind: str) -> str:
    paths = {
        "data-extraction-model": SCHEMA_DIR / "data_extraction_model_metamodel.yaml",
        "review-design": REVIEW_DESIGN_METAMODEL_PATH,
    }
    path = paths.get(kind)
    if path is None:
        raise HTTPException(status_code=404, detail=f"Unknown metamodel: {kind}")
    return path.read_text(encoding="utf-8")


def download_metamodel_json_schema(kind: str) -> dict[str, Any]:
    paths = {
        "data-extraction-model": (SCHEMA_DIR / "data_extraction_model_metamodel.yaml", "DataExtractionModel"),
        "review-design": (REVIEW_DESIGN_METAMODEL_PATH, "ReviewDesign"),
    }
    target = paths.get(kind)
    if target is None:
        raise HTTPException(status_code=404, detail=f"Unknown metamodel: {kind}")
    path, top_class = target
    generator = JsonSchemaGenerator(str(path), top_class=top_class)
    return yaml.safe_load(generator.serialize())
