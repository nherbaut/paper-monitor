# Paper Data Extractor

Python microservice used by Paper Monitor to compose LinkML-backed taxonomy models and create paper classifications from them.

## Features

- Stores contributed taxonomy models as YAML documents.
- Lets users upload custom taxonomy models on top of contributed models.
- Composes selected models into one derived model.
- Exposes a generated classification form for a paper based on selected dimensions.
- Stores submitted classifications as JSON.
- Ships the systematic survey meta-model as a LinkML schema.

## Run

```bash
cd paper-data-extractor
python -m venv .venv
. .venv/bin/activate
pip install -e .
uvicorn paper_data_extractor.main:app --reload --port 8091
```

Open `http://localhost:8091`.

## API

- `GET /api/models/contributed`: list contributed models.
- `GET /api/models/custom`: list custom uploaded models.
- `GET /api/models`: list all models.
- `POST /api/models/custom`: upload a custom taxonomy YAML.
- `POST /api/models/custom/json`: create a custom taxonomy from the visual designer.
- `POST /api/models/compose`: compose selected model ids and return:
  - the composed taxonomy instance
  - the UI-oriented form schema
  - a generated review LinkML schema
  - a generated JSON Schema
- `POST /api/classifications`: store a paper classification.
- `GET /api/classifications/{classification_id}`: read a stored classification.

## Model Shape

Taxonomy YAML documents are instances of the LinkML meta-model in `schemas/systematic_survey_metamodel.yaml`.

Contributed models live in `data/contributed_models`. Uploaded custom models are written to `data/custom_models`.

## Meta-model Terms

The meta-model describes taxonomy models used to classify papers. A taxonomy model is not a classification result by itself. It defines the questions, categories, scoring scales, and rules that will later be shown to a user when they classify one paper.

### Taxonomy

A `Taxonomy` is the root object of a model.

It contains:

- `id`: stable technical identifier, for example `wieringa_2006_paper_classification`.
- `title`: human-readable model name.
- `source`: optional bibliographic source for the taxonomy.
- `target_entity`: the kind of thing classified by the model, usually `paper`.
- `dimensions`: the classification axes or fields.
- `scales`: reusable answer scales for criteria.
- `rules`: optional notes that explain how the model should be applied.

### Source

A `Source` records where the taxonomy comes from. It is metadata, not a classification field.

It contains:

- `citation_key`: short reference key, for example `wieringa_2006`.
- `title`: source publication title.
- `authors`: list of author names.
- `year`: publication year.

### Dimension

A `Dimension` is one classification axis. In the UI it usually becomes one form section or field.

Examples:

- Paper class.
- Application domain.
- Evaluation strategy.
- Output modality.

It contains:

- `id`: stable technical identifier.
- `label`: human-readable name shown in forms.
- `description`: optional explanation.
- `value_type`: the kind of answer expected.
- `cardinality`: whether the user can choose one or several values.
- `required`: whether the classifier must answer it.
- `values`: the available taxa for category-like dimensions.
- `subdimensions`: nested dimensions for hierarchical taxonomies.

### Value Type

`value_type` tells the service how a dimension should be interpreted.

Allowed values:

- `category`: choose one or more taxa from a controlled list.
- `criterion`: answer an evaluation criterion directly.
- `method`: classify a method or research approach.
- `free_text`: collect free text.
- `numeric`: collect a number.

### Cardinality

`cardinality` defines how many answers are allowed for a dimension.

Allowed values:

- `single`: exactly one selected value when answered.
- `multiple`: several selected values are allowed.

### Taxon

A `Taxon` is a controlled value inside a category dimension.

Examples:

- `evaluation_research`.
- `proposal_of_solution`.
- `art_and_creativity`.
- `real_time`.

It contains:

- `id`: stable technical identifier.
- `label`: human-readable label.
- `description`: optional explanation.
- `children`: nested taxa for hierarchical value trees.
- `criteria`: evaluation questions that become active when this taxon is selected.

### Criterion

A `Criterion` is a question attached to a taxon. Criteria are useful when selecting a category should trigger additional evaluation questions.

For example, selecting `evaluation_research` may ask whether the problem is clearly stated and whether the research method is sound.

It contains:

- `id`: stable technical identifier.
- `label`: short display label.
- `question`: full question shown to the classifier.
- `scale`: id of the scale used to answer the question.
- `required`: whether the criterion must be answered when active.

### Scale

A `Scale` defines reusable answer values for criteria.

Examples:

- Binary yes/no.
- Ordinal 0/1/2.
- Nominal low/medium/high.
- Numeric score.
- Free text justification.

It contains:

- `id`: stable technical identifier, referenced by criteria.
- `scale_type`: the kind of scale.
- `scale_values`: controlled values when the scale has predefined choices.

### Scale Type

`scale_type` describes how criterion answers should be collected.

Allowed values:

- `binary`: two possible values, for example yes/no.
- `ordinal`: ordered values, for example 0/1/2.
- `nominal`: unordered named values.
- `numeric`: numeric input.
- `free_text`: text input.

### Scale Value

A `ScaleValue` is one possible value inside a scale.

It contains:

- `value`: stored value, for example `0`.
- `label`: human-readable label, for example `not_satisfied`.
- `description`: optional explanation of when to use this value.

### Rule

A `Rule` is a human-readable instruction about how the taxonomy should be applied. Rules are not currently executable constraints; they document intended usage.

It contains:

- `id`: stable technical identifier.
- `description`: explanation of the rule.
- `applies_to`: id of the dimension, taxon, or model part concerned by the rule.

## How The Pieces Fit Together

A typical taxonomy structure is:

```text
Taxonomy
  Source
  Scales
    Scale
      ScaleValue
  Dimensions
    Dimension
      Taxon
        Criterion -> references a Scale by id
        Child Taxon
      Subdimension
  Rules
```

In practical terms:

- Create a `Taxonomy` for the full model.
- Add one `Dimension` per classification axis.
- Add `Taxon` values to dimensions when the user should choose from a controlled vocabulary.
- Add `Scale` definitions before adding criteria that depend on them.
- Attach `Criterion` questions to taxa when selecting that taxon should require extra evaluation.
- Add `Rule` entries to document model-specific interpretation rules.

## Review Schema Compilation

The service now supports a two-step LinkML pipeline:

1. Compose one taxonomy instance from several reusable models.
2. Compile that taxonomy instance into a derived LinkML schema for review answers.
3. Generate JSON Schema from that derived LinkML schema using LinkML's Python generator API.

That means the JSON Schema is not generated directly from the taxonomy instance. Instead, the taxonomy instance is first transformed into a generated `PaperReview` LinkML schema, and that generated schema is then translated into JSON Schema.

Generated review schemas are written to `data/generated_schemas` for inspection and debugging.

You can also compile a taxonomy instance manually from the command line:

```bash
paper-data-extractor-compile-review-schema data/composed_models/composed-98b8d74b2e11a1da.yaml
```

Or compile by model id and source:

```bash
paper-data-extractor-compile-review-schema composed-98b8d74b2e11a1da --source composed
```

Do not run `gen-json-schema` directly on a taxonomy instance such as `composed-....yaml`. That file is data conforming to the survey metamodel, not a LinkML schema. The compiler first generates a derived LinkML review schema, and only that generated schema is suitable for JSON Schema generation.
