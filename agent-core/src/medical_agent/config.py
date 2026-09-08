from enum import Enum
from pathlib import Path
from urllib.parse import quote

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class ModelMode(str, Enum):
    MOCK = "mock"
    REAL = "real"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    model_mode: ModelMode = ModelMode.MOCK
    dashscope_region: str = "cn-beijing"
    dashscope_workspace_id: str | None = None
    dashscope_api_key: str | None = None
    dashscope_base_url: str | None = None
    gateway_service_token_secret: str | None = None
    gateway_base_url: str = "http://127.0.0.1:8080"
    postgres_dsn: str | None = None
    postgres_host: str = "127.0.0.1"
    postgres_port: int = 5432
    postgres_db: str | None = None
    postgres_user: str | None = None
    postgres_password: str | None = None
    redis_url: str = "redis://127.0.0.1:6379/0"
    redis_password: str | None = None
    medical_storage_root: Path = Path(__file__).resolve().parents[3] / "data" / "uploads"
    ingestion_worker_enabled: bool = True

    @model_validator(mode="before")
    @classmethod
    def normalize_legacy_model_mode(cls, value: object) -> object:
        """Accept old boolean-style values while keeping the documented enum explicit."""
        if not isinstance(value, dict):
            return value
        raw = value.get("model_mode")
        if raw in (True, "true", "TRUE", "1"):
            return {**value, "model_mode": ModelMode.REAL.value}
        if raw in (False, "false", "FALSE", "0"):
            return {**value, "model_mode": ModelMode.MOCK.value}
        return value

    @model_validator(mode="after")
    def validate_real_provider_configuration(self) -> "Settings":
        if self.model_mode is ModelMode.REAL:
            missing = [
                name
                for name, value in {
                    "DASHSCOPE_WORKSPACE_ID": self.dashscope_workspace_id,
                    "DASHSCOPE_API_KEY": self.dashscope_api_key,
                }.items()
                if not value
            ]
            if missing:
                raise ValueError(f"real model mode requires {', '.join(missing)}")
        return self

    @property
    def effective_postgres_dsn(self) -> str | None:
        if self.postgres_dsn:
            return self.postgres_dsn
        if self.postgres_db and self.postgres_user and self.postgres_password:
            return (
                f"postgresql://{quote(self.postgres_user, safe='')}:{quote(self.postgres_password, safe='')}@"
                f"{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
            )
        return None

    @property
    def effective_redis_url(self) -> str:
        if not self.redis_password or "@" in self.redis_url:
            return self.redis_url
        scheme, remainder = self.redis_url.split("://", 1)
        return f"{scheme}://:{quote(self.redis_password, safe='')}@{remainder}"
