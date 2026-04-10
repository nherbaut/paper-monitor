from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path

import json

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, ValidationError
from starlette.background import BackgroundTask


APP = FastAPI(title="piper-api", version="0.1.0")

PIPER_BIN = os.environ.get("PIPER_BIN", "piper")
PIPER_MODELS_DIR = Path(os.environ.get("PIPER_MODELS_DIR", "/models"))
PIPER_DEFAULT_VOICE = os.environ.get("PIPER_DEFAULT_VOICE", "").strip()


class SpeakRequest(BaseModel):
    text: str = Field(min_length=1, max_length=20000)
    voice: str | None = None
    speaker: int | None = None
    noise_scale: float | None = None
    length_scale: float | None = None
    noise_w: float | None = None


def available_voices() -> list[str]:
    if not PIPER_MODELS_DIR.exists():
        return []
    return sorted(path.stem for path in PIPER_MODELS_DIR.glob("*.onnx"))


def resolve_model_path(voice: str | None) -> Path:
    selected = (voice or PIPER_DEFAULT_VOICE).strip()
    if not selected:
        voices = available_voices()
        if not voices:
            raise HTTPException(status_code=500, detail="no piper voice models are available")
        selected = voices[0]

    model_path = PIPER_MODELS_DIR / (selected + ".onnx")
    if not model_path.exists():
        raise HTTPException(status_code=404, detail=f"voice model not found: {selected}")
    return model_path


def build_command(model_path: Path, output_path: Path, request: SpeakRequest) -> list[str]:
    command = [
        PIPER_BIN,
        "--model",
        str(model_path),
        "--output_file",
        str(output_path),
    ]
    if request.speaker is not None:
        command.extend(["--speaker", str(request.speaker)])
    if request.noise_scale is not None:
        command.extend(["--noise_scale", str(request.noise_scale)])
    if request.length_scale is not None:
        command.extend(["--length_scale", str(request.length_scale)])
    if request.noise_w is not None:
        command.extend(["--noise_w", str(request.noise_w)])
    return command


def cleanup_output(output_path: Path) -> None:
    try:
        output_path.unlink(missing_ok=True)
    finally:
        output_path.parent.rmdir()


@APP.get("/healthz")
def healthz() -> dict[str, object]:
    return {
        "ok": True,
        "piper_bin": PIPER_BIN,
        "models_dir": str(PIPER_MODELS_DIR),
        "voices": available_voices(),
        "default_voice": PIPER_DEFAULT_VOICE or None,
    }


@APP.get("/voices")
def voices() -> dict[str, object]:
    return {
        "voices": available_voices(),
        "default_voice": PIPER_DEFAULT_VOICE or None,
    }


async def parse_speak_request(request: Request) -> SpeakRequest:
    content_type = (request.headers.get("content-type") or "").lower()

    if "application/json" not in content_type:
        raise HTTPException(status_code=415, detail="content-type must be application/json")

    raw_body = await request.body()
    if not raw_body.strip():
        raise HTTPException(status_code=400, detail="request body is required")

    try:
        payload = json.loads(raw_body.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="invalid JSON body") from exc

    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="JSON body must be an object")

    try:
        return SpeakRequest.model_validate(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc


@APP.post("/v1/speak")
async def speak(request: Request) -> FileResponse:
    speak_request = await parse_speak_request(request)
    model_path = resolve_model_path(speak_request.voice)

    temp_dir = Path(tempfile.mkdtemp(prefix="piper-api-"))
    output_path = temp_dir / "speech.wav"
    command = build_command(model_path, output_path, speak_request)

    try:
        result = subprocess.run(
            command,
            input=speak_request.text,
            text=True,
            capture_output=True,
            check=False,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=500, detail=f"piper binary not found: {PIPER_BIN}") from exc

    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "piper synthesis failed").strip()
        raise HTTPException(status_code=500, detail=detail)

    if not output_path.exists():
        raise HTTPException(status_code=500, detail="piper did not produce an output file")

    return FileResponse(
        path=output_path,
        media_type="audio/wav",
        filename="speech.wav",
        background=BackgroundTask(cleanup_output, output_path),
    )
