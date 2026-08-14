#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if grep -R -n 'fuck\.andes\.browser' "$ROOT/app"; then
    printf '%s\n' 'Retired Android package reference remains.' >&2
    exit 1
fi

grep -F 'namespace = "com.thoitiettxl.eta"' "$ROOT/app/build.gradle.kts" >/dev/null
grep -F 'applicationId = "com.thoitiettxl.eta"' "$ROOT/app/build.gradle.kts" >/dev/null
grep -F 'versionName = "1.0.0"' "$ROOT/app/build.gradle.kts" >/dev/null

printf '%s\n' 'Android package identity check passed.'
