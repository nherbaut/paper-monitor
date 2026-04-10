# Piper API

Small HTTP wrapper around a local `piper` installation.

## Endpoints

- `GET /healthz`
- `GET /voices`
- `POST /v1/speak`

Example request:

```json
{
  "text": "Hello from Paper Monitor",
  "voice": "en_US-lessac-medium"
}
```

## Environment

- `PIPER_BIN`
  Default: `piper`
- `PIPER_MODELS_DIR`
  Default: `/models`
- `PIPER_DEFAULT_VOICE`
  Optional default model name without `.onnx`

## Local run

```bash
cd piper-api
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export PIPER_BIN=/path/to/piper
export PIPER_MODELS_DIR=/path/to/piper-models
uvicorn app:APP --reload --host 0.0.0.0 --port 8090
```

## Notes

- The provided `Dockerfile` is based on `debian:bookworm-slim` and installs:
  - `piper-tts`
  - `pandoc`
  - `texlive-xetex`, `texlive-latex-extra`, `texlive-fonts-recommended`
  - Python 3 + pip
- Models are not bundled into the repository. Mount them into `/models`.
- The service currently returns WAV output directly.
