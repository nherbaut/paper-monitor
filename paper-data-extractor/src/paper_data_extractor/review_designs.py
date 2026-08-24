from __future__ import annotations

import fcntl
import hashlib
import json
import logging
import re
import tempfile
import uuid
from contextlib import contextmanager
from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml
from fastapi import HTTPException
from linkml.generators.jsonschemagen import JsonSchemaGenerator

from paper_data_extractor.paths import REVIEW_DESIGNS_DIR, SCHEMA_DIR
from paper_data_extractor.review_schema import compile_review_schema_artifacts
from paper_data_extractor.taxonomy import (
    compose_taxonomies,
    load_model,
    load_yaml,
    save_composed_model,
    slugify,
    taxonomy_to_form_schema,
    yaml_text,
)

REVIEW_DESIGN_METAMODEL_PATH = SCHEMA_DIR / "review_design_metamodel.yaml"
logger = logging.getLogger(__name__)
RQ_SLOT_PATTERN = re.compile(r"^rq_[1-9][0-9]*$")


def review_design_metadata_file(path: Path) -> Path:
    return path.with_suffix(".meta.json")


def load_review_design_metadata(path: Path) -> dict[str, Any]:
    metadata_path = review_design_metadata_file(path)
    if not metadata_path.exists():
        return {"is_public": True}
    try:
        payload = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        logger.warning("Failed to load review design metadata from %s: %s", metadata_path, exc)
        return {"is_public": True}
    return payload if isinstance(payload, dict) else {"is_public": True}


def can_read_review_design(metadata: dict[str, Any], current_user_id: str | None, is_admin: bool) -> bool:
    return bool(is_admin or metadata.get("is_public", True) or (
        current_user_id and metadata.get("owner_id") == current_user_id
    ))


def can_write_review_design(metadata: dict[str, Any], current_user_id: str | None, is_admin: bool) -> bool:
    return bool(is_admin or (current_user_id and metadata.get("owner_id") == current_user_id))


def list_review_designs(current_user_id: str | None = None, is_admin: bool = False) -> list[dict[str, Any]]:
    reviews: list[dict[str, Any]] = []
    latest_revisions: dict[str, int] = {}
    readable: list[tuple[Path, dict[str, Any], dict[str, Any]]] = []
    for path in sorted(REVIEW_DESIGNS_DIR.glob("*.yaml")):
        design = load_yaml(path)
        metadata = load_review_design_metadata(path)
        if not can_read_review_design(metadata, current_user_id, is_admin):
            continue
        readable.append((path, design, metadata))
        derivation_id = design.get("derivation_id")
        revision = design.get("revision")
        if derivation_id and isinstance(revision, int):
            latest_revisions[str(derivation_id)] = max(latest_revisions.get(str(derivation_id), 0), revision)

    for _, design, metadata in readable:
        derivation_id = string_or_none(design.get("derivation_id"))
        revision = design.get("revision") if isinstance(design.get("revision"), int) else None
        owned = bool(current_user_id and metadata.get("owner_id") == current_user_id)
        reviews.append(
            {
                "id": str(design.get("id")),
                "title": str(design.get("title") or design.get("id")),
                "target_entity": design.get("target_entity"),
                "selected_model_ids": design.get("selected_model_ids") or [],
                "is_public": bool(metadata.get("is_public", True)),
                "owned_by_current_user": owned,
                "can_write": bool(is_admin or owned),
                "owner_display_name": string_or_none(metadata.get("owner_display_name")),
                "derivation_id": derivation_id,
                "revision": revision,
                "is_latest_revision": not derivation_id or revision == latest_revisions.get(derivation_id),
            }
        )
    return reviews


def review_design_file(review_design_id: str) -> Path:
    path = REVIEW_DESIGNS_DIR / f"{slugify(review_design_id)}.yaml"
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"Unknown review design: {review_design_id}")
    return path


def load_review_design(
    review_design_id: str,
    current_user_id: str | None = None,
    is_admin: bool = False,
) -> dict[str, Any]:
    path = review_design_file(review_design_id)
    metadata = load_review_design_metadata(path)
    if not can_read_review_design(metadata, current_user_id, is_admin):
        raise HTTPException(status_code=404, detail=f"Unknown review design: {review_design_id}")
    return load_yaml(path)


def save_review_design(review_design: dict[str, Any], metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    path = REVIEW_DESIGNS_DIR / f"{slugify(str(review_design['id']))}.yaml"
    if path.exists():
        raise HTTPException(status_code=409, detail=f"A review design already exists for id: {review_design['id']}")
    if metadata is not None:
        atomic_write(review_design_metadata_file(path), json.dumps(metadata, indent=2, sort_keys=True))
    atomic_write(path, yaml_text(review_design))
    return review_design


def delete_review_design(review_design_id: str, current_user_id: str | None = None, is_admin: bool = False) -> None:
    path = review_design_file(review_design_id)
    metadata = load_review_design_metadata(path)
    if not can_write_review_design(metadata, current_user_id, is_admin):
        raise HTTPException(status_code=403, detail="You cannot delete this review design")
    path.unlink()
    metadata_path = review_design_metadata_file(path)
    if metadata_path.exists():
        metadata_path.unlink()


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


def create_review_design_derivation(
    base_review_design_id: str,
    title: str,
    research_questions: list[dict[str, Any]],
    owner_id: str,
    owner_username: str,
    owner_display_name: str,
    is_admin: bool = False,
) -> dict[str, Any]:
    base = load_review_design(base_review_design_id, current_user_id=owner_id, is_admin=is_admin)
    derivation_id = str(uuid.uuid4())
    questions = normalize_research_questions(research_questions)
    design = build_derived_review_design(
        base,
        title,
        questions,
        derivation_id=derivation_id,
        revision=1,
        derived_from_review_design_id=str(base["id"]),
        previous_revision_id=None,
        current_user_id=owner_id,
        is_admin=is_admin,
    )
    return save_review_design(design, owner_metadata(owner_id, owner_username, owner_display_name))


def create_review_design_revision(
    review_design_id: str,
    title: str,
    research_questions: list[dict[str, Any]],
    owner_id: str,
    owner_username: str,
    owner_display_name: str,
    is_admin: bool = False,
) -> dict[str, Any]:
    source_path = review_design_file(review_design_id)
    source_metadata = load_review_design_metadata(source_path)
    if not can_write_review_design(source_metadata, owner_id, is_admin):
        raise HTTPException(status_code=403, detail="You cannot revise this review design")
    source = load_yaml(source_path)
    derivation_id = string_or_none(source.get("derivation_id"))
    if not derivation_id:
        raise HTTPException(status_code=400, detail="Create a derivation before creating revisions")
    questions = normalize_research_questions(research_questions)
    with revision_lock():
        revision = 1 + max(
            (
                int(item.get("revision") or 0)
                for path in REVIEW_DESIGNS_DIR.glob("*.yaml")
                for item in [load_yaml(path)]
                if str(item.get("derivation_id") or "") == derivation_id
            ),
            default=0,
        )
        design = build_derived_review_design(
            source,
            title,
            questions,
            derivation_id=derivation_id,
            revision=revision,
            derived_from_review_design_id=str(source.get("derived_from_review_design_id") or source["id"]),
            previous_revision_id=str(source["id"]),
            current_user_id=owner_id,
            is_admin=is_admin,
        )
        return save_review_design(design, source_metadata)


def build_derived_review_design(
    source: dict[str, Any],
    title: str,
    questions: list[dict[str, Any]],
    *,
    derivation_id: str,
    revision: int,
    derived_from_review_design_id: str,
    previous_revision_id: str | None,
    current_user_id: str,
    is_admin: bool,
) -> dict[str, Any]:
    normalized_title = title.strip()
    if not normalized_title:
        raise HTTPException(status_code=422, detail="A review design title is required")
    composed = deepcopy(source.get("composed_model") or {})
    composed["dimensions"] = [
        dimension for dimension in composed.get("dimensions") or [] if not is_research_question_dimension(dimension)
    ]
    composed["dimensions"].extend(
        {
            "id": question["slot_id"],
            "label": question["question"],
            "value_type": "free_text",
            "cardinality": "single",
            "required": question["required"],
        }
        for question in questions
    )
    composed["title"] = normalized_title
    composed["id"] = composed_model_id(composed)
    save_composed_model(composed)

    selected_model_ids = []
    for model_id in source.get("selected_model_ids") or []:
        try:
            model = load_model(str(model_id), current_user_id=current_user_id, is_admin=is_admin)
        except HTTPException:
            selected_model_ids.append(str(model_id))
            continue
        dimensions = model.get("dimensions") or []
        if dimensions and all(is_research_question_dimension(item) for item in dimensions):
            continue
        selected_model_ids.append(str(model_id))

    short_id = derivation_id.split("-")[0]
    design_id = f"{slugify(normalized_title)}-r{revision}-{short_id}"
    return {
        "id": design_id,
        "title": normalized_title,
        "target_entity": composed.get("target_entity") or "paper",
        "selected_model_ids": selected_model_ids,
        "derivation_id": derivation_id,
        "revision": revision,
        "derived_from_review_design_id": derived_from_review_design_id,
        "previous_revision_id": previous_revision_id,
        "research_questions": questions,
        "composed_model": composed,
    }


def normalize_research_questions(questions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not questions or len(questions) > 50:
        raise HTTPException(status_code=422, detail="Provide between 1 and 50 research questions")
    normalized: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    for index, item in enumerate(questions, start=1):
        question = str(item.get("question") or "").strip()
        if not question:
            raise HTTPException(status_code=422, detail=f"RQ {index} must contain a question")
        if len(question) > 2000:
            raise HTTPException(status_code=422, detail=f"RQ {index} exceeds 2000 characters")
        key = str(item.get("key") or uuid.uuid4()).strip()
        if key in seen_keys:
            raise HTTPException(status_code=422, detail="Research question keys must be unique")
        seen_keys.add(key)
        normalized.append({
            "key": key,
            "slot_id": f"rq_{index}",
            "ordinal": index,
            "question": question,
            "required": bool(item.get("required", False)),
        })
    return normalized


def is_research_question_dimension(dimension: Any) -> bool:
    return bool(
        isinstance(dimension, dict)
        and RQ_SLOT_PATTERN.fullmatch(str(dimension.get("id") or ""))
        and dimension.get("value_type") == "free_text"
    )


def composed_model_id(composed: dict[str, Any]) -> str:
    identity = deepcopy(composed)
    identity.pop("id", None)
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True).encode("utf-8")).hexdigest()[:16]
    return f"composed-{digest}"


def owner_metadata(owner_id: str, username: str, display_name: str) -> dict[str, Any]:
    return {
        "owner_id": owner_id,
        "owner_username": username,
        "owner_display_name": display_name,
        "is_public": False,
    }


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(content)
        temporary = Path(handle.name)
    temporary.replace(path)


@contextmanager
def revision_lock():
    lock_path = REVIEW_DESIGNS_DIR / ".revision.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def string_or_none(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


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
