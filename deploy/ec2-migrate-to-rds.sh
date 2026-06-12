#!/bin/bash
# One-shot SQLite → RDS PostgreSQL migration.
# Runs on the EC2 instance via GitHub Actions migrate-to-rds.yml.
# Safe to re-run — uses INSERT ... ON CONFLICT DO NOTHING.
set -euo pipefail

APP="${APP:-/app}"
DB_URL="${DATABASE_URL:-}"

if [[ -z "$DB_URL" ]]; then
  echo "ERROR: DATABASE_URL not set" >&2
  exit 1
fi

# Find the SQLite file
SQLITE_PATH=""
for candidate in \
    "$APP/data/cricrelay.db" \
    "$APP/cricrelay.db" \
    "/var/lib/cricrelay/cricrelay.db" \
    "/tmp/cricrelay.db"; do
  if [[ -f "$candidate" ]]; then
    SQLITE_PATH="$candidate"
    break
  fi
done

# Also check STATE_DIR from the service environment
if [[ -z "$SQLITE_PATH" ]]; then
  STATE_DIR=$(grep -i STATE_DIR "$APP/.env" 2>/dev/null | cut -d= -f2 | tr -d ' "' || true)
  if [[ -n "$STATE_DIR" && -f "$STATE_DIR/cricrelay.db" ]]; then
    SQLITE_PATH="$STATE_DIR/cricrelay.db"
  fi
fi

if [[ -z "$SQLITE_PATH" ]]; then
  echo "ERROR: Could not find SQLite database file" >&2
  find "$APP" -name "*.db" -o -name "*.sqlite" 2>/dev/null || true
  exit 1
fi

echo "Found SQLite at: $SQLITE_PATH"

# Install psycopg2 if needed
pip3 install --quiet psycopg2-binary

# Create schema on PostgreSQL first (Flask apply_migrations handles columns)
cd "$APP"
python3 - <<'PYEOF'
import os, sys
sys.path.insert(0, "/app")
os.environ.setdefault("FLASK_ENV", "production")
from app import app, db
with app.app_context():
    db.create_all()
    from app import apply_migrations
    apply_migrations()
print("Schema ready on PostgreSQL.")
PYEOF

# Migrate data
python3 - <<PYEOF
import os, sqlite3, psycopg2

SQLITE_PATH = "$SQLITE_PATH"
PG_DSN = os.environ["DATABASE_URL"]

sq = sqlite3.connect(SQLITE_PATH)
sq.row_factory = sqlite3.Row
pg = psycopg2.connect(PG_DSN)
cur = pg.cursor()

# Orgs
rows = sq.execute("SELECT * FROM cricrelay_org").fetchall()
print(f"Migrating {len(rows)} organisations...")
for r in rows:
    d = dict(r)
    cur.execute("""
        INSERT INTO cricrelay_org (
            id, slug, name, email, password_hash, play_cricket_base_url,
            public_logo_url, public_primary_color, public_accent_color, ui_theme,
            youtube_refresh_token_enc, youtube_channel_id, youtube_channel_title,
            youtube_connected_at, youtube_active_broadcast_id, youtube_active_stream_id,
            youtube_active_match_slug, twitch_refresh_token_enc, twitch_user_id,
            twitch_login, twitch_display_name, twitch_connected_at,
            twitch_active_match_slug, created_at
        ) VALUES (
            %(id)s, %(slug)s, %(name)s, %(email)s, %(password_hash)s,
            %(play_cricket_base_url)s, %(public_logo_url)s,
            %(public_primary_color)s, %(public_accent_color)s, %(ui_theme)s,
            %(youtube_refresh_token_enc)s, %(youtube_channel_id)s,
            %(youtube_channel_title)s, %(youtube_connected_at)s,
            %(youtube_active_broadcast_id)s, %(youtube_active_stream_id)s,
            %(youtube_active_match_slug)s, %(twitch_refresh_token_enc)s,
            %(twitch_user_id)s, %(twitch_login)s, %(twitch_display_name)s,
            %(twitch_connected_at)s, %(twitch_active_match_slug)s, %(created_at)s
        )
        ON CONFLICT (id) DO NOTHING
    """, d)

# Matches
rows = sq.execute("SELECT * FROM cricrelay_match").fetchall()
print(f"Migrating {len(rows)} relay matches...")
for r in rows:
    d = dict(r)
    cur.execute("""
        INSERT INTO cricrelay_match (
            id, organization_id, play_cricket_match_id, full_scrape_url,
            score_match_slug, label, paused, relay_source, created_at
        ) VALUES (
            %(id)s, %(organization_id)s, %(play_cricket_match_id)s,
            %(full_scrape_url)s, %(score_match_slug)s, %(label)s,
            %(paused)s, %(relay_source)s, %(created_at)s
        )
        ON CONFLICT (id) DO NOTHING
    """, d)

pg.commit()

# Verify
cur.execute("SELECT COUNT(*) FROM cricrelay_org")
pg_orgs = cur.fetchone()[0]
sq_orgs = sq.execute("SELECT COUNT(*) FROM cricrelay_org").fetchone()[0]
cur.execute("SELECT COUNT(*) FROM cricrelay_match")
pg_matches = cur.fetchone()[0]
sq_matches = sq.execute("SELECT COUNT(*) FROM cricrelay_match").fetchone()[0]

print(f"Orgs:    SQLite={sq_orgs}  PostgreSQL={pg_orgs}")
print(f"Matches: SQLite={sq_matches}  PostgreSQL={pg_matches}")

if pg_orgs < sq_orgs or pg_matches < sq_matches:
    print("WARNING: row count mismatch — check for errors above")
    exit(1)

print("Migration complete.")
sq.close()
pg.close()
PYEOF

# Write DATABASE_URL into the app .env so it survives restarts
if grep -q "^DATABASE_URL=" "$APP/.env" 2>/dev/null; then
  sed -i "s|^DATABASE_URL=.*|DATABASE_URL=$DB_URL|" "$APP/.env"
else
  echo "DATABASE_URL=$DB_URL" >> "$APP/.env"
fi

echo "DATABASE_URL written to $APP/.env"
echo "Restart the cricket service to switch to PostgreSQL."
