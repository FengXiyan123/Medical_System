"""Token-accounting primitives for one agent run.

Provider-reported usage is deliberately distinct from unknown usage.  This
prevents a missing terminal usage chunk from being silently treated as zero.
"""

from dataclasses import dataclass, field
from enum import StrEnum


class UsageSource(StrEnum):
    PROVIDER_REPORTED = "PROVIDER_REPORTED"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class InvocationUsage:
    invocation_id: str
    attempt_no: int
    source: UsageSource
    input_tokens: int | None = None
    output_tokens: int | None = None

    def __post_init__(self) -> None:
        if not self.invocation_id.strip():
            raise ValueError("invocation_id must not be blank")
        if self.attempt_no < 1:
            raise ValueError("attempt_no must be at least 1")
        if self.source is UsageSource.PROVIDER_REPORTED:
            if self.input_tokens is None or self.input_tokens < 0:
                raise ValueError("input_tokens must be a non-negative integer")
            if self.output_tokens is None or self.output_tokens < 0:
                raise ValueError("output_tokens must be a non-negative integer")
        elif self.input_tokens is not None or self.output_tokens is not None:
            raise ValueError("unknown usage must not contain token values")

    @classmethod
    def reported(
        cls, *, invocation_id: str, attempt_no: int, input_tokens: int, output_tokens: int
    ) -> "InvocationUsage":
        return cls(
            invocation_id=invocation_id,
            attempt_no=attempt_no,
            source=UsageSource.PROVIDER_REPORTED,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    @classmethod
    def unknown(cls, *, invocation_id: str, attempt_no: int) -> "InvocationUsage":
        return cls(
            invocation_id=invocation_id,
            attempt_no=attempt_no,
            source=UsageSource.UNKNOWN,
        )


@dataclass(frozen=True, slots=True)
class UsageSummary:
    input_tokens: int
    output_tokens: int
    total_tokens: int
    reported_invocation_count: int
    unknown_invocation_count: int


@dataclass(slots=True)
class UsageLedger:
    _records: list[InvocationUsage] = field(default_factory=list)
    _recorded_attempts: set[tuple[str, int]] = field(default_factory=set)

    def record(self, usage: InvocationUsage) -> None:
        key = (usage.invocation_id, usage.attempt_no)
        if key in self._recorded_attempts:
            raise ValueError("duplicate invocation attempt")
        self._recorded_attempts.add(key)
        self._records.append(usage)

    def summary(self) -> UsageSummary:
        reported = [
            usage for usage in self._records if usage.source is UsageSource.PROVIDER_REPORTED
        ]
        input_tokens = sum(usage.input_tokens or 0 for usage in reported)
        output_tokens = sum(usage.output_tokens or 0 for usage in reported)
        return UsageSummary(
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            total_tokens=input_tokens + output_tokens,
            reported_invocation_count=len(reported),
            unknown_invocation_count=len(self._records) - len(reported),
        )
