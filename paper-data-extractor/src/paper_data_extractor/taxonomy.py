from __future__ import annotations

import hashlib
import html
import json
import logging
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
logger = logging.getLogger(__name__)


class QuotedStringDumper(yaml.SafeDumper):
    pass


def _represent_quoted_string(dumper: yaml.SafeDumper, data: str) -> yaml.nodes.ScalarNode:
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style='"')


QuotedStringDumper.add_representer(str, _represent_quoted_string)


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
    path.write_text(
        yaml.dump(data, Dumper=QuotedStringDumper, sort_keys=False, allow_unicode=False),
        encoding="utf-8",
    )


def yaml_text(data: dict[str, Any]) -> str:
    return yaml.dump(data, Dumper=QuotedStringDumper, sort_keys=False, allow_unicode=False)


def custom_model_metadata_file(path: Path) -> Path:
    return path.with_suffix(".meta.json")


def load_custom_model_metadata(path: Path) -> dict[str, Any]:
    metadata_path = custom_model_metadata_file(path)
    if not metadata_path.exists():
        return {"is_public": True}
    try:
        payload = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        logger.warning("Failed to load custom model metadata from %s: %s", metadata_path, exc)
        return {"is_public": True}
    if not isinstance(payload, dict):
        return {"is_public": True}
    return payload


def save_custom_model_metadata(path: Path, metadata: dict[str, Any]) -> None:
    custom_model_metadata_file(path).write_text(
        json.dumps(metadata, indent=2, sort_keys=True),
        encoding="utf-8",
    )


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or "taxonomy"


def machine_id(value: str) -> str:
    identifier = re.sub(r"[^a-zA-Z0-9]+", "_", value.strip().lower()).strip("_")
    return identifier or "item"


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
    validate_taxonomy_against_metamodel(taxonomy)


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


def normalize_taxonomy(taxonomy: dict[str, Any]) -> dict[str, Any]:
    normalized = normalize_strings(deepcopy(taxonomy))
    normalized["dimensions"] = [normalize_dimension_model(dimension) for dimension in normalized.get("dimensions") or []]
    return normalized


def normalize_strings(value: Any) -> Any:
    if isinstance(value, str):
        return html.unescape(value).replace("\r\n", "\n")
    if isinstance(value, list):
        return [normalize_strings(item) for item in value]
    if isinstance(value, dict):
        return {key: normalize_strings(item) for key, item in value.items()}
    return value


def normalize_dimension_model(dimension: dict[str, Any]) -> dict[str, Any]:
    normalized = deepcopy(dimension)
    promoted_subdimensions: list[dict[str, Any]] = []
    normalized_values: list[Any] = []

    for value in normalized.get("values") or []:
        normalized_value, extra_subdimensions = normalize_taxon_model(value)
        normalized_values.append(normalized_value)
        promoted_subdimensions.extend(extra_subdimensions)

    normalized["values"] = normalized_values
    normalized["subdimensions"] = merge_dimensions(
        [normalize_dimension_model(item) for item in normalized.get("subdimensions") or []],
        promoted_subdimensions,
    )
    return normalized


def normalize_taxon_model(value: Any) -> tuple[Any, list[dict[str, Any]]]:
    if isinstance(value, str):
        return value, []

    normalized = deepcopy(value)
    normalized_children: list[Any] = []
    promoted_subdimensions: list[dict[str, Any]] = []

    for child in normalized.get("children") or []:
        normalized_child, extra_subdimensions = normalize_taxon_model(child)
        normalized_children.append(normalized_child)
        promoted_subdimensions.extend(extra_subdimensions)

    normalized["children"] = normalized_children

    for subdimension in normalized.pop("subdimensions", []) or []:
        if isinstance(subdimension, dict):
            promoted_subdimensions.append(contextualize_promoted_subdimension(subdimension, normalized))

    return normalized, promoted_subdimensions


def contextualize_promoted_subdimension(subdimension: dict[str, Any], taxon: dict[str, Any]) -> dict[str, Any]:
    normalized = normalize_dimension_model(subdimension)
    taxon_id = machine_id(str(taxon.get("id") or taxon.get("label") or "taxon"))
    subdimension_id = machine_id(str(normalized.get("id") or normalized.get("label") or "dimension"))
    qualified_id = f"{taxon_id}_{subdimension_id}"
    if str(normalized.get("id") or "") != qualified_id:
        normalized["id"] = qualified_id
    taxon_label = str(taxon.get("label") or taxon.get("id") or "taxon")
    description_prefix = f"Applies when taxon '{taxon_label}' is selected."
    existing_description = str(normalized.get("description") or "").strip()
    normalized["description"] = f"{description_prefix} {existing_description}".strip()
    normalized["label"] = f"{taxon_label}: {normalized.get('label') or normalized.get('id')}"
    return normalized


def taxonomy_validation_errors(taxonomy: dict[str, Any]) -> list[str]:
    logger.info(
        "Taxonomy validation starting: taxonomy_id=%s title=%s dimensions=%d scales=%d rules=%d",
        taxonomy.get("id"),
        taxonomy.get("title"),
        len(taxonomy.get("dimensions") or []),
        len(taxonomy.get("scales") or []),
        len(taxonomy.get("rules") or []),
    )
    errors: list[str] = []
    try:
        required = ("id", "title", "dimensions")
        missing = [key for key in required if key not in taxonomy]
        if missing:
            errors.append(f"Taxonomy is missing required fields: {', '.join(missing)}")
        elif not isinstance(taxonomy.get("dimensions"), list):
            errors.append("Taxonomy dimensions must be a list")
        else:
            for dimension in taxonomy["dimensions"]:
                validate_dimension(dimension)
            for scale in taxonomy.get("scales") or []:
                validate_scale(scale)
    except HTTPException as exc:
        detail = exc.detail
        if isinstance(detail, list):
            errors.extend(str(item) for item in detail)
        else:
            errors.append(str(detail))

    if errors:
        logger.warning(
            "Taxonomy validation failed during shape checks: taxonomy_id=%s errors=%s",
            taxonomy.get("id"),
            errors,
        )
        return errors

    validate_taxonomy_authoring_model(taxonomy, errors)
    if errors:
        logger.warning(
            "Taxonomy validation failed against authoring metamodel: taxonomy_id=%s errors=%s",
            taxonomy.get("id"),
            errors,
        )
    else:
        logger.info("Taxonomy validation succeeded: taxonomy_id=%s", taxonomy.get("id"))
    return errors


def validate_taxonomy_against_metamodel(taxonomy: dict[str, Any]) -> None:
    errors = taxonomy_validation_errors(taxonomy)
    if errors:
        raise HTTPException(status_code=400, detail=" ; ".join(errors))


def validate_taxonomy_authoring_model(taxonomy: dict[str, Any], errors: list[str]) -> None:
    validate_mapping_keys(
        taxonomy,
        "<root>",
        {"id", "title", "source", "target_entity", "dimensions", "scales", "rules"},
        errors,
    )

    source = taxonomy.get("source")
    if source is not None:
        if not isinstance(source, dict):
            errors.append("source: must be a mapping")
        else:
            validate_mapping_keys(source, "source", {"citation_key", "title", "authors", "year"}, errors)
            authors = source.get("authors")
            if authors is not None and not isinstance(authors, list):
                errors.append("source.authors: must be a list")
            elif isinstance(authors, list):
                for index, author in enumerate(authors):
                    if not isinstance(author, str):
                        errors.append(f"source.authors.{index}: must be a string")
            year = source.get("year")
            if year is not None and not isinstance(year, int):
                errors.append("source.year: must be an integer")

    for index, dimension in enumerate(taxonomy.get("dimensions") or []):
        validate_dimension_authoring_model(dimension, f"dimensions.{index}", errors)

    scales = taxonomy.get("scales") or []
    known_scale_ids = {str(scale.get("id")) for scale in scales if isinstance(scale, dict) and scale.get("id")}
    for index, scale in enumerate(scales):
        validate_scale_authoring_model(scale, f"scales.{index}", errors)

    for index, rule in enumerate(taxonomy.get("rules") or []):
        validate_rule_authoring_model(rule, f"rules.{index}", errors)

    for index, dimension in enumerate(taxonomy.get("dimensions") or []):
        validate_dimension_scale_references(dimension, known_scale_ids, f"dimensions.{index}", errors)


def validate_dimension_authoring_model(dimension: Any, path: str, errors: list[str]) -> None:
    if not isinstance(dimension, dict):
        errors.append(f"{path}: must be a mapping")
        return
    validate_mapping_keys(
        dimension,
        path,
        {"id", "label", "description", "value_type", "cardinality", "required", "values", "subdimensions"},
        errors,
    )
    values = dimension.get("values")
    if values is not None and not isinstance(values, list):
        errors.append(f"{path}.values: must be a list")
    else:
        for index, value in enumerate(values or []):
            validate_taxon_authoring_model(value, f"{path}.values.{index}", errors)

    subdimensions = dimension.get("subdimensions")
    if subdimensions is not None and not isinstance(subdimensions, list):
        errors.append(f"{path}.subdimensions: must be a list")
    else:
        for index, subdimension in enumerate(subdimensions or []):
            validate_dimension_authoring_model(subdimension, f"{path}.subdimensions.{index}", errors)


def validate_taxon_authoring_model(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, str):
        return
    if not isinstance(value, dict):
        errors.append(f"{path}: must be a string or mapping")
        return
    validate_mapping_keys(value, path, {"id", "label", "description", "children", "criteria"}, errors)

    children = value.get("children")
    if children is not None and not isinstance(children, list):
        errors.append(f"{path}.children: must be a list")
    else:
        for index, child in enumerate(children or []):
            validate_taxon_authoring_model(child, f"{path}.children.{index}", errors)

    criteria = value.get("criteria")
    if criteria is not None and not isinstance(criteria, list):
        errors.append(f"{path}.criteria: must be a list")
    else:
        for index, criterion in enumerate(criteria or []):
            validate_criterion_authoring_model(criterion, f"{path}.criteria.{index}", errors)


def validate_criterion_authoring_model(criterion: Any, path: str, errors: list[str]) -> None:
    if not isinstance(criterion, dict):
        errors.append(f"{path}: must be a mapping")
        return
    validate_mapping_keys(criterion, path, {"id", "label", "description", "question", "scale", "required"}, errors)


def validate_scale_authoring_model(scale: Any, path: str, errors: list[str]) -> None:
    if not isinstance(scale, dict):
        errors.append(f"{path}: must be a mapping")
        return
    validate_mapping_keys(scale, path, {"id", "scale_type", "scale_values"}, errors)
    scale_values = scale.get("scale_values")
    if scale_values is not None and not isinstance(scale_values, list):
        errors.append(f"{path}.scale_values: must be a list")
        return
    for index, scale_value in enumerate(scale_values or []):
        if not isinstance(scale_value, dict):
            errors.append(f"{path}.scale_values.{index}: must be a mapping")
            continue
        validate_mapping_keys(
            scale_value,
            f"{path}.scale_values.{index}",
            {"value", "label", "description"},
            errors,
        )


def validate_rule_authoring_model(rule: Any, path: str, errors: list[str]) -> None:
    if not isinstance(rule, dict):
        errors.append(f"{path}: must be a mapping")
        return
    validate_mapping_keys(rule, path, {"id", "description", "applies_to"}, errors)


def validate_dimension_scale_references(
    dimension: Any, known_scale_ids: set[str], path: str, errors: list[str]
) -> None:
    if not isinstance(dimension, dict):
        return
    for index, value in enumerate(dimension.get("values") or []):
        validate_taxon_scale_references(value, known_scale_ids, f"{path}.values.{index}", errors)
    for index, subdimension in enumerate(dimension.get("subdimensions") or []):
        validate_dimension_scale_references(subdimension, known_scale_ids, f"{path}.subdimensions.{index}", errors)


def validate_taxon_scale_references(value: Any, known_scale_ids: set[str], path: str, errors: list[str]) -> None:
    if isinstance(value, str) or not isinstance(value, dict):
        return
    for index, criterion in enumerate(value.get("criteria") or []):
        if not isinstance(criterion, dict):
            continue
        scale_id = criterion.get("scale")
        if isinstance(scale_id, str) and scale_id not in known_scale_ids:
            errors.append(f"{path}.criteria.{index}.scale: unknown scale '{scale_id}'")
    for index, child in enumerate(value.get("children") or []):
        validate_taxon_scale_references(child, known_scale_ids, f"{path}.children.{index}", errors)


def validate_mapping_keys(value: dict[str, Any], path: str, allowed_keys: set[str], errors: list[str]) -> None:
    for key in value:
        if key not in allowed_keys:
            errors.append(f"{path}.{key}: unexpected field")


def model_file_for(directory: Path, model_id: str) -> Path:
    candidate = directory / f"{slugify(model_id)}.yaml"
    if not candidate.exists():
        raise HTTPException(status_code=404, detail=f"Unknown model: {model_id}")
    return candidate


def list_models(
    directory: Path,
    source: str,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> list[ModelSummary]:
    models: list[ModelSummary] = []
    for path in sorted(directory.glob("*.yaml")):
        taxonomy = load_yaml(path)
        metadata = load_custom_model_metadata(path) if source == "custom" else {"is_public": True}
        if source == "custom" and not can_read_custom_model(metadata, current_user_id, is_admin):
            continue
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
        owned_by_current_user = bool(current_user_id and metadata.get("owner_id") == current_user_id)
        can_write = source == "custom" and (is_admin or owned_by_current_user)
        models.append(
            ModelSummary(
                id=str(taxonomy.get("id") or path.stem),
                title=str(taxonomy.get("title") or path.stem),
                source=source,
                target_entity=taxonomy.get("target_entity"),
                dimension_count=len(taxonomy.get("dimensions") or []),
                preview_text=" | ".join(preview_parts) if preview_parts else None,
                is_public=bool(metadata.get("is_public", True)),
                owned_by_current_user=owned_by_current_user,
                can_write=can_write,
                owner_display_name=string_or_none(metadata.get("owner_display_name")),
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


def load_model(
    model_id: str,
    source: str | None = None,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> dict[str, Any]:
    if source is not None:
        path = model_file_for(model_directory(source), model_id)
        enforce_model_readable(path, source, current_user_id, is_admin)
        return load_yaml(path)

    contributed_path = CONTRIBUTED_MODELS_DIR / f"{slugify(model_id)}.yaml"
    custom_path = CUSTOM_MODELS_DIR / f"{slugify(model_id)}.yaml"
    if contributed_path.exists():
        return load_yaml(contributed_path)
    if custom_path.exists():
        enforce_model_readable(custom_path, "custom", current_user_id, is_admin)
        return load_yaml(custom_path)
    raise HTTPException(status_code=404, detail=f"Unknown model: {model_id}")


def save_custom_model(
    taxonomy: dict[str, Any],
    owner_id: str,
    owner_username: str,
    owner_display_name: str,
    is_admin: bool = False,
    is_public: bool | None = None,
) -> ModelSummary:
    taxonomy = normalize_taxonomy(taxonomy)
    validate_taxonomy_shape(taxonomy)
    path = CUSTOM_MODELS_DIR / f"{slugify(str(taxonomy['id']))}.yaml"
    if path.exists():
        raise HTTPException(status_code=409, detail=f"A custom model already exists for id: {taxonomy['id']}")
    dump_yaml(path, taxonomy)
    metadata = {
        "owner_id": owner_id,
        "owner_username": owner_username,
        "owner_display_name": owner_display_name,
        "is_public": bool(is_admin if is_public is None else is_public),
    }
    save_custom_model_metadata(path, metadata)
    return ModelSummary(
        id=str(taxonomy["id"]),
        title=str(taxonomy["title"]),
        source="custom",
        target_entity=taxonomy.get("target_entity"),
        dimension_count=len(taxonomy.get("dimensions") or []),
        preview_text=None,
        is_public=bool(metadata["is_public"]),
        owned_by_current_user=True,
        can_write=True,
        owner_display_name=owner_display_name,
    )


def compose_taxonomies(
    model_ids: list[str],
    custom_taxonomy: dict[str, Any] | None = None,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> dict[str, Any]:
    if not model_ids and custom_taxonomy is None:
        raise HTTPException(status_code=400, detail="At least one model or a custom taxonomy is required")

    selected = [load_model(model_id, current_user_id=current_user_id, is_admin=is_admin) for model_id in model_ids]
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


def delete_model(
    model_id: str,
    source: str,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> None:
    if source != "custom":
        raise HTTPException(status_code=403, detail="Only custom models can be deleted")
    path = model_file_for(model_directory(source), model_id)
    metadata = load_custom_model_metadata(path)
    if not can_write_custom_model(metadata, current_user_id, is_admin):
        raise HTTPException(status_code=403, detail="You do not have permission to delete this model")
    path.unlink()
    metadata_path = custom_model_metadata_file(path)
    if metadata_path.exists():
        metadata_path.unlink()


def update_custom_model_visibility(
    model_id: str,
    is_public: bool,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> ModelSummary:
    path = model_file_for(CUSTOM_MODELS_DIR, model_id)
    metadata = load_custom_model_metadata(path)
    if not can_write_custom_model(metadata, current_user_id, is_admin):
        raise HTTPException(status_code=403, detail="You do not have permission to update this model")
    metadata["is_public"] = bool(is_public)
    save_custom_model_metadata(path, metadata)
    taxonomy = load_yaml(path)
    return ModelSummary(
        id=str(taxonomy.get("id") or path.stem),
        title=str(taxonomy.get("title") or path.stem),
        source="custom",
        target_entity=taxonomy.get("target_entity"),
        dimension_count=len(taxonomy.get("dimensions") or []),
        preview_text=None,
        is_public=bool(metadata["is_public"]),
        owned_by_current_user=bool(current_user_id and metadata.get("owner_id") == current_user_id),
        can_write=bool(is_admin or (current_user_id and metadata.get("owner_id") == current_user_id)),
        owner_display_name=string_or_none(metadata.get("owner_display_name")),
    )


def enforce_model_readable(path: Path, source: str, current_user_id: str | None, is_admin: bool) -> None:
    if source != "custom":
        return
    metadata = load_custom_model_metadata(path)
    if not can_read_custom_model(metadata, current_user_id, is_admin):
        raise HTTPException(status_code=404, detail="Unknown model")


def can_read_custom_model(metadata: dict[str, Any], current_user_id: str | None, is_admin: bool) -> bool:
    if is_admin:
        return True
    if metadata.get("is_public", True):
        return True
    return bool(current_user_id and metadata.get("owner_id") == current_user_id)


def can_write_custom_model(metadata: dict[str, Any], current_user_id: str | None, is_admin: bool) -> bool:
    if is_admin:
        return True
    return bool(current_user_id and metadata.get("owner_id") == current_user_id)


def string_or_none(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


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
