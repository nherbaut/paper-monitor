from __future__ import annotations

import base64
import json
from typing import Any
from urllib import error, request

import yaml
from fastapi import HTTPException


class OpenAITaxonomyExtractor:
    def __init__(self, api_key: str | None, model: str = "gpt-5", base_url: str = "https://api.openai.com/v1/responses") -> None:
        self.api_key = (api_key or "").strip()
        self.model = model.strip() or "gpt-5"
        self.base_url = base_url

    def is_configured(self) -> bool:
        return bool(self.api_key)

    def extract_yaml(self, pdf_bytes: bytes, filename: str, prompt: str) -> str:
        if not self.is_configured():
            raise HTTPException(status_code=503, detail="OpenAI extraction is not configured on this server")
        if not pdf_bytes:
            raise HTTPException(status_code=400, detail="Upload a non-empty PDF file")
        payload = {
            "model": self.model,
            "input": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_file",
                            "filename": filename or "paper.pdf",
                            "file_data": base64.b64encode(pdf_bytes).decode("ascii"),
                        },
                        {
                            "type": "input_text",
                            "text": prompt.strip(),
                        },
                    ],
                }
            ],
        }
        body = json.dumps(payload).encode("utf-8")
        http_request = request.Request(
            self.base_url,
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with request.urlopen(http_request, timeout=180) as response:
                data = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise HTTPException(status_code=502, detail=f"OpenAI API error: {detail}") from exc
        except error.URLError as exc:
            raise HTTPException(status_code=502, detail=f"Could not reach OpenAI API: {exc.reason}") from exc
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail="Timed out while waiting for the OpenAI API") from exc
        raw_text = extract_output_text(data).strip()
        if not raw_text:
            raise HTTPException(status_code=502, detail="OpenAI API returned an empty response")
        return strip_code_fences(raw_text)


def extract_output_text(payload: dict[str, Any]) -> str:
    output_text = payload.get("output_text")
    if isinstance(output_text, str) and output_text.strip():
        return output_text

    collected: list[str] = []
    for item in payload.get("output") or []:
        for content in item.get("content") or []:
            text = content.get("text")
            if isinstance(text, str) and text.strip():
                collected.append(text)
    return "\n".join(collected).strip()


def strip_code_fences(text: str) -> str:
    stripped = text.strip()
    if stripped.startswith("```") and stripped.endswith("```"):
        lines = stripped.splitlines()
        if len(lines) >= 3:
            return "\n".join(lines[1:-1]).strip()
    return stripped


def parse_yaml_mapping(raw_yaml: str) -> dict[str, Any]:
    try:
        parsed = yaml.safe_load(raw_yaml) or {}
    except yaml.YAMLError as exc:
        raise HTTPException(status_code=422, detail=f"Generated YAML is invalid: {exc}") from exc
    if not isinstance(parsed, dict):
        raise HTTPException(status_code=422, detail="Generated YAML must contain a mapping at the top level")
    return parsed
