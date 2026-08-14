#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CLI_SOURCE="$ROOT/tools/eta-browser-cli"
EXTENSION_SOURCE="$ROOT/pi/eta-browser-extension"
SKILL_SOURCE="$ROOT/pi/skills/eta-browser"
LOCAL_PREFIX=${ETA_BROWSER_LOCAL_PREFIX:-"$HOME/.local"}
PI_HOME_DIR=${pi_HOME:-"$HOME/.pi"}
EXTENSION_TARGET="$PI_HOME_DIR/agent/extensions/eta-browser"
SKILL_TARGET="$PI_HOME_DIR/agent/skills/eta-browser"

command -v node >/dev/null 2>&1 || {
    printf '%s\n' 'Node.js 20 or newer is required.' >&2
    exit 1
}
command -v npm >/dev/null 2>&1 || {
    printf '%s\n' 'npm is required.' >&2
    exit 1
}

npm install --global --prefix "$LOCAL_PREFIX" --ignore-scripts --no-package-lock "$CLI_SOURCE"

mkdir -p "$EXTENSION_TARGET" "$SKILL_TARGET"
cp "$EXTENSION_SOURCE/index.ts" "$EXTENSION_TARGET/index.ts"
cp "$EXTENSION_SOURCE/core.mjs" "$EXTENSION_TARGET/core.mjs"
cp "$SKILL_SOURCE/SKILL.md" "$SKILL_TARGET/SKILL.md"

ETA_BROWSER_EXTENSION_TARGET="$EXTENSION_TARGET" \
ETA_BROWSER_CLI_SOURCE="$CLI_SOURCE" \
node <<'NODE'
import { writeFileSync } from "node:fs";
import path from "node:path";

const target = process.env.ETA_BROWSER_EXTENSION_TARGET;
const cli = process.env.ETA_BROWSER_CLI_SOURCE;
const manifest = {
  name: "eta-browser-pi-extension",
  version: "1.0.0",
  private: true,
  type: "module",
  dependencies: {
    "eta-browser-cli": `file:${cli}`,
  },
};
writeFileSync(path.join(target, "package.json"), `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
NODE

npm install --prefix "$EXTENSION_TARGET" --omit=dev --ignore-scripts --no-package-lock

printf '%s\n' "Installed eta-browser CLI under $LOCAL_PREFIX"
printf '%s\n' "Installed Pi extension at $EXTENSION_TARGET"
printf '%s\n' "Installed Pi skill at $SKILL_TARGET"
printf '%s\n' 'Restart Pi before using eta_browser_use.'
