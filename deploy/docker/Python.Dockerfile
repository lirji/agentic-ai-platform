FROM ghcr.io/astral-sh/uv:0.11.3@sha256:90bbb3c16635e9627f49eec6539f956d70746c409209041800a0280b93152823 AS uv
FROM python:3.12.13-slim-bookworm@sha256:4766d8b510c428e595d74b9cc5bbb2fae8e26316fffb4adc89908d79aacd58a2 AS build
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy
COPY ai-runtime/pyproject.toml ai-runtime/uv.lock ./
COPY ai-runtime/src ./src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-editable

FROM python:3.12.13-slim-bookworm@sha256:4766d8b510c428e595d74b9cc5bbb2fae8e26316fffb4adc89908d79aacd58a2
ARG SOURCE_FINGERPRINT
LABEL io.agentic.source-fingerprint="${SOURCE_FINGERPRINT}"
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY docker/search_bridge.py /app/search_bridge.py
ENV PATH="/app/.venv/bin:${PATH}" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 \
    LANGSMITH_TRACING=false LANGCHAIN_TRACING_V2=false
USER 10001:10001
ENTRYPOINT ["python", "-m", "agentic_ai.cli"]
