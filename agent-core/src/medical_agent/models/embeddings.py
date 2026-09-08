"""DashScope-compatible text embedding adapter for real RAG mode."""

from __future__ import annotations

import json
import urllib.request
from dataclasses import dataclass

from medical_agent.models.qwen_config import DashScopeChatProfile


@dataclass(frozen=True, slots=True)
class DashScopeEmbeddingModel:
    workspace_id: str
    region: str
    api_key: str
    base_url: str | None = None
    model: str = "text-embedding-v4"
    dimension: int = 1024

    @property
    def profile_id(self) -> str:
        return f"{self.model}-{self.dimension}"

    @property
    def _url(self) -> str:
        # Reuse the validated regional host logic of the chat profile.
        profile = DashScopeChatProfile(self.workspace_id, self.region, self.api_key, self.base_url)
        return profile.chat_completions_url.removesuffix("/chat/completions") + "/embeddings"

    def embed(self, content: str) -> tuple[float, ...]:
        request = urllib.request.Request(
            self._url,
            data=json.dumps({"model": self.model, "input": content, "dimensions": self.dimension,
                             "encoding_format": "float"}, ensure_ascii=False).encode(),
            method="POST",
            headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read())
        try:
            vector = payload["data"][0]["embedding"]
        except (KeyError, IndexError, TypeError) as error:
            raise ValueError("DashScope embedding response is invalid") from error
        if not isinstance(vector, list) or len(vector) != self.dimension or not all(isinstance(v, (int, float)) for v in vector):
            raise ValueError("DashScope embedding dimension is invalid")
        return tuple(float(value) for value in vector)
