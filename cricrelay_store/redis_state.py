"""Redis live-state + pub/sub for the realtime path.

After each ball, the web layer recomputes the scoreboard from the event log and
calls publish_scoreboard(): the latest view is cached (fast read for the public
page) AND published on a per-match channel that the realtime pusher fans out to
connected viewers — replacing the old 2s polling.
"""
from __future__ import annotations

import json

import redis


class RedisLiveState:
    def __init__(self, url: str | None = None, client=None) -> None:
        # `client` injection lets tests pass an in-process fake; production
        # passes a url.
        self._r = client if client is not None else redis.Redis.from_url(
            url, decode_responses=True
        )

    @staticmethod
    def _key(match_id: str) -> str:
        return f"live:{match_id}"

    @staticmethod
    def channel(match_id: str) -> str:
        return f"score:{match_id}"

    def publish_scoreboard(self, match_id: str, view: dict) -> None:
        payload = json.dumps(view, separators=(",", ":"))
        self._r.set(self._key(match_id), payload)
        self._r.publish(self.channel(match_id), payload)

    def get_scoreboard(self, match_id: str) -> dict | None:
        raw = self._r.get(self._key(match_id))
        return json.loads(raw) if raw else None

    def ping(self) -> bool:
        return bool(self._r.ping())
