FROM python:3.10-slim

WORKDIR /app

ENV PIP_NO_CACHE_DIR=1 \
    POETRY_VERSION=1.8.2 \
    POETRY_VIRTUALENVS_CREATE=false

RUN pip install "poetry==${POETRY_VERSION}"

COPY pyproject.toml poetry.lock* ./
RUN poetry install --no-interaction --no-root --only main

COPY plrs_ml ./plrs_ml

EXPOSE 8000

CMD ["uvicorn", "plrs_ml.main:app", "--host", "0.0.0.0", "--port", "8000"]
