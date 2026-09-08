from enum import Enum


class InvocationPurpose(str, Enum):
    KB_ROUTE = "KB_ROUTE"
    QUERY_REWRITE = "QUERY_REWRITE"
    AGENT_DECISION = "AGENT_DECISION"
    ANSWER = "ANSWER"


class RunBudget:
    def __init__(
        self,
        *,
        max_invocations: int,
        max_chat_tokens: int,
        final_answer_reserve_tokens: int,
    ) -> None:
        if max_invocations < 1:
            raise ValueError("max_invocations must be positive")
        if max_chat_tokens < 1:
            raise ValueError("max_chat_tokens must be positive")
        if not 1 <= final_answer_reserve_tokens <= max_chat_tokens:
            raise ValueError("final_answer_reserve_tokens must fit the chat budget")

        self._max_invocations = max_invocations
        self._max_chat_tokens = max_chat_tokens
        self._final_answer_reserve_tokens = final_answer_reserve_tokens
        self._reserved_invocations = 0
        self._reserved_chat_tokens = 0

    def try_reserve(self, purpose: InvocationPurpose, planned_chat_tokens: int) -> bool:
        if planned_chat_tokens < 0:
            raise ValueError("planned_chat_tokens must not be negative")

        if purpose is InvocationPurpose.ANSWER:
            allowed_invocations = self._max_invocations
            allowed_tokens = self._max_chat_tokens
        else:
            allowed_invocations = self._max_invocations - 1
            allowed_tokens = self._max_chat_tokens - self._final_answer_reserve_tokens

        if self._reserved_invocations + 1 > allowed_invocations:
            return False
        if self._reserved_chat_tokens + planned_chat_tokens > allowed_tokens:
            return False

        self._reserved_invocations += 1
        self._reserved_chat_tokens += planned_chat_tokens
        return True
