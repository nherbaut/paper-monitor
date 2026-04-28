from __future__ import annotations

import argparse
from pathlib import Path

from paper_data_extractor.review_schema import (
    compile_review_schema_artifacts,
    save_review_json_schema,
    save_review_linkml_schema,
)
from paper_data_extractor.taxonomy import load_model, load_yaml


def compile_review_schema_cli() -> None:
    parser = argparse.ArgumentParser(
        description="Compile a taxonomy instance into a derived LinkML review schema and JSON Schema."
    )
    parser.add_argument(
        "taxonomy",
        help="Path to a taxonomy YAML instance, or a model id if --source is provided.",
    )
    parser.add_argument(
        "--source",
        choices=["contributed", "custom", "composed"],
        help="Treat the positional argument as a model id from this source instead of a file path.",
    )
    args = parser.parse_args()

    if args.source:
        taxonomy = load_model(args.taxonomy, args.source)
    else:
        taxonomy = load_yaml(Path(args.taxonomy))

    linkml_schema, json_schema = compile_review_schema_artifacts(taxonomy)
    linkml_path = save_review_linkml_schema(linkml_schema)
    json_schema_path = save_review_json_schema(linkml_schema, json_schema)

    print(f"taxonomy_id: {taxonomy['id']}")
    print(f"review_linkml_schema: {linkml_path}")
    print(f"review_json_schema: {json_schema_path}")


if __name__ == "__main__":
    compile_review_schema_cli()
