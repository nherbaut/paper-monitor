from __future__ import annotations

import base64
import json
import logging
from typing import Any
from urllib import error, request

import yaml
from fastapi import HTTPException

logger = logging.getLogger(__name__)


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
        logger.info(
            "OpenAI taxonomy extraction starting: filename=%s bytes=%d prompt_chars=%d model=%s",
            filename,
            len(pdf_bytes),
            len(prompt or ""),
            self.model,
        )
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
                logger.info(
                    "OpenAI taxonomy extraction HTTP response received: filename=%s status=%s",
                    filename,
                    getattr(response, "status", "unknown"),
                )
                data = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            logger.error(
                "OpenAI taxonomy extraction HTTP error: filename=%s status=%s detail=%s",
                filename,
                exc.code,
                detail,
            )
            raise HTTPException(status_code=502, detail=f"OpenAI API error: {detail}") from exc
        except error.URLError as exc:
            logger.error("OpenAI taxonomy extraction URL error: filename=%s reason=%s", filename, exc.reason)
            raise HTTPException(status_code=502, detail=f"Could not reach OpenAI API: {exc.reason}") from exc
        except TimeoutError as exc:
            logger.error("OpenAI taxonomy extraction timed out: filename=%s", filename)
            raise HTTPException(status_code=504, detail="Timed out while waiting for the OpenAI API") from exc
        raw_text = extract_output_text(data).strip()
        if not raw_text:
            logger.error("OpenAI taxonomy extraction returned empty output: filename=%s payload_keys=%s", filename, sorted(data.keys()))
            raise HTTPException(status_code=502, detail="OpenAI API returned an empty response")
        logger.info(
            "OpenAI taxonomy extraction completed: filename=%s output_chars=%d",
            filename,
            len(raw_text),
        )
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
        logger.warning("Generated taxonomy YAML parsing error: %s", exc)
        raise HTTPException(status_code=422, detail=f"Generated YAML is invalid: {exc}") from exc
    if not isinstance(parsed, dict):
        logger.warning("Generated taxonomy YAML parsed to non-mapping type: %s", type(parsed).__name__)
        raise HTTPException(status_code=422, detail="Generated YAML must contain a mapping at the top level")
    logger.info("Generated taxonomy YAML parsed successfully with keys=%s", sorted(parsed.keys()))
    return parsed
