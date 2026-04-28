FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src
COPY schemas ./schemas
COPY templates ./templates
COPY static ./static
COPY data/contributed_models ./data/contributed_models

RUN pip install --no-cache-dir .

EXPOSE 8091

CMD ["uvicorn", "paper_data_extractor.main:app", "--host", "0.0.0.0", "--port", "8091"]

