from __future__ import annotations

import json
from datetime import UTC, datetime
from uuid import uuid4

from fastapi import HTTPException

from paper_data_extractor.models import ClassificationRequest, ClassificationResponse
from paper_data_extractor.paths import CLASSIFICATIONS_DIR
from paper_data_extractor.taxonomy import compose_taxonomies, load_composed_model, taxonomy_to_form_schema


def create_classification(request: ClassificationRequest) -> ClassificationResponse:
    if request.composed_model_id:
        taxonomy = load_composed_model(request.composed_model_id)
    else:
        taxonomy = compose_taxonomies(request.model_ids, request.custom_taxonomy)

    form_schema = taxonomy_to_form_schema(taxonomy)
    validate_values(form_schema, request.values)

    now = datetime.now(UTC)
    classification_id = str(uuid4())
    payload = {
        "id": classification_id,
        "paper": request.paper.model_dump(),
        "composed_model_id": taxonomy["id"],
        "model_ids": request.model_ids,
        "values": request.values,
        "stored_at": now.isoformat(),
    }

    path = CLASSIFICATIONS_DIR / f"{classification_id}.json"
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")

    return ClassificationResponse(
        id=classification_id,
        paper_id=request.paper.id,
        composed_model_id=taxonomy["id"],
        values=request.values,
        stored_at=payload["stored_at"],
    )


def read_classification(classification_id: str) -> dict:
    path = CLASSIFICATIONS_DIR / f"{classification_id}.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="Classification not found")
    return json.loads(path.read_text(encoding="utf-8"))


def validate_values(form_schema: dict, values: dict) -> None:
    for field in form_schema.get("fields") or []:
        validate_field(field, values, form_schema.get("scales") or {})


def validate_field(field: dict, values: dict, scales: dict) -> None:
    field_id = field["id"]
    value = values.get(field_id)
    if field.get("required") and value in (None, "", []):
        raise HTTPException(status_code=400, detail=f"Missing required field: {field_id}")

    if value not in (None, "", []):
        if field["cardinality"] == "single" and isinstance(value, list):
            raise HTTPException(status_code=400, detail=f"Field {field_id} accepts a single value")
        if field["cardinality"] == "multiple" and not isinstance(value, list):
            raise HTTPException(status_code=400, detail=f"Field {field_id} accepts multiple values")

        allowed = collect_option_ids(field.get("values") or [])
        if allowed:
            submitted_values = value if isinstance(value, list) else [value]
            unknown = [item for item in submitted_values if item not in allowed]
            if unknown:
                raise HTTPException(status_code=400, detail=f"Field {field_id} has unknown values: {', '.join(unknown)}")
            validate_selected_criteria(field, submitted_values, values, scales)

    for subfield in field.get("subdimensions") or []:
        validate_field(subfield, values, scales)


def collect_option_ids(options: list[dict]) -> set[str]:
    result: set[str] = set()
    for option in options:
        result.add(option["id"])
        result.update(collect_option_ids(option.get("children") or []))
    return result


def validate_selected_criteria(field: dict, selected_values: list, values: dict, scales: dict) -> None:
    options_by_id = collect_options_by_id(field.get("values") or [])
    for selected in selected_values:
        option = options_by_id.get(str(selected))
        if not option:
            continue
        for criterion in option.get("criteria") or []:
            criterion_id = criterion["id"]
            answer = values.get(criterion_id)
            if criterion.get("required") and answer in (None, "", []):
                raise HTTPException(status_code=400, detail=f"Missing required criterion: {criterion_id}")
            if answer in (None, "", []):
                continue
            scale = scales.get(criterion.get("scale"))
            if not scale:
                continue
            allowed = {str(item["value"]) for item in scale.get("scale_values") or []}
            if allowed and str(answer) not in allowed:
                raise HTTPException(status_code=400, detail=f"Criterion {criterion_id} has an unsupported value")


def collect_options_by_id(options: list[dict]) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for option in options:
        result[str(option["id"])] = option
        result.update(collect_options_by_id(option.get("children") or []))
    return result
