from __future__ import annotations

import hashlib
import json
import os
import re
from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml
from fastapi import HTTPException

from paper_data_extractor.models import ModelSummary
from paper_data_extractor.paths import (
    COMPOSED_MODELS_DIR,
    CONTRIBUTED_MODELS_DIR,
    CUSTOM_MODELS_DIR,
    SCHEMA_DIR,
)

os.environ.setdefault("PYSTOW_HOME", str(SCHEMA_DIR.parent / "data" / ".pystow"))

from linkml_runtime.utils.schemaview import SchemaView

METAMODEL_PATH = SCHEMA_DIR / "data_extraction_model_metamodel.yaml"


def load_metamodel() -> SchemaView:
    return SchemaView(str(METAMODEL_PATH))


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle) or {}
    except yaml.YAMLError as exc:
        raise HTTPException(status_code=400, detail=f"Invalid YAML in {path.name}: {exc}") from exc
    if not isinstance(data, dict):
        raise HTTPException(status_code=400, detail=f"{path.name} must contain a YAML mapping")
    return data


def dump_yaml(path: Path, data: dict[str, Any]) -> None:
    path.write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=False), encoding="utf-8")


def yaml_text(data: dict[str, Any]) -> str:
    return yaml.safe_dump(data, sort_keys=False, allow_unicode=False)


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or "taxonomy"


def validate_taxonomy_shape(taxonomy: dict[str, Any]) -> None:
    # Load the LinkML meta-model so startup and validation fail early if it is invalid.
    load_metamodel()

    required = ("id", "title", "dimensions")
    missing = [key for key in required if key not in taxonomy]
    if missing:
        raise HTTPException(status_code=400, detail=f"Taxonomy is missing required fields: {', '.join(missing)}")
    if not isinstance(taxonomy["dimensions"], list):
        raise HTTPException(status_code=400, detail="Taxonomy dimensions must be a list")
    for dimension in taxonomy["dimensions"]:
        validate_dimension(dimension)
    for scale in taxonomy.get("scales") or []:
        validate_scale(scale)


def validate_dimension(dimension: Any) -> None:
    if not isinstance(dimension, dict):
        raise HTTPException(status_code=400, detail="Each dimension must be a mapping")
    for key in ("id", "label", "value_type", "cardinality"):
        if key not in dimension:
            raise HTTPException(status_code=400, detail=f"Dimension is missing required field: {key}")
    if dimension["value_type"] not in {"category", "criterion", "method", "free_text", "numeric"}:
        raise HTTPException(status_code=400, detail=f"Unsupported value_type: {dimension['value_type']}")
    if dimension["cardinality"] not in {"single", "multiple"}:
        raise HTTPException(status_code=400, detail=f"Unsupported cardinality: {dimension['cardinality']}")
    for value in dimension.get("values") or []:
        validate_taxon(value)
    for subdimension in dimension.get("subdimensions") or []:
        validate_dimension(subdimension)


def validate_taxon(value: Any) -> None:
    if isinstance(value, str):
        return
    if not isinstance(value, dict):
        raise HTTPException(status_code=400, detail="Each taxon must be a string or mapping")
    if "id" not in value or "label" not in value:
        raise HTTPException(status_code=400, detail="Taxon mappings must include id and label")
    for child in value.get("children") or []:
        validate_taxon(child)
    for criterion in value.get("criteria") or []:
        validate_criterion(criterion)


def validate_criterion(criterion: Any) -> None:
    if not isinstance(criterion, dict):
        raise HTTPException(status_code=400, detail="Each criterion must be a mapping")
    for key in ("id", "label", "question", "scale"):
        if key not in criterion:
            raise HTTPException(status_code=400, detail=f"Criterion is missing required field: {key}")


def validate_scale(scale: Any) -> None:
    if not isinstance(scale, dict):
        raise HTTPException(status_code=400, detail="Each scale must be a mapping")
    for key in ("id", "scale_type", "scale_values"):
        if key not in scale:
            raise HTTPException(status_code=400, detail=f"Scale is missing required field: {key}")
    if scale["scale_type"] not in {"binary", "ordinal", "nominal", "numeric", "free_text"}:
        raise HTTPException(status_code=400, detail=f"Unsupported scale_type: {scale['scale_type']}")


def model_file_for(directory: Path, model_id: str) -> Path:
    candidate = directory / f"{slugify(model_id)}.yaml"
    if not candidate.exists():
        raise HTTPException(status_code=404, detail=f"Unknown model: {model_id}")
    return candidate


def list_models(directory: Path, source: str) -> list[ModelSummary]:
    models: list[ModelSummary] = []
    for path in sorted(directory.glob("*.yaml")):
        taxonomy = load_yaml(path)
        dimension_labels = [
            str(dimension.get("label") or dimension.get("id") or "")
            for dimension in (taxonomy.get("dimensions") or [])
            if isinstance(dimension, dict)
        ]
        preview_parts = []
        if dimension_labels:
            preview_parts.append(f"Dimensions: {', '.join(dimension_labels[:3])}")
            if len(dimension_labels) > 3:
                preview_parts[-1] += ", ..."
        if taxonomy.get("source", {}).get("title"):
            preview_parts.append(f"Source: {taxonomy['source']['title']}")
        models.append(
            ModelSummary(
                id=str(taxonomy.get("id") or path.stem),
                title=str(taxonomy.get("title") or path.stem),
                source=source,
                target_entity=taxonomy.get("target_entity"),
                dimension_count=len(taxonomy.get("dimensions") or []),
                preview_text=" | ".join(preview_parts) if preview_parts else None,
            )
        )
    return models


def model_directory(source: str) -> Path:
    directories = {
        "contributed": CONTRIBUTED_MODELS_DIR,
        "custom": CUSTOM_MODELS_DIR,
        "composed": COMPOSED_MODELS_DIR,
    }
    directory = directories.get(source)
    if directory is None:
        raise HTTPException(status_code=400, detail=f"Unknown model source: {source}")
    return directory


def load_model(model_id: str, source: str | None = None) -> dict[str, Any]:
    if source is not None:
        return load_yaml(model_file_for(model_directory(source), model_id))

    contributed_path = CONTRIBUTED_MODELS_DIR / f"{slugify(model_id)}.yaml"
    custom_path = CUSTOM_MODELS_DIR / f"{slugify(model_id)}.yaml"
    if contributed_path.exists():
        return load_yaml(contributed_path)
    if custom_path.exists():
        return load_yaml(custom_path)
    raise HTTPException(status_code=404, detail=f"Unknown model: {model_id}")


def save_custom_model(taxonomy: dict[str, Any]) -> ModelSummary:
    validate_taxonomy_shape(taxonomy)
    path = CUSTOM_MODELS_DIR / f"{slugify(str(taxonomy['id']))}.yaml"
    if path.exists():
        raise HTTPException(status_code=409, detail=f"A custom model already exists for id: {taxonomy['id']}")
    dump_yaml(path, taxonomy)
    return ModelSummary(
        id=str(taxonomy["id"]),
        title=str(taxonomy["title"]),
        source="custom",
        target_entity=taxonomy.get("target_entity"),
        dimension_count=len(taxonomy.get("dimensions") or []),
        preview_text=None,
    )


def compose_taxonomies(model_ids: list[str], custom_taxonomy: dict[str, Any] | None = None) -> dict[str, Any]:
    if not model_ids and custom_taxonomy is None:
        raise HTTPException(status_code=400, detail="At least one model or a custom taxonomy is required")

    selected = [load_model(model_id) for model_id in model_ids]
    if custom_taxonomy is not None:
        validate_taxonomy_shape(custom_taxonomy)
        selected.append(custom_taxonomy)

    composed = {
        "id": stable_composed_id(selected),
        "title": " + ".join(str(item.get("title") or item.get("id")) for item in selected),
        "target_entity": first_present(selected, "target_entity") or "paper",
        "source": first_present(selected, "source"),
        "dimensions": [],
        "scales": [],
        "rules": [],
    }

    dimensions_by_id: dict[str, dict[str, Any]] = {}
    scales_by_id: dict[str, dict[str, Any]] = {}
    for taxonomy in selected:
        for dimension in taxonomy.get("dimensions") or []:
            merge_dimension(dimensions_by_id, deepcopy(dimension))
        for scale in taxonomy.get("scales") or []:
            scales_by_id[str(scale["id"])] = deepcopy(scale)
        composed["rules"].extend(deepcopy(taxonomy.get("rules") or []))

    composed["dimensions"] = list(dimensions_by_id.values())
    composed["scales"] = list(scales_by_id.values())
    save_composed_model(composed)
    return composed


def stable_composed_id(taxonomies: list[dict[str, Any]]) -> str:
    identity = json.dumps(
        [
            {
                "id": item.get("id"),
                "title": item.get("title"),
                "dimensions": item.get("dimensions"),
                "scales": item.get("scales"),
            }
            for item in taxonomies
        ],
        sort_keys=True,
    )
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]
    return f"composed-{digest}"


def first_present(taxonomies: list[dict[str, Any]], key: str) -> Any:
    for taxonomy in taxonomies:
        if taxonomy.get(key) not in (None, "", []):
            return deepcopy(taxonomy[key])
    return None


def merge_dimension(dimensions_by_id: dict[str, dict[str, Any]], dimension: dict[str, Any]) -> None:
    dimension_id = str(dimension["id"])
    existing = dimensions_by_id.get(dimension_id)
    if existing is None:
        dimensions_by_id[dimension_id] = dimension
        return

    existing.setdefault("values", [])
    existing.setdefault("subdimensions", [])
    existing["values"] = merge_items_by_id(existing.get("values") or [], dimension.get("values") or [])
    existing["subdimensions"] = merge_dimensions(existing.get("subdimensions") or [], dimension.get("subdimensions") or [])


def merge_dimensions(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_id = {str(item["id"]): deepcopy(item) for item in left}
    for item in right:
        merge_dimension(by_id, deepcopy(item))
    return list(by_id.values())


def merge_items_by_id(left: list[Any], right: list[Any]) -> list[Any]:
    result = deepcopy(left)
    known = {item_id(item) for item in result}
    for item in right:
        key = item_id(item)
        if key not in known:
            result.append(deepcopy(item))
            known.add(key)
    return result


def item_id(item: Any) -> str:
    if isinstance(item, str):
        return item
    return str(item.get("id"))


def save_composed_model(taxonomy: dict[str, Any]) -> None:
    dump_yaml(COMPOSED_MODELS_DIR / f"{taxonomy['id']}.yaml", taxonomy)


def load_composed_model(model_id: str) -> dict[str, Any]:
    return load_yaml(model_file_for(COMPOSED_MODELS_DIR, model_id))


def delete_model(model_id: str, source: str) -> None:
    path = model_file_for(model_directory(source), model_id)
    path.unlink()


def taxonomy_to_form_schema(taxonomy: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": taxonomy["id"],
        "title": taxonomy["title"],
        "target_entity": taxonomy.get("target_entity") or "paper",
        "scales": {scale["id"]: scale_to_form_schema(scale) for scale in taxonomy.get("scales") or []},
        "fields": [dimension_to_field(dimension) for dimension in taxonomy.get("dimensions") or []],
    }


def dimension_to_field(dimension: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": dimension["id"],
        "label": dimension.get("label") or dimension["id"],
        "description": dimension.get("description"),
        "value_type": dimension["value_type"],
        "cardinality": dimension["cardinality"],
        "required": bool(dimension.get("required", False)),
        "values": [value_to_option(value) for value in dimension.get("values") or []],
        "subdimensions": [dimension_to_field(item) for item in dimension.get("subdimensions") or []],
    }


def value_to_option(value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        return {"id": value, "label": value, "children": [], "criteria": []}
    return {
        "id": value["id"],
        "label": value.get("label") or value["id"],
        "description": value.get("description"),
        "children": [value_to_option(child) for child in value.get("children") or []],
        "criteria": value.get("criteria") or [],
    }


def scale_to_form_schema(scale: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": scale["id"],
        "scale_type": scale["scale_type"],
        "scale_values": scale.get("scale_values") or [],
    }
