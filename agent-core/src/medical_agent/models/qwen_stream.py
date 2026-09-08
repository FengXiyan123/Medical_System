from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class ChatStreamResult:
    content: str
    finish_reason: str | None
    usage: dict[str, int] | None


def consume_chat_stream(
    chunks: Iterable[Mapping[str, object]], *, on_delta: Callable[[str], None] | None = None
) -> ChatStreamResult:
    content_parts: list[str] = []
    finish_reason: str | None = None
    usage: dict[str, int] | None = None

    for chunk in chunks:
        raw_choices = chunk.get("choices", [])
        if isinstance(raw_choices, list):
            for choice in raw_choices:
                if not isinstance(choice, Mapping):
                    continue
                delta = choice.get("delta", {})
                if isinstance(delta, Mapping) and isinstance(delta.get("content"), str):
                    text = delta["content"]
                    content_parts.append(text)
                    if on_delta:
                        on_delta(text)
                if isinstance(choice.get("finish_reason"), str):
                    finish_reason = choice["finish_reason"]

        normalized_usage = _normalize_usage(chunk.get("usage"))
        if normalized_usage is not None:
            usage = normalized_usage

    return ChatStreamResult("".join(content_parts), finish_reason, usage)


def _normalize_usage(raw_usage: object) -> dict[str, int] | None:
    if not isinstance(raw_usage, Mapping):
        return None
    prompt_tokens = raw_usage.get("prompt_tokens")
    completion_tokens = raw_usage.get("completion_tokens")
    total_tokens = raw_usage.get("total_tokens")
    if not all(isinstance(value, int) for value in (prompt_tokens, completion_tokens, total_tokens)):
        return None
    return {
        "input_tokens": prompt_tokens,
        "output_tokens": completion_tokens,
        "total_tokens": total_tokens,
    }
