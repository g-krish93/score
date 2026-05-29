#!/usr/bin/env python3
"""Patch Flutter-generated android/ for CricRelay Stream CI (AGP 8.7, JitPack, Gradle 8.10)."""
from __future__ import annotations

import re
import sys
from pathlib import Path


def main() -> int:
    android = Path(sys.argv[1] if len(sys.argv) > 1 else "android").resolve()
    settings = android / "settings.gradle"
    if not settings.is_file():
        print(f"missing {settings}", file=sys.stderr)
        return 1

    text = settings.read_text(encoding="utf-8")
    text = text.replace(
        "RepositoriesMode.FAIL_ON_PROJECT_REPOS",
        "RepositoriesMode.PREFER_PROJECT",
    )
    text = re.sub(
        r'id "com\.android\.application" version "[^"]+"',
        'id "com.android.application" version "8.7.3"',
        text,
    )
    if "com.google.gms.google-services" not in text:
        text = re.sub(
            r'(id "org\.jetbrains\.kotlin\.android" version "[^"]+" apply false)',
            r'\1\n    id "com.google.gms.google-services" version "4.4.2" apply false\n'
            r'    id "com.google.firebase.crashlytics" version "3.0.2" apply false',
            text,
            count=1,
        )
    if "jitpack.io" not in text:

        def add_jitpack(match: re.Match[str]) -> str:
            block = match.group(0)
            if "jitpack.io" in block:
                return block
            return block.replace(
                "mavenCentral()",
                'mavenCentral()\n        maven { url "https://jitpack.io" }',
                1,
            )

        text = re.sub(
            r"dependencyResolutionManagement\s*\{[^}]+\}",
            add_jitpack,
            text,
            count=1,
            flags=re.DOTALL,
        )
        if "jitpack.io" not in text:
            text = text.replace(
                "mavenCentral()",
                'mavenCentral()\n        maven { url "https://jitpack.io" }',
                1,
            )

    settings.write_text(text, encoding="utf-8")
    print("patched", settings)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
