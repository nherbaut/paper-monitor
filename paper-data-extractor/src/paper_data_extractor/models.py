from typing import Any

from pydantic import BaseModel, Field


class PaperInput(BaseModel):
    id: str
    title: str
    authors: list[str] = Field(default_factory=list)
    abstract: str | None = None
    published_on: str | None = None
    source_link: str | None = None
    doi: str | None = None


class ModelSummary(BaseModel):
    id: str
    title: str
    source: str
    target_entity: str | None = None
    dimension_count: int
    preview_text: str | None = None


class ReviewDesignRequest(BaseModel):
    title: str
    model_ids: list[str]


class ReviewDesignSummary(BaseModel):
    id: str
    title: str
    target_entity: str | None = None
    selected_model_ids: list[str] = Field(default_factory=list)


class ReviewDesignResponse(BaseModel):
    id: str
    review_design: dict[str, Any]
    form_schema: dict[str, Any]
    review_linkml_schema: dict[str, Any] | None = None
    review_json_schema: dict[str, Any] | None = None


class ClassificationRequest(BaseModel):
    paper: PaperInput
    model_ids: list[str] = Field(default_factory=list)
    composed_model_id: str | None = None
    custom_taxonomy: dict[str, Any] | None = None
    values: dict[str, Any]


class ClassificationResponse(BaseModel):
    id: str
    paper_id: str
    composed_model_id: str
    values: dict[str, Any]
    stored_at: str
