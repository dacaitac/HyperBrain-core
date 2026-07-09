#!/usr/bin/env bash
# PostToolUse(Bash) build recorder for HyperBrain-core (ADR-017 gate #2).
# Writes the freshness marker .claude/.build-passed when a `./gradlew build`
# command printed BUILD SUCCESSFUL. commit-gate.sh consumes this marker.
set -euo pipefail

INPUT="$(cat)"
CMD="$(printf '%s' "$INPUT" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)"
OUT="$(printf '%s' "$INPUT" | python3 -c 'import sys,json;d=json.load(sys.stdin);r=d.get("tool_response","");print(r if isinstance(r,str) else json.dumps(r))' 2>/dev/null || true)"

# Only react to gradle build commands that succeeded.
echo "$CMD" | grep -Eq 'gradlew.*build' || exit 0
echo "$OUT" | grep -q 'BUILD SUCCESSFUL' || exit 0

REPO_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
mkdir -p "$REPO_ROOT/.claude"
touch "$REPO_ROOT/.claude/.build-passed"
exit 0
