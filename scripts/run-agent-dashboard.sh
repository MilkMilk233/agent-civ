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
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
      [[ "$line" != *=* ]] && continue

      key="${line%%=*}"
      value="${line#*=}"
      key="${key#"${key%%[![:space:]]*}"}"
      key="${key%"${key##*[![:space:]]}"}"

      if [[ -z "${!key+x}" ]]; then
        export "$key=$value"
      fi
    done < "$ENV_FILE"
  fi
}

build_frontend() {
  if [[ "${UNCIV_AGENT_SKIP_FRONTEND_BUILD:-false}" =~ ^(1|true|yes)$ ]]; then
    echo "Skipping agent dashboard frontend build."
    return
  fi

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
  export UNCIV_AGENT_WORKSPACE_DIR="$ROOT_DIR"
  if [[ "${UNCIV_AGENT_USE_DIST_JAR:-false}" =~ ^(1|true|yes)$ ]] && [[ -f "$ROOT_DIR/desktop/build/libs/Unciv.jar" ]]; then
    cd "$ROOT_DIR/android/assets"
    exec java -jar "$ROOT_DIR/desktop/build/libs/Unciv.jar" --agentreplay
  fi
  exec ./gradlew desktop:run --args="--agentreplay"
}

load_nvm
load_env_file
build_frontend
start_backend
