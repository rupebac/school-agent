#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker run --rm --init --read-only --cap-drop=ALL \
  --security-opt no-new-privileges \
  --security-opt "seccomp=$PROJECT_ROOT/deploy/seccomp_profile.json" \
  --tmpfs /tmp:size=256m,mode=1777 --shm-size=256m --memory=768m --cpus=1 --pids-limit=256 \
  -v "$PROJECT_ROOT/playwright-worker/isolation-smoke.js:/app/isolation-smoke.js:ro" \
  "${1:-school-agent-worker:test}" node /app/isolation-smoke.js
