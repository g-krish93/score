#!/bin/bash
# One-shot SQLite → RDS PostgreSQL migration.
# Strategy: write DATABASE_URL → restart app (creates schema) → stop → migrate data → restart.
set -euo pipefail

APP="${APP:-/app}"
DB_URL="${DATABASE_URL:-}"

if [[ -z "$DB_URL" ]]; then
  echo "ERROR: DATABASE_URL not set" >&2
  exit 1
fi

# ── 1. Find SQLite file ────────────────────────────────────────────────────────
SQLITE_PATH=""
for candidate in \
    "$APP/data/cricrelay.db" \
    "$APP/server/cricrelay.db" \
    "$APP/cricrelay.db" \
    "/var/lib/cricrelay/cricrelay.db"; do
  if [[ -f "$candidate" ]]; then
    SQLITE_PATH="$candidate"
    break
  fi
done

if [[ -z "$SQLITE_PATH" ]]; then
  STATE_DIR=$(grep -i STATE_DIR "$APP/server/.env" "$APP/.env" 2>/dev/null \
    | head -1 | cut -d= -f2 | tr -d ' "' || true)
  if [[ -n "$STATE_DIR" && -f "$STATE_DIR/cricrelay.db" ]]; then
    SQLITE_PATH="$STATE_DIR/cricrelay.db"
  fi
fi

if [[ -z "$SQLITE_PATH" ]]; then
  echo "ERROR: Cannot find SQLite database file" >&2
  find "$APP" /var/lib -name "*.db" -o -name "*.sqlite" 2>/dev/null || true
  exit 1
fi
echo "SQLite found: $SQLITE_PATH"

# ── 2. Write DATABASE_URL to .env so app starts with PostgreSQL ───────────────
ENV_FILE="$APP/server/.env"
[[ ! -f "$ENV_FILE" ]] && ENV_FILE="$APP/.env"

if grep -q "^DATABASE_URL=" "$ENV_FILE" 2>/dev/null; then
  sed -i "s|^DATABASE_URL=.*|DATABASE_URL=$DB_URL|" "$ENV_FILE"
else
  echo "DATABASE_URL=$DB_URL" >> "$ENV_FILE"
fi
echo "DATABASE_URL written to $ENV_FILE"

# ── 3. Restart app — Flask db.create_all() + apply_migrations() run on boot ──
echo "Restarting app to create PostgreSQL schema..."
systemctl restart cricket
for i in $(seq 1 20); do
  if curl -sf http://127.0.0.1:5000/health >/dev/null 2>&1; then
    echo "App healthy on PostgreSQL (attempt $i)."
    break
  fi
  if [[ $i -eq 20 ]]; then
    echo "ERROR: App did not come up after schema creation." >&2
    journalctl -u cricket -n 60 --no-pager || true
    exit 1
  fi
  sleep 3
done

# ── 4. Stop app for clean data migration ──────────────────────────────────────
systemctl stop cricket
echo "App stopped for data migration."

# ── 5. Install psycopg2 and migrate data ──────────────────────────────────────
pip3 install --quiet psycopg2-binary

python3 << PYEOF
import sqlite3, psycopg2, sys

SQLITE_PATH = "$SQLITE_PATH"
PG_DSN = "$DB_URL"

sq = sqlite3.connect(SQLITE_PATH)
sq.row_factory = sqlite3.Row
pg = psycopg2.connect(PG_DSN)
cur = pg.cursor()

def bool_cols_for(table):
    cur.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name=%s AND data_type='boolean'",
        (table,),
    )
    return {row[0] for row in cur.fetchall()}

def migrate_table(table):
    bools = bool_cols_for(table)
    rows = sq.execute(f"SELECT * FROM {table}").fetchall()
    print(f"Migrating {len(rows)} row(s) from {table}...")
    ok = 0
    for r in rows:
        d = dict(r)
        cols = list(d.keys())
        vals = [bool(v) if c in bools and v is not None else v
                for c, v in zip(cols, d.values())]
        ph = ",".join(["%s"] * len(cols))
        try:
            cur.execute(
                f"INSERT INTO {table} ({','.join(cols)}) VALUES ({ph}) ON CONFLICT (id) DO NOTHING",
                vals,
            )
            ok += 1
        except Exception as e:
            print(f"  Skipping {d.get('id')}: {e}")
            pg.rollback()
    pg.commit()
    return len(rows), ok

sq_orgs, ok_orgs = migrate_table("cricrelay_org")
sq_matches, ok_matches = migrate_table("cricrelay_match")

cur.execute("SELECT COUNT(*) FROM cricrelay_org"); pg_orgs = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM cricrelay_match"); pg_matches = cur.fetchone()[0]

print(f"Orgs:    SQLite={sq_orgs}  PostgreSQL={pg_orgs}")
print(f"Matches: SQLite={sq_matches}  PostgreSQL={pg_matches}")

if pg_orgs < sq_orgs or pg_matches < sq_matches:
    print("ERROR: row count mismatch", file=sys.stderr)
    sys.exit(1)

print("Data migration complete.")
sq.close(); pg.close()
PYEOF

# ── 6. Restart app on PostgreSQL ──────────────────────────────────────────────
systemctl start cricket
sleep 5
if ! systemctl is-active --quiet cricket; then
  echo "ERROR: App failed to start after migration" >&2
  journalctl -u cricket -n 60 --no-pager || true
  exit 1
fi

curl -sf http://127.0.0.1:5000/health
echo ""
echo "Migration complete. App is live on PostgreSQL."
