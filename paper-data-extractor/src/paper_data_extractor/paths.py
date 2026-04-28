import os
from pathlib import Path


PROJECT_ROOT = Path(os.environ.get("PROJECT_ROOT", Path(__file__).resolve().parents[2])).expanduser().resolve()
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
