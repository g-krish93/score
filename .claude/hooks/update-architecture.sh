#!/usr/bin/env bash
set -uo pipefail

# Consume stdin (session JSON payload from Claude Code Stop event)
SESSION_JSON=$(cat)

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"

# Check for code file changes in working tree vs HEAD
CHANGED=$(git -C "$PROJECT_DIR" diff --name-only HEAD 2>/dev/null \
  | grep -E '\.(kt|swift|tsx|jsx|py|gradle|json|yaml)$' || true)

if [ -z "$CHANGED" ]; then
  exit 0
fi

FILES_LIST=$(echo "$CHANGED" | tr '\n' ' ' | sed 's/[[:space:]]*$//')

ARCH_FILE="$PROJECT_DIR/docs/ARCHITECTURE.md"

PROMPT="Update the CricRelay architecture documentation.

Changed files this session: $FILES_LIST

Steps:
1. Read $ARCH_FILE — if it does not exist, create it with a comprehensive architecture overview covering: Android Kotlin multi-module structure, iOS Swift app, Python Flask server, HTML/JS live overlay, Play Cricket API integration, BLE PCS scoring device relay.
2. Read each changed file listed above to understand what changed structurally.
3. Update $ARCH_FILE to reflect: new modules, changed data flows, new dependencies, renamed components, or new features. Keep it concise and architectural — not a line-by-line changelog.
4. Stage and commit: git -C \"$PROJECT_DIR\" add docs/ARCHITECTURE.md && git -C \"$PROJECT_DIR\" commit -m \"docs: auto-update architecture after $FILES_LIST\""

claude --print --dangerously-skip-permissions "$PROMPT" \
  > /tmp/arch-update-$$.log 2>&1

exit 0
