"""Lightweight in-memory rate limiter for auth endpoints."""
from __future__ import annotations

import os
import threading
import time
from collections import defaultdict
from functools import wraps
from typing import Callable, TypeVar

from flask import flash, jsonify, redirect, render_template, request, url_for

F = TypeVar("F", bound=Callable)

_DEFAULT_MAX = int(os.getenv("AUTH_RATE_LIMIT_MAX", "5"))
_DEFAULT_WINDOW = int(os.getenv("AUTH_RATE_LIMIT_WINDOW_SEC", "900"))


class AuthRateLimiter:
    def __init__(self, max_attempts: int = _DEFAULT_MAX, window_sec: int = _DEFAULT_WINDOW):
        self.max_attempts = max_attempts
        self.window_sec = window_sec
        self._hits: dict[str, list[float]] = defaultdict(list)
        self._lock = threading.Lock()

    def _client_ip(self) -> str:
        forwarded = (request.headers.get("X-Forwarded-For") or "").split(",")[0].strip()
        return forwarded or (request.remote_addr or "unknown")

    def _ip_key(self, scope: str) -> str:
        return f"{scope}:ip:{self._client_ip()}"

    def _email_key(self, scope: str, email: str) -> str:
        return f"{scope}:email:{email.lower()}"

    def _is_over_limit(self, key: str) -> bool:
        """Check the hit count for *key* without recording a new hit."""
        now = time.monotonic()
        cutoff = now - self.window_sec
        with self._lock:
            recent = [t for t in self._hits[key] if t > cutoff]
            self._hits[key] = recent
            return len(recent) >= self.max_attempts

    def _check_and_record(self, key: str) -> bool:
        """Check and unconditionally record a hit; return True when over limit."""
        now = time.monotonic()
        cutoff = now - self.window_sec
        with self._lock:
            recent = [t for t in self._hits[key] if t > cutoff]
            if len(recent) >= self.max_attempts:
                self._hits[key] = recent
                return True
            recent.append(now)
            self._hits[key] = recent
            return False

    def _record(self, key: str) -> None:
        with self._lock:
            self._hits[key].append(time.monotonic())

    def _clear(self, key: str) -> None:
        with self._lock:
            self._hits.pop(key, None)

    # ------------------------------------------------------------------
    # Public helpers for explicit (non-decorator) tracking
    # ------------------------------------------------------------------

    def is_limited(self, scope: str, email: str | None = None) -> bool:
        """Return True if the current IP or the given email account is over the limit."""
        if self._is_over_limit(self._ip_key(scope)):
            return True
        if email and self._is_over_limit(self._email_key(scope, email)):
            return True
        return False

    def record_failed(self, scope: str, email: str | None = None) -> None:
        """Record one failed attempt against the IP and, optionally, the email."""
        self._record(self._ip_key(scope))
        if email:
            self._record(self._email_key(scope, email))

    def clear_on_success(self, scope: str, email: str | None = None) -> None:
        """Reset counters after a successful login so legitimate users aren't locked out."""
        self._clear(self._ip_key(scope))
        if email:
            self._clear(self._email_key(scope, email))

    # ------------------------------------------------------------------
    # Decorator — used for register / forgot-password (counts every POST)
    # For login use count_on_attempt=False and call record_failed / clear_on_success manually.
    # ------------------------------------------------------------------

    def limit_auth(
        self,
        *,
        scope: str,
        json_endpoint: bool = False,
        html_template: str | None = None,
        count_on_attempt: bool = True,
    ) -> Callable[[F], F]:
        def decorator(view: F) -> F:
            @wraps(view)
            def wrapped(*args, **kwargs):
                if request.method != "POST":
                    return view(*args, **kwargs)
                ip_key = self._ip_key(scope)
                limited = (
                    self._check_and_record(ip_key)
                    if count_on_attempt
                    else self._is_over_limit(ip_key)
                )
                if not limited:
                    return view(*args, **kwargs)
                message = "Too many attempts. Please wait 15 minutes and try again."
                if json_endpoint or request.path.startswith("/api/"):
                    return jsonify({"error": message}), 429
                flash(message, "error")
                if html_template:
                    return render_template(html_template), 429
                if scope == "register":
                    return redirect(url_for("register_page"))
                if scope == "forgot-password":
                    return redirect(url_for("forgot_password_page"))
                return redirect(url_for("login_page"))

            return wrapped  # type: ignore[return-value]

        return decorator


auth_limiter = AuthRateLimiter()
