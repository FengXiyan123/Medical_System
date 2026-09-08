"""Recovery boundaries for failures that cannot be solved by replaying Redis.

Redis messages are only delivery hints.  Run state, completed embedding hashes,
and callback records stay in durable storage, so a worker restart cannot repeat
billable work or let an expired worker overwrite a newer attempt.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from threading import RLock

from medical_agent.persistence.outbox import CallbackEvent, InMemoryCallbackOutbox
from medical_agent.persistence.runs import InMemoryRunRepository


class PersistenceUnavailableError(RuntimeError):
    """Raised before a worker starts a billable action without PG persistence."""


@dataclass(frozen=True, slots=True)
class EmbeddingFence:
    task_id: str
    token: int


class EmbeddingFenceRegistry:
    """Reference fencing registry used by workers and deterministic tests.

    Production storage must enforce the same monotonic token comparison inside
    the transaction that writes an embedding or marks a generation ready.
    """

    def __init__(self) -> None:
        self._tokens: dict[str, int] = {}
        self._completed_hashes: dict[str, set[str]] = {}
        self._lock = RLock()

    def claim(self, task_id: str) -> EmbeddingFence:
        if not task_id.strip():
            raise ValueError("task_id must not be blank")
        with self._lock:
            token = self._tokens.get(task_id, 0) + 1
            self._tokens[task_id] = token
            return EmbeddingFence(task_id=task_id, token=token)

    def record_completed(self, fence: EmbeddingFence, content_hash: str) -> bool:
        """Record once when the caller still owns the latest fencing token."""
        if not content_hash.strip():
            raise ValueError("content_hash must not be blank")
        with self._lock:
            if self._tokens.get(fence.task_id) != fence.token:
                return False
            completed = self._completed_hashes.setdefault(fence.task_id, set())
            if content_hash in completed:
                return False
            completed.add(content_hash)
            return True

    def completed_hashes(self, task_id: str) -> set[str]:
        with self._lock:
            return set(self._completed_hashes.get(task_id, set()))


class RecoveryCoordinator:
    """Coordinates conservative recovery without implicitly rerunning models."""

    def __init__(self, *, postgres_available: Callable[[], bool]) -> None:
        self._postgres_available = postgres_available

    def require_billable_work_persistence(self) -> None:
        """Do not start model/embedding calls when their durable ledger is down."""
        if not self._postgres_available():
            raise PersistenceUnavailableError(
                "PostgreSQL is unavailable; new billable work is paused until durable state returns"
            )

    def recover_stale_runs(
        self,
        repository: InMemoryRunRepository,
        *,
        now: datetime,
        heartbeat_timeout: timedelta,
    ) -> list[str]:
        self.require_billable_work_persistence()
        # An interrupted chat is intentionally not requeued: retrying it could
        # issue another paid model call after partial output was already sent.
        return repository.recover_interrupted(now=now, heartbeat_timeout=heartbeat_timeout)

    def redeliver_callbacks(
        self,
        outbox: InMemoryCallbackOutbox,
        deliver: Callable[[CallbackEvent], None],
    ) -> int:
        """Retry only durable callbacks; failure leaves their record pending."""
        delivered = 0
        for event in outbox.pending():
            try:
                deliver(event)
            except Exception:  # noqa: BLE001, S112 - external Java endpoint remains retryable.
                continue
            if outbox.mark_delivered(event.event_id, now=datetime.now(UTC)):
                delivered += 1
        return delivered
