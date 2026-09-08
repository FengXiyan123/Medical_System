"""Verification of Java gateway service credentials for internal endpoints."""

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ServiceClaims:
    subject: str
    audience: str
    scopes: frozenset[str]
    run_id: str | None
    user_id: str | None
    mode: str | None


class ServiceTokenVerifier:
    def __init__(self, secret: str, *, audience: str) -> None:
        if len(secret.encode()) < 32:
            raise ValueError("service token secret must contain at least 32 bytes")
        self._secret = secret.encode()
        self._audience = audience

    def verify(self, token: str) -> ServiceClaims:
        try:
            header, payload, signature = token.split(".")
            parsed_header = json.loads(_decode(header))
            claims = json.loads(_decode(payload))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("invalid service token") from error
        if parsed_header.get("alg") != "HS256":
            raise ValueError("unsupported service token algorithm")
        signed = f"{header}.{payload}".encode()
        expected = hmac.new(self._secret, signed, hashlib.sha256).digest()
        try:
            actual_signature = _decode(signature)
        except (ValueError, UnicodeDecodeError) as error:
            raise ValueError("invalid service token") from error
        if not hmac.compare_digest(expected, actual_signature):
            raise ValueError("invalid service token signature")
        if claims.get("aud") != self._audience:
            raise ValueError("invalid service token audience")
        if not isinstance(claims.get("exp"), (int, float)) or claims["exp"] <= time.time():
            raise ValueError("service token has expired")
        subject = claims.get("sub")
        if subject != "agent-gateway":
            raise ValueError("invalid service token subject")
        scopes = _scopes(claims.get("scope"))
        return ServiceClaims(
            subject=subject,
            audience=self._audience,
            scopes=scopes,
            run_id=_string_claim(claims, "run_id"),
            user_id=_string_claim(claims, "user_id"),
            mode=_string_claim(claims, "mode"),
        )

    def verify_run_ticket(self, token: str, *, run_id: str, user_id: str, mode: str) -> ServiceClaims:
        claims = self.verify(token)
        if "runs:write" not in claims.scopes:
            raise ValueError("service token is missing runs:write scope")
        if (claims.run_id, claims.user_id, claims.mode) != (run_id, user_id, mode):
            raise ValueError("service token is not bound to this run")
        return claims


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _scopes(value: object) -> frozenset[str]:
    if isinstance(value, str):
        return frozenset(scope for scope in value.split() if scope)
    if isinstance(value, list) and all(isinstance(scope, str) for scope in value):
        return frozenset(value)
    return frozenset()


def _string_claim(claims: dict[str, object], name: str) -> str | None:
    value = claims.get(name)
    return value if isinstance(value, str) else None
