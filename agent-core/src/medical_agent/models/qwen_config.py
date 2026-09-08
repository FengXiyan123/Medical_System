from collections.abc import Mapping, Sequence
from dataclasses import dataclass

_REGION_HOSTS = {
    "cn-beijing": "cn-beijing.maas.aliyuncs.com",
    "ap-southeast-1": "ap-southeast-1.maas.aliyuncs.com",
    "us-east-1": "us-east-1.maas.aliyuncs.com",
    "eu-central-1": "eu-central-1.maas.aliyuncs.com",
    "ap-northeast-1": "ap-northeast-1.maas.aliyuncs.com",
}


@dataclass(frozen=True)
class DashScopeChatProfile:
    workspace_id: str
    region: str
    api_key: str
    base_url: str | None = None
    model: str = "qwen3.6-flash"

    def __post_init__(self) -> None:
        if not self.workspace_id.strip():
            raise ValueError("workspace_id is required")
        if self.region not in _REGION_HOSTS:
            raise ValueError(f"unsupported DashScope region: {self.region}")
        if not self.api_key.strip():
            raise ValueError("api_key is required")

    @property
    def chat_completions_url(self) -> str:
        if self.base_url:
            return self.base_url.rstrip("/") + "/chat/completions"
        host = _REGION_HOSTS[self.region]
        return f"https://{self.workspace_id}.{host}/compatible-mode/v1/chat/completions"

    def chat_request(
        self,
        *,
        messages: Sequence[Mapping[str, object]],
        max_completion_tokens: int,
    ) -> dict[str, object]:
        if max_completion_tokens < 1:
            raise ValueError("max_completion_tokens must be positive")
        return {
            "model": self.model,
            "messages": [dict(message) for message in messages],
            "stream": True,
            "stream_options": {"include_usage": True},
            "enable_thinking": False,
            "enable_search": False,
            "parallel_tool_calls": False,
            "max_completion_tokens": max_completion_tokens,
        }
