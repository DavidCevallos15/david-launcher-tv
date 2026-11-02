#!/usr/bin/env bash
# Deshabilita (no desinstala) los paquetes de debloat-packages.txt y registra el rollback.
# Uso: TV_HOST=<ip-del-tv> ./scripts/debloat.sh
set -euo pipefail

: "${TV_HOST:?Define TV_HOST con la IP de tu TV, ej: TV_HOST=192.168.x.x}"
DIR="$(cd "$(dirname "$0")" && pwd)"
LIST="$DIR/debloat-packages.txt"
LOG="$DIR/../DEBLOAT-LOG.md"   # ignorado por git

adb connect "$TV_HOST:5555" >/dev/null
DISABLED="$(adb shell pm list packages -d | tr -d '\r')"

echo "# Debloat log — $(date -Iseconds)" >> "$LOG"
grep -vE '^\s*(#|$)' "$LIST" | while read -r pkg; do
  if grep -qx "package:$pkg" <<<"$DISABLED"; then
    echo "· $pkg ya estaba deshabilitado (de fábrica o antes)"
    continue
  fi
  if adb shell pm disable-user --user 0 "$pkg" 2>&1 | grep -q "new state"; then
    echo "✓ $pkg"
    echo "- \`$pkg\` → \`adb shell pm enable $pkg\`" >> "$LOG"
  else
    echo "✗ $pkg (no instalado o protegido)"
  fi
done
echo "Log: $LOG"
