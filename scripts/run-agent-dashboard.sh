#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/desktop/web/agent-dashboard"
ENV_FILE="$ROOT_DIR/.env"

load_nvm() {
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    return
  fi

  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [[ -s "$NVM_DIR/nvm.sh" ]]; then
    # shellcheck disable=SC1090
    source "$NVM_DIR/nvm.sh"
  fi
}

load_env_file() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi
}

build_frontend() {
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found. Install Node.js / npm first." >&2
    exit 1
  fi

  echo "Building agent dashboard frontend..."
  (
    cd "$FRONTEND_DIR"
    npm run build
  )
}

start_backend() {
  echo "Starting Unciv dashboard server on http://localhost:${UNCIV_AGENT_OBS_PORT:-7071}/ ..."
  cd "$ROOT_DIR"
  exec ./gradlew desktop:run --args="--agentreplay"
}

load_nvm
load_env_file
build_frontend
start_backend
