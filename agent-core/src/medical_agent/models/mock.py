from collections.abc import Iterator, Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class DeterministicMockChatModel:
    response_text: str
    input_tokens: int
    output_tokens: int
    chunk_size: int = 16

    def __post_init__(self) -> None:
        if self.input_tokens < 0 or self.output_tokens < 0:
            raise ValueError("token counts must not be negative")
        if self.chunk_size < 1:
            raise ValueError("chunk_size must be positive")

    def stream(self, request: Mapping[str, object]) -> Iterator[dict[str, object]]:
        del request
        for start in range(0, len(self.response_text), self.chunk_size):
            yield {
                "choices": [
                    {
                        "delta": {"content": self.response_text[start : start + self.chunk_size]},
                        "finish_reason": None,
                    }
                ],
                "usage": None,
            }
        yield {"choices": [{"delta": {}, "finish_reason": "stop"}], "usage": None}
        yield {
            "choices": [],
            "usage": {
                "prompt_tokens": self.input_tokens,
                "completion_tokens": self.output_tokens,
                "total_tokens": self.input_tokens + self.output_tokens,
            },
        }
