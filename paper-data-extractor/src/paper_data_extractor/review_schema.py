from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from linkml import LOCAL_METAMODEL_LDCONTEXT_FILE, LOCAL_TYPES_LDCONTEXT_FILE
from linkml.generators.jsonldgen import JSONLDGenerator
from linkml.generators.jsonschemagen import JsonSchemaGenerator
from linkml.generators.owlgen import OwlSchemaGenerator
from linkml.generators.shaclgen import ShaclGenerator
from rdflib import Graph

from paper_data_extractor.paths import GENERATED_SCHEMAS_DIR
from paper_data_extractor.taxonomy import dump_yaml, slugify


def taxonomy_to_review_linkml_schema(taxonomy: dict[str, Any]) -> dict[str, Any]:
    taxonomy_id = str(taxonomy["id"])
    schema_name = f"{slugify(taxonomy_id).replace('-', '_')}_review_schema"
    paper_review_class = "PaperReview"

    schema: dict[str, Any] = {
        "id": f"https://papers.home.nextnet.top/generated/{schema_name}",
        "name": schema_name,
        "prefixes": {
            "ex": "https://papers.home.nextnet.top/generated/",
            "linkml": "https://w3id.org/linkml/",
        },
        "imports": ["linkml:types"],
        "default_prefix": "ex",
        "classes": {
            paper_review_class: {
                "tree_root": True,
                "description": f"Review answers for taxonomy {taxonomy.get('title') or taxonomy_id}",
                "slots": [],
            }
        },
        "slots": {},
        "enums": {},
    }

    add_core_slot(schema, "paper_id", "string", required=True, description="Paper identifier in Paper Monitor.")
    add_core_slot(schema, "taxonomy_id", "string", required=True, description="Composed taxonomy identifier.")
    schema["classes"][paper_review_class]["slots"].extend(["paper_id", "taxonomy_id"])

    scales = {str(scale["id"]): deepcopy(scale) for scale in taxonomy.get("scales") or []}
    slot_order: list[str] = []
    criterion_slots: dict[str, dict[str, Any]] = {}

    for dimension in taxonomy.get("dimensions") or []:
        compile_dimension(schema, dimension, scales, slot_order, criterion_slots)

    for slot_name, slot_def in criterion_slots.items():
        schema["slots"][slot_name] = slot_def
        slot_order.append(slot_name)

    schema["classes"][paper_review_class]["slots"].extend(slot_order)
    return schema


def compile_dimension(
    schema: dict[str, Any],
    dimension: dict[str, Any],
    scales: dict[str, dict[str, Any]],
    slot_order: list[str],
    criterion_slots: dict[str, dict[str, Any]],
) -> None:
    slot_name = str(dimension["id"])
    slot_order.append(slot_name)

    schema["slots"][slot_name] = build_dimension_slot(schema, dimension)

    for value in dimension.get("values") or []:
        collect_criteria(schema, value, scales, criterion_slots)
    for subdimension in dimension.get("subdimensions") or []:
        compile_dimension(schema, subdimension, scales, slot_order, criterion_slots)


def build_dimension_slot(schema: dict[str, Any], dimension: dict[str, Any]) -> dict[str, Any]:
    slot: dict[str, Any] = {
        "title": dimension.get("label") or dimension["id"],
        "description": dimension.get("description"),
        "required": bool(dimension.get("required", False)),
    }

    value_type = dimension["value_type"]
    if value_type in {"category", "method", "criterion"} and (dimension.get("values") or []):
        enum_name = f"{dimension['id']}_enum"
        schema["enums"][enum_name] = build_taxon_enum(dimension.get("values") or [])
        slot["range"] = enum_name
        slot["multivalued"] = dimension["cardinality"] == "multiple"
        return remove_empty(slot)

    if value_type == "numeric":
        slot["range"] = "float"
    else:
        slot["range"] = "string"
    slot["multivalued"] = dimension["cardinality"] == "multiple"
    return remove_empty(slot)


def collect_criteria(
    schema: dict[str, Any],
    taxon: dict[str, Any] | str,
    scales: dict[str, dict[str, Any]],
    criterion_slots: dict[str, dict[str, Any]],
) -> None:
    if isinstance(taxon, str):
        return

    for criterion in taxon.get("criteria") or []:
        slot_name = str(criterion["id"])
        if slot_name not in criterion_slots:
            criterion_slots[slot_name] = build_criterion_slot(schema, criterion, scales)

    for child in taxon.get("children") or []:
        collect_criteria(schema, child, scales, criterion_slots)


def build_criterion_slot(
    schema: dict[str, Any], criterion: dict[str, Any], scales: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    slot: dict[str, Any] = {
        "title": criterion.get("label") or criterion["id"],
        "description": criterion.get("question"),
    }

    scale = scales.get(str(criterion["scale"]))
    if scale is None:
        slot["range"] = "string"
        return slot

    if scale.get("scale_values"):
        enum_name = f"{criterion['id']}_enum"
        schema["enums"][enum_name] = build_scale_enum(scale.get("scale_values") or [])
        slot["range"] = enum_name
        return slot

    scale_type = scale.get("scale_type")
    if scale_type == "numeric":
        slot["range"] = "float"
    else:
        slot["range"] = "string"
    return slot


def build_taxon_enum(values: list[Any]) -> dict[str, Any]:
    permissible_values: dict[str, dict[str, Any]] = {}
    for value in flatten_taxa(values):
        permissible_values[str(value["id"])] = remove_empty(
            {
                "title": value.get("label") or value["id"],
                "description": value.get("description"),
            }
        )
    return {"permissible_values": permissible_values}


def build_scale_enum(scale_values: list[dict[str, Any]]) -> dict[str, Any]:
    permissible_values: dict[str, dict[str, Any]] = {}
    for value in scale_values:
        permissible_values[str(value["value"])] = remove_empty(
            {
                "title": value.get("label") or value["value"],
                "description": value.get("description"),
            }
        )
    return {"permissible_values": permissible_values}


def flatten_taxa(values: list[Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for value in values:
        if isinstance(value, str):
            result.append({"id": value, "label": value})
            continue
        result.append(value)
        result.extend(flatten_taxa(value.get("children") or []))
    return result


def add_core_slot(
    schema: dict[str, Any], slot_name: str, slot_range: str, required: bool = False, description: str | None = None
) -> None:
    schema["slots"][slot_name] = remove_empty({"range": slot_range, "required": required, "description": description})


def review_linkml_schema_to_json_schema(schema: dict[str, Any], top_class: str = "PaperReview") -> dict[str, Any]:
    schema_path = save_review_linkml_schema(schema)
    generator = JsonSchemaGenerator(str(schema_path), top_class=top_class)
    return json.loads(generator.serialize())


def review_linkml_schema_to_owl_xml(schema: dict[str, Any]) -> str:
    schema_path = save_review_linkml_schema(schema)
    return OwlSchemaGenerator(str(schema_path), format="xml").serialize()


def review_linkml_schema_to_shacl_ttl(schema: dict[str, Any]) -> str:
    schema_path = save_review_linkml_schema(schema)
    return ShaclGenerator(str(schema_path), format="ttl").serialize()


def review_linkml_schema_to_rdf_xml(schema: dict[str, Any]) -> str:
    schema_path = save_review_linkml_schema(schema)
    jsonld = json.loads(JSONLDGenerator(str(schema_path), format="jsonld").serialize(context=[f"file://{LOCAL_METAMODEL_LDCONTEXT_FILE}"]))
    jsonld["@context"] = [_localize_jsonld_context_entry(entry) for entry in jsonld.get("@context") or []]
    graph = Graph()
    graph.parse(data=json.dumps(jsonld), format="json-ld", base=_schema_base(schema_path), prefix=True)
    return graph.serialize(format="xml")


def compile_review_schema_artifacts(taxonomy: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    linkml_schema = taxonomy_to_review_linkml_schema(taxonomy)
    json_schema = review_linkml_schema_to_json_schema(linkml_schema)
    save_review_json_schema(linkml_schema, json_schema)
    return linkml_schema, json_schema


def save_review_linkml_schema(schema: dict[str, Any]) -> Path:
    path = GENERATED_SCHEMAS_DIR / f"{slugify(schema['name'])}.yaml"
    dump_yaml(path, schema)
    return path


def save_review_json_schema(linkml_schema: dict[str, Any], json_schema: dict[str, Any]) -> Path:
    path = GENERATED_SCHEMAS_DIR / f"{slugify(linkml_schema['name'])}.schema.json"
    path.write_text(json.dumps(json_schema, indent=2, sort_keys=True), encoding="utf-8")
    return path


def remove_empty(value: dict[str, Any]) -> dict[str, Any]:
    return {key: item for key, item in value.items() if item not in (None, "", [], {})}


def _localize_jsonld_context_entry(entry: Any) -> Any:
    if entry == "https://w3id.org/linkml/meta.context.jsonld":
        return f"file://{LOCAL_METAMODEL_LDCONTEXT_FILE}"
    if entry == "https://w3id.org/linkml/types.context.jsonld":
        return f"file://{LOCAL_TYPES_LDCONTEXT_FILE}"
    return entry


def _schema_base(schema_path: Path) -> str:
    return str(schema_path.parent.as_uri()) + "/"
