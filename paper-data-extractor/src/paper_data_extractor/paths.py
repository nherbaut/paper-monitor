import os
from pathlib import Path


def resolve_project_root() -> Path:
    candidates = []
    env_root = os.environ.get("PROJECT_ROOT")
    if env_root:
        candidates.append(Path(env_root).expanduser())
    candidates.append(Path(__file__).resolve().parents[2])
    candidates.append(Path("/app"))
    candidates.append(Path.cwd())

    for candidate in candidates:
        resolved = candidate.resolve()
        if (resolved / "static").is_dir() and (resolved / "templates").is_dir():
            return resolved
    return candidates[0].resolve()


PROJECT_ROOT = resolve_project_root()
DATA_DIR = PROJECT_ROOT / "data"
SCHEMA_DIR = PROJECT_ROOT / "schemas"
CONTRIBUTED_MODELS_DIR = DATA_DIR / "contributed_models"
CUSTOM_MODELS_DIR = DATA_DIR / "custom_models"
COMPOSED_MODELS_DIR = DATA_DIR / "composed_models"
REVIEW_DESIGNS_DIR = DATA_DIR / "review_designs"
CLASSIFICATIONS_DIR = DATA_DIR / "classifications"
GENERATED_SCHEMAS_DIR = DATA_DIR / "generated_schemas"


def ensure_data_dirs() -> None:
    for directory in (
        CONTRIBUTED_MODELS_DIR,
        CUSTOM_MODELS_DIR,
        COMPOSED_MODELS_DIR,
        REVIEW_DESIGNS_DIR,
        CLASSIFICATIONS_DIR,
        GENERATED_SCHEMAS_DIR,
    ):
        directory.mkdir(parents=True, exist_ok=True)
