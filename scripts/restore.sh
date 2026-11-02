#!/usr/bin/env bash
# Rehabilita todo lo de debloat-packages.txt y vuelve al launcher de fábrica.
# Uso: TV_HOST=<ip-del-tv> ./scripts/restore.sh
set -euo pipefail

: "${TV_HOST:?Define TV_HOST con la IP de tu TV}"
DIR="$(cd "$(dirname "$0")" && pwd)"

adb connect "$TV_HOST:5555" >/dev/null
grep -vE '^\s*(#|$)' "$DIR/debloat-packages.txt" | while read -r pkg; do
  adb shell pm enable "$pkg" >/dev/null 2>&1 && echo "✓ $pkg" || echo "· $pkg"
done
adb shell pm enable com.google.android.tvlauncher >/dev/null 2>&1 || true
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity >/dev/null 2>&1 || true
echo "Listo. Presiona Home en el control para confirmar el launcher de fábrica."
