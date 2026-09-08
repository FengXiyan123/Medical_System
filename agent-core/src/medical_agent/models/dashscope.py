"""Minimal streaming client for DashScope's OpenAI-compatible chat endpoint."""

from __future__ import annotations

import json
import urllib.request
from collections.abc import Iterable, Mapping

from medical_agent.models.qwen_config import DashScopeChatProfile


class DashScopeStreamingChatModel:
    """Yields decoded server-sent chat completion chunks without retaining prompts."""

    def __init__(self, profile: DashScopeChatProfile) -> None:
        self._profile = profile

    def stream(self, request: Mapping[str, object]) -> Iterable[Mapping[str, object]]:
        messages = request.get("messages")
        if not isinstance(messages, list):
            raise TypeError("chat request must contain messages")
        payload = self._profile.chat_request(messages=messages, max_completion_tokens=1024)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        http_request = urllib.request.Request(
            self._profile.chat_completions_url,
            data=body,
            headers={
                "Authorization": f"Bearer {self._profile.api_key}",
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            method="POST",
        )
        with urllib.request.urlopen(http_request, timeout=60) as response:
            for raw_line in response:
                line = raw_line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                data = line.removeprefix("data:").strip()
                if data == "[DONE]":
                    return
                decoded = json.loads(data)
                if isinstance(decoded, dict):
                    yield decoded
