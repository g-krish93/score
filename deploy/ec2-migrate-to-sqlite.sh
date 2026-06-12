#!/bin/bash
# One-shot RDS PostgreSQL → SQLite migration (reverse of ec2-migrate-to-rds.sh).
# Strategy: stop app → export PG → fresh SQLite → migrate data → remove DATABASE_URL → restart.
set -euo pipefail

APP="${APP:-/app}"
ENV_FILE="$APP/server/.env"
[[ ! -f "$ENV_FILE" ]] && ENV_FILE="$APP/.env"
SQLITE_PATH="$APP/data/cricrelay.db"

DB_URL="$(grep -E '^#? ?DATABASE_URL=' "$ENV_FILE" 2>/dev/null | head -1 | sed 's/^#//' | cut -d= -f2- || true)"
if [[ -z "$DB_URL" ]]; then
  echo "ERROR: DATABASE_URL not set in $ENV_FILE — already on SQLite?" >&2
  exit 1
fi

mkdir -p "$(dirname "$SQLITE_PATH")"
BACKUP_SQLITE="${SQLITE_PATH}.pre-rds-restore.$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -f "$SQLITE_PATH" ]]; then
  cp -a "$SQLITE_PATH" "$BACKUP_SQLITE"
  echo "Backed up stale SQLite to $BACKUP_SQLITE"
fi

echo "Stopping app..."
systemctl stop cricket

# Comment out DATABASE_URL before Python import (load_dotenv() would re-read it otherwise).
if grep -q "^DATABASE_URL=" "$ENV_FILE"; then
  sed -i 's/^DATABASE_URL=/# DATABASE_URL=/' "$ENV_FILE"
  echo "Commented out DATABASE_URL in $ENV_FILE (before schema create)"
fi

pip3 install --quiet psycopg2-binary

python3 << PYEOF
import os, sqlite3, psycopg2, sys
from pathlib import Path

PG_DSN = """$DB_URL"""
SQLITE_PATH = """$SQLITE_PATH"""

TABLES = [
    "cricrelay_org",
    "cricrelay_user",
    "cricrelay_match",
    "cricrelay_stream_session",
    "cricrelay_sponsor",
]

pg = psycopg2.connect(PG_DSN)
pg_cur = pg.cursor()

# Fresh SQLite with schema from app models
if Path(SQLITE_PATH).exists():
    Path(SQLITE_PATH).unlink()

os.environ.pop("DATABASE_URL", None)
os.environ["STATE_DIR"] = str(Path(SQLITE_PATH).parent)
sys.path.insert(0, """$APP""")
from server.app import app, db  # noqa: E402

with app.app_context():
    db.create_all()

sq = sqlite3.connect(SQLITE_PATH)
sq.row_factory = sqlite3.Row
sq_cur = sq.cursor()

def pg_bool_cols(table):
    pg_cur.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name=%s AND data_type='boolean'",
        (table,),
    )
    return {row[0] for row in pg_cur.fetchall()}

def migrate_table(table):
    pg_cur.execute(f"SELECT * FROM {table} ORDER BY id")
    rows = pg_cur.fetchall()
    if not rows:
        print(f"{table}: 0 rows")
        return 0
    cols = [d[0] for d in pg_cur.description]
    bools = pg_bool_cols(table)
    ph = ",".join(["?"] * len(cols))
    col_list = ",".join(cols)
    ok = 0
    for row in rows:
        vals = []
        for c, v in zip(cols, row):
            if c in bools and v is not None:
                vals.append(1 if v else 0)
            else:
                vals.append(v)
        try:
            sq_cur.execute(
                f"INSERT OR REPLACE INTO {table} ({col_list}) VALUES ({ph})",
                vals,
            )
            ok += 1
        except Exception as e:
            print(f"  ERROR {table} id={row[0]}: {e}", file=sys.stderr)
            raise
    sq.commit()
    print(f"{table}: migrated {ok}/{len(rows)} rows")
    return ok

for t in TABLES:
    pg_cur.execute(
        "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name=%s)",
        (t,),
    )
    if not pg_cur.fetchone()[0]:
        print(f"{t}: skip (not in RDS)")
        continue
    migrate_table(t)

for t in TABLES:
    try:
        pg_cur.execute(f"SELECT COUNT(*) FROM {t}")
        pg_n = pg_cur.fetchone()[0]
        sq_n = sq_cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        print(f"Verify {t}: PG={pg_n} SQLite={sq_n}")
        if pg_n != sq_n:
            print(f"ERROR: count mismatch on {t}", file=sys.stderr)
            sys.exit(1)
    except Exception:
        pass

check = sq_cur.execute("PRAGMA integrity_check").fetchone()[0]
print(f"SQLite integrity_check: {check}")
if check != "ok":
    sys.exit(1)

sq.close()
pg.close()
print("Data migration complete.")
PYEOF

echo "Starting app on SQLite..."
systemctl start cricket
for i in $(seq 1 20); do
  if curl -sf http://127.0.0.1:5000/health >/dev/null 2>&1; then
    echo "App healthy on SQLite (attempt $i)."
    break
  fi
  if [[ $i -eq 20 ]]; then
    echo "ERROR: App did not come up." >&2
    journalctl -u cricket -n 60 --no-pager || true
    exit 1
  fi
  sleep 3
done

curl -sf http://127.0.0.1:5000/health
echo ""
echo "Migration complete. App is live on SQLite."
