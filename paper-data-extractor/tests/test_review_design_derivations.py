from pathlib import Path

import pytest
from fastapi import HTTPException

from paper_data_extractor import review_designs, taxonomy
from paper_data_extractor.review_schema import taxonomy_to_review_linkml_schema
from paper_data_extractor.taxonomy import dump_yaml


@pytest.fixture()
def design_store(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    reviews = tmp_path / "review_designs"
    composed = tmp_path / "composed_models"
    reviews.mkdir()
    composed.mkdir()
    monkeypatch.setattr(review_designs, "REVIEW_DESIGNS_DIR", reviews)
    monkeypatch.setattr(taxonomy, "COMPOSED_MODELS_DIR", composed)
    base = {
        "id": "standard-review",
        "title": "Standard review",
        "target_entity": "paper",
        "selected_model_ids": [],
        "composed_model": {
            "id": "composed-standard",
            "title": "Standard review",
            "target_entity": "paper",
            "dimensions": [
                {
                    "id": "paper_class",
                    "label": "Paper class",
                    "value_type": "category",
                    "cardinality": "single",
                    "required": True,
                    "values": [{"id": "research", "label": "Research"}],
                },
                {
                    "id": "rq_1",
                    "label": "RQ 1",
                    "value_type": "free_text",
                    "cardinality": "single",
                    "required": False,
                },
            ],
            "scales": [],
            "rules": [],
        },
    }
    dump_yaml(reviews / "standard-review.yaml", base)
    return reviews


def test_derivation_replaces_rqs_and_is_private(design_store: Path) -> None:
    created = review_designs.create_review_design_derivation(
        "standard-review",
        "My review",
        [
            {"question": "What problem is addressed?", "required": True},
            {"question": "What evidence is provided?", "required": False},
        ],
        owner_id="42",
        owner_username="alice",
        owner_display_name="Alice",
    )

    dimensions = created["composed_model"]["dimensions"]
    assert [item["id"] for item in dimensions] == ["paper_class", "rq_1", "rq_2"]
    assert dimensions[1]["label"] == "What problem is addressed?"
    assert dimensions[1]["required"] is True
    assert created["revision"] == 1
    assert len(created["research_questions"]) == 2

    linkml = taxonomy_to_review_linkml_schema(created["composed_model"])
    assert linkml["slots"]["rq_1"]["title"] == "What problem is addressed?"

    assert [item["id"] for item in review_designs.list_review_designs("42")] == [
        "my-review-r1-" + created["derivation_id"].split("-")[0],
        "standard-review",
    ]
    assert [item["id"] for item in review_designs.list_review_designs("99")] == ["standard-review"]
    with pytest.raises(HTTPException) as error:
        review_designs.load_review_design(created["id"], current_user_id="99")
    assert error.value.status_code == 404
    assert review_designs.load_review_design(created["id"], is_admin=True)["id"] == created["id"]


def test_revision_preserves_question_keys_across_reorder_and_reword(design_store: Path) -> None:
    first = review_designs.create_review_design_derivation(
        "standard-review",
        "My review",
        [
            {"question": "First question", "required": False},
            {"question": "Second question", "required": False},
        ],
        owner_id="42",
        owner_username="alice",
        owner_display_name="Alice",
    )
    first_question, second_question = first["research_questions"]

    second = review_designs.create_review_design_revision(
        first["id"],
        "My review",
        [
            {"key": second_question["key"], "question": "Second question, clarified", "required": True},
            {"key": first_question["key"], "question": "First question", "required": False},
        ],
        owner_id="42",
        owner_username="alice",
        owner_display_name="Alice",
    )

    assert second["revision"] == 2
    assert second["previous_revision_id"] == first["id"]
    assert second["derivation_id"] == first["derivation_id"]
    assert second["research_questions"][0]["key"] == second_question["key"]
    assert second["research_questions"][0]["slot_id"] == "rq_1"
    assert second["research_questions"][1]["key"] == first_question["key"]
    assert second["research_questions"][1]["slot_id"] == "rq_2"

    summaries = review_designs.list_review_designs("42")
    revisions = [item for item in summaries if item["derivation_id"] == first["derivation_id"]]
    assert [item["is_latest_revision"] for item in revisions] == [False, True]


def test_rejects_blank_questions(design_store: Path) -> None:
    with pytest.raises(HTTPException) as error:
        review_designs.create_review_design_derivation(
            "standard-review",
            "My review",
            [{"question": "   "}],
            owner_id="42",
            owner_username="alice",
            owner_display_name="Alice",
        )
    assert error.value.status_code == 422
