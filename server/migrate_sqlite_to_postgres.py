"""Migrate CricRelay relational data from SQLite to Postgres.

Part of the strangler cut-over: before switching the app's DATABASE_URL to the
self-hosted Postgres, copy the existing SQLite data across so nothing is lost.

Usage:
    # report what would be copied, no writes, no target needed:
    python -m server.migrate_sqlite_to_postgres --dry-run

    # copy SQLite -> Postgres (target empties matching tables first):
    python -m server.migrate_sqlite_to_postgres \
        --source sqlite:////tmp/cricrelay.db \
        --target postgresql+psycopg2://cricrelay:PASS@172.31.38.51:5432/cricrelay \
        --truncate

Source/target may also come from env: CR_SQLITE_URL / CR_PG_URL.
The app's own models (db.create_all) define the authoritative Postgres schema;
this script reflects the SQLite schema and will create any missing tables, then
copies the rows.
"""
from __future__ import annotations

import argparse
import os

from sqlalchemy import MetaData, create_engine, func, insert, select


def _default_sqlite_url() -> str:
    state_dir = os.getenv("STATE_DIR", "/tmp")
    return f"sqlite:///{os.path.join(state_dir, 'cricrelay.db')}"


def main() -> int:
    ap = argparse.ArgumentParser(description="Copy CricRelay data SQLite -> Postgres")
    ap.add_argument("--source", default=os.getenv("CR_SQLITE_URL") or _default_sqlite_url())
    ap.add_argument("--target", default=os.getenv("CR_PG_URL", ""))
    ap.add_argument("--dry-run", action="store_true", help="report counts, write nothing")
    ap.add_argument("--truncate", action="store_true", help="empty target tables before copy")
    args = ap.parse_args()

    if not args.target and not args.dry_run:
        ap.error("--target (or CR_PG_URL) is required unless --dry-run")

    src = create_engine(args.source)
    meta = MetaData()
    meta.reflect(bind=src)
    tables = list(meta.sorted_tables)

    print(f"source : {args.source}")
    print(f"tables : {[t.name for t in tables] or 'none found'}")
    with src.connect() as s:
        for t in tables:
            n = s.execute(select(func.count()).select_from(t)).scalar_one()
            print(f"  {t.name}: {n} rows")

    if args.dry_run:
        print("dry-run: no writes performed.")
        return 0

    dst = create_engine(args.target)
    meta.create_all(bind=dst)  # create any missing tables from the reflected schema
    copied = 0
    with src.connect() as s, dst.begin() as d:
        for t in tables:
            if args.truncate:
                d.execute(t.delete())
            rows = [dict(r._mapping) for r in s.execute(select(t))]
            if rows:
                d.execute(insert(t), rows)
            copied += len(rows)
            print(f"  copied {len(rows):>5} -> {t.name}")
    print(f"done: {copied} rows into {args.target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
