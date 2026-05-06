FROM python:3.12-slim

WORKDIR /app
ENV PROJECT_ROOT=/app

COPY pyproject.toml README.md ./
RUN python -c "import pathlib, tomllib; deps = tomllib.loads(pathlib.Path('pyproject.toml').read_text())['project']['dependencies']; pathlib.Path('/tmp/requirements.txt').write_text('\n'.join(deps) + '\n', encoding='utf-8')"
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY src ./src
COPY schemas ./schemas
COPY templates ./templates
COPY static ./static
COPY data/contributed_models ./data/contributed_models

RUN pip install --no-cache-dir --no-deps .

EXPOSE 8091

CMD ["uvicorn", "paper_data_extractor.main:app", "--host", "0.0.0.0", "--port", "8091"]
