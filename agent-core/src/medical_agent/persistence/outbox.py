"""Durable callback records emitted after run state changes."""

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from threading import RLock


@dataclass(frozen=True, slots=True)
class CallbackEvent:
    event_id: str
    run_id: str
    event_type: str
    payload: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class CallbackDelivery:
    event: CallbackEvent
    attempts: int
    delivered_at: datetime | None


class InMemoryCallbackOutbox:
    """Idempotent outbox suitable for unit tests and the local mock runner."""

    def __init__(self) -> None:
        self._deliveries: dict[str, CallbackDelivery] = {}
        self._lock = RLock()

    def append(self, event: CallbackEvent) -> bool:
        with self._lock:
            if event.event_id in self._deliveries:
                return False
            self._deliveries[event.event_id] = CallbackDelivery(
                event=event, attempts=0, delivered_at=None
            )
            return True

    def pending(self) -> list[CallbackEvent]:
        with self._lock:
            return [delivery.event for delivery in self._deliveries.values() if delivery.delivered_at is None]

    def mark_delivered(self, event_id: str, *, now: datetime | None = None) -> bool:
        with self._lock:
            delivery = self._deliveries.get(event_id)
            if delivery is None or delivery.delivered_at is not None:
                return False
            self._deliveries[event_id] = CallbackDelivery(
                event=delivery.event,
                attempts=delivery.attempts + 1,
                delivered_at=now or datetime.now(UTC),
            )
            return True
