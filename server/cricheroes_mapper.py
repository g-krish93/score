"""Transform a CricHeroes scraper snapshot into overlay-ready JSON."""
from __future__ import annotations

from typing import Any

from .overlay_mapping_common import snapshot_to_overlay as _snapshot_to_overlay

__all__ = ["snapshot_to_overlay"]


def snapshot_to_overlay(
    snapshot: dict,
    stale: bool = False,
    last_ok_at: Any = None,
) -> dict:
    return _snapshot_to_overlay(snapshot, stale=stale, last_ok_at=last_ok_at)
