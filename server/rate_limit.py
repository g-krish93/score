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

    def _too_many(self, scope: str) -> bool:
        key = f"{scope}:{self._client_ip()}"
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

    def limit_auth(
        self,
        *,
        scope: str,
        json_endpoint: bool = False,
        html_template: str | None = None,
    ) -> Callable[[F], F]:
        def decorator(view: F) -> F:
            @wraps(view)
            def wrapped(*args, **kwargs):
                if request.method != "POST":
                    return view(*args, **kwargs)
                if not self._too_many(scope):
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
