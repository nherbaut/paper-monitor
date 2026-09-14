import json

import pytest
from fastapi import HTTPException

from paper_data_extractor import openai_taxonomy_extractor
from paper_data_extractor.openai_taxonomy_extractor import OpenAITaxonomyExtractor


class FakeResponse:
    def __init__(self, payload: dict):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


def test_paper_analysis_returns_structured_abstract_and_review_values(monkeypatch):
    extractor = OpenAITaxonomyExtractor("secret")
    deleted: list[str] = []
    monkeypatch.setattr(extractor, "upload_pdf", lambda *_args: "file-123")
    monkeypatch.setattr(extractor, "delete_file", deleted.append)
    monkeypatch.setattr(
        openai_taxonomy_extractor.request,
        "urlopen",
        lambda *_args, **_kwargs: FakeResponse({
            "output_text": json.dumps({
                "structured_abstract_markdown": "INTRODUCTION: Context.",
                "review_values": {"rq_1": "Answer"},
            })
        }),
    )

    result = extractor.analyze_paper(b"%PDF", "paper.pdf", "Analyze it")

    assert result["structured_abstract_markdown"] == "INTRODUCTION: Context."
    assert result["review_values"] == {"rq_1": "Answer"}
    assert deleted == ["file-123"]


def test_paper_analysis_deletes_uploaded_file_after_invalid_output(monkeypatch):
    extractor = OpenAITaxonomyExtractor("secret")
    deleted: list[str] = []
    monkeypatch.setattr(extractor, "upload_pdf", lambda *_args: "file-456")
    monkeypatch.setattr(extractor, "delete_file", deleted.append)
    monkeypatch.setattr(
        openai_taxonomy_extractor.request,
        "urlopen",
        lambda *_args, **_kwargs: FakeResponse({"output_text": "not json"}),
    )

    with pytest.raises(HTTPException) as error:
        extractor.analyze_paper(b"%PDF", "paper.pdf", "Analyze it")

    assert error.value.status_code == 502
    assert deleted == ["file-456"]
