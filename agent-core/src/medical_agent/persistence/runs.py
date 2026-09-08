"""Run state, idempotency, and lease abstractions.

Only the repository owns state transitions. This prevents duplicate delivery,
concurrent workers, and recovery from returning terminal work to a queue.
"""

from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from threading import BoundedSemaphore, RLock
from typing import Any, Protocol

from medical_agent.persistence.outbox import CallbackEvent, InMemoryCallbackOutbox


class RunStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    INTERRUPTED = "INTERRUPTED"

    @property
    def is_terminal(self) -> bool:
        return self in {
            RunStatus.SUCCEEDED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.INTERRUPTED,
        }


@dataclass(frozen=True, slots=True)
class RunRecord:
    run_id: str
    payload_fingerprint: str
    status: RunStatus = RunStatus.QUEUED
    state_version: int = 0
    lease_owner: str | None = None
    heartbeat_at: datetime | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
    failure_reason: str | None = None


class RunRepository(Protocol):
    def enqueue(self, *, run_id: str, payload_fingerprint: str) -> tuple[RunRecord, bool]: ...

    def get(self, run_id: str) -> RunRecord | None: ...

    def claim_next(self, *, worker_id: str, now: datetime) -> RunRecord | None: ...

    def heartbeat(self, run_id: str, *, worker_id: str, now: datetime) -> bool: ...

    def complete(self, run_id: str, *, worker_id: str, now: datetime) -> RunRecord | None: ...

    def fail(
        self, run_id: str, *, worker_id: str, reason: str, now: datetime
    ) -> RunRecord | None: ...


class InMemoryRunRepository:
    """Thread-safe reference repository with the same CAS semantics as PG."""

    def __init__(self) -> None:
        self._runs: dict[str, RunRecord] = {}
        self._lock = RLock()

    def enqueue(self, *, run_id: str, payload_fingerprint: str) -> tuple[RunRecord, bool]:
        now = datetime.now(UTC)
        with self._lock:
            existing = self._runs.get(run_id)
            if existing is not None:
                if existing.payload_fingerprint != payload_fingerprint:
                    raise ValueError("run_id was previously delivered with a different payload")
                return replace(existing), False
            record = RunRecord(
                run_id=run_id,
                payload_fingerprint=payload_fingerprint,
                created_at=now,
                updated_at=now,
            )
            self._runs[run_id] = record
            return replace(record), True

    def get(self, run_id: str) -> RunRecord | None:
        with self._lock:
            record = self._runs.get(run_id)
            return replace(record) if record is not None else None

    def claim(self, *, run_id: str, worker_id: str, now: datetime) -> RunRecord | None:
        with self._lock:
            record = self._runs.get(run_id)
            if record is None or record.status is not RunStatus.QUEUED:
                return None
            claimed = self._claimed(record, worker_id=worker_id, now=now)
            self._runs[run_id] = claimed
            return replace(claimed)

    def claim_next(self, *, worker_id: str, now: datetime) -> RunRecord | None:
        with self._lock:
            queued = next((run for run in self._runs.values() if run.status is RunStatus.QUEUED), None)
            if queued is None:
                return None
            claimed = self._claimed(queued, worker_id=worker_id, now=now)
            self._runs[queued.run_id] = claimed
            return replace(claimed)

    def heartbeat(self, run_id: str, *, worker_id: str, now: datetime) -> bool:
        with self._lock:
            record = self._runs.get(run_id)
            if not self._owns_running(record, worker_id):
                return False
            self._runs[run_id] = replace(
                record,
                state_version=record.state_version + 1,
                heartbeat_at=now,
                updated_at=now,
            )
            return True

    def complete(self, run_id: str, *, worker_id: str, now: datetime) -> RunRecord | None:
        return self._finish(run_id, worker_id=worker_id, status=RunStatus.SUCCEEDED, reason=None, now=now)

    def fail(self, run_id: str, *, worker_id: str, reason: str, now: datetime) -> RunRecord | None:
        return self._finish(run_id, worker_id=worker_id, status=RunStatus.FAILED, reason=reason, now=now)

    def cancel(self, run_id: str, *, now: datetime) -> RunRecord | None:
        with self._lock:
            record = self._runs.get(run_id)
            if record is None or record.status.is_terminal:
                return None
            cancelled = replace(
                record,
                status=RunStatus.CANCELLED,
                state_version=record.state_version + 1,
                lease_owner=None,
                updated_at=now,
            )
            self._runs[run_id] = cancelled
            return replace(cancelled)

    def recover_interrupted(self, *, now: datetime, heartbeat_timeout: timedelta) -> list[str]:
        cutoff = now - heartbeat_timeout
        recovered: list[str] = []
        with self._lock:
            for run_id, record in self._runs.items():
                if (
                    record.status is RunStatus.RUNNING
                    and record.heartbeat_at is not None
                    and record.heartbeat_at <= cutoff
                ):
                    self._runs[run_id] = replace(
                        record,
                        status=RunStatus.INTERRUPTED,
                        state_version=record.state_version + 1,
                        lease_owner=None,
                        updated_at=now,
                    )
                    recovered.append(run_id)
        return recovered

    @staticmethod
    def _claimed(record: RunRecord, *, worker_id: str, now: datetime) -> RunRecord:
        return replace(
            record,
            status=RunStatus.RUNNING,
            state_version=record.state_version + 1,
            lease_owner=worker_id,
            heartbeat_at=now,
            updated_at=now,
        )

    @staticmethod
    def _owns_running(record: RunRecord | None, worker_id: str) -> bool:
        return (
            record is not None
            and record.status is RunStatus.RUNNING
            and record.lease_owner == worker_id
        )

    def _finish(
        self,
        run_id: str,
        *,
        worker_id: str,
        status: RunStatus,
        reason: str | None,
        now: datetime,
    ) -> RunRecord | None:
        with self._lock:
            record = self._runs.get(run_id)
            if not self._owns_running(record, worker_id):
                return None
            terminal = replace(
                record,
                status=status,
                state_version=record.state_version + 1,
                lease_owner=None,
                updated_at=now,
                failure_reason=reason,
            )
            self._runs[run_id] = terminal
            return replace(terminal)


class RestrictedRunExecutor:
    """Bounded worker that claims one lease before it may execute a run."""

    def __init__(
        self,
        repository: RunRepository,
        *,
        worker_id: str,
        max_concurrent_runs: int,
        callback_outbox: InMemoryCallbackOutbox | None = None,
    ) -> None:
        if max_concurrent_runs < 1:
            raise ValueError("max_concurrent_runs must be positive")
        self._repository = repository
        self._worker_id = worker_id
        self._slots = BoundedSemaphore(max_concurrent_runs)
        self._callback_outbox = callback_outbox

    def run_once(self, work: Callable[[RunRecord], None]) -> bool:
        if not self._slots.acquire(blocking=False):
            return False
        try:
            run = self._repository.claim_next(worker_id=self._worker_id, now=datetime.now(UTC))
            if run is None:
                return False
            try:
                work(run)
            except Exception as error:  # noqa: BLE001 - worker failures become durable run failures.
                terminal = self._repository.fail(
                    run.run_id,
                    worker_id=self._worker_id,
                    reason=str(error),
                    now=datetime.now(UTC),
                )
            else:
                terminal = self._repository.complete(
                    run.run_id, worker_id=self._worker_id, now=datetime.now(UTC)
                )
            if terminal is not None:
                self._emit_terminal_callback(terminal)
            return True
        finally:
            self._slots.release()

    def _emit_terminal_callback(self, run: RunRecord) -> None:
        if self._callback_outbox is None:
            return
        event_type = "run.completed" if run.status is RunStatus.SUCCEEDED else "run.failed"
        self._callback_outbox.append(
            CallbackEvent(
                event_id=f"{run.run_id}:{run.state_version}:{event_type}",
                run_id=run.run_id,
                event_type=event_type,
                payload={"status": run.status.value},
            )
        )


class PostgresRunRepository:
    """PostgreSQL adapter shape with a `FOR UPDATE SKIP LOCKED` lease query.

    The adapter accepts a DB-API connection factory, so unit tests stay driver-
    free while deployment can provide a psycopg connection factory.
    """

    CLAIM_NEXT_SQL = """
        WITH candidate AS (
            SELECT run_id FROM execution.run
            WHERE status = 'QUEUED'
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        UPDATE execution.run target
        SET status = 'RUNNING', state_version = target.state_version + 1,
            lease_owner = %s, heartbeat_at = %s
        FROM candidate
        WHERE target.run_id = candidate.run_id AND target.status = 'QUEUED'
        RETURNING target.run_id, target.payload_fingerprint, target.status,
            target.state_version, target.lease_owner, target.heartbeat_at,
            target.created_at, target.updated_at, target.failure_reason
    """

    def __init__(self, connection_factory: Callable[[], Any]) -> None:
        self._connection_factory = connection_factory

    def claim_next(self, *, worker_id: str, now: datetime) -> RunRecord | None:
        connection = self._connection_factory()
        try:
            with connection.cursor() as cursor:
                cursor.execute(self.CLAIM_NEXT_SQL, (worker_id, now))
                row = cursor.fetchone()
            connection.commit()
            return self._record_from_row(row) if row is not None else None
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def _record_from_row(row: tuple[Any, ...]) -> RunRecord:
        return RunRecord(
            run_id=row[0],
            payload_fingerprint=row[1],
            status=RunStatus(row[2]),
            state_version=row[3],
            lease_owner=row[4],
            heartbeat_at=row[5],
            created_at=row[6],
            updated_at=row[7],
            failure_reason=row[8],
        )


class RunLifecycle:
    """Small compatibility wrapper used by early lifecycle unit tests."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self.status = RunStatus.QUEUED

    def claim(self) -> bool:
        if self.status is not RunStatus.QUEUED:
            return False
        self.status = RunStatus.RUNNING
        return True

    def complete(self) -> bool:
        return self._finish(RunStatus.SUCCEEDED)

    def cancel(self) -> bool:
        return self._finish(RunStatus.CANCELLED)

    def recover_interrupted(self) -> bool:
        return self._finish(RunStatus.INTERRUPTED)

    def _finish(self, terminal: RunStatus) -> bool:
        if self.status.is_terminal:
            return False
        if self.status is not RunStatus.RUNNING:
            raise ValueError("run must be claimed before terminal transition")
        self.status = terminal
        return True
