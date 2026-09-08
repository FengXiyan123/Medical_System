FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN pip install --no-cache-dir uv && uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev

EXPOSE 8000
CMD ["uv", "run", "--no-sync", "uvicorn", "medical_agent.api.app:app", "--host", "0.0.0.0", "--port", "8000"]
