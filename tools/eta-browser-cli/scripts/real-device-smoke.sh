#!/bin/sh
set -eu

CLI=${ETA_BROWSER_CLI:-eta-browser}
URL=${ETA_BROWSER_SMOKE_URL:-https://example.com}
OUTPUT=${ETA_BROWSER_SMOKE_OUTPUT:-${TMPDIR:-/tmp}/eta-browser-smoke.jpg}

cleanup() {
    "$CLI" session release >/dev/null 2>&1 || true
}
on_signal() {
    signal=$1
    cleanup
    trap - EXIT HUP INT TERM
    case "$signal" in
        HUP) exit 129 ;;
        INT) exit 130 ;;
        TERM) exit 143 ;;
    esac
}
trap cleanup EXIT
trap 'on_signal HUP' HUP
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM

"$CLI" health
"$CLI" session acquire
"$CLI" navigate "$URL"
"$CLI" wait-for-selector h1
"$CLI" get-readable --max-chars 2000
"$CLI" find-elements --selector 'a,button,input'
"$CLI" observe
"$CLI" get-page-info
"$CLI" console --limit 10
"$CLI" network --limit 20
"$CLI" screenshot --output "$OUTPUT"
test -s "$OUTPUT"

"$CLI" session release
trap - EXIT HUP INT TERM
printf 'Eta Browser real-device smoke passed; screenshot=%s\n' "$OUTPUT" >&2
