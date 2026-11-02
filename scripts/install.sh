#!/usr/bin/env bash
# Instala el APK y lo define como Home.
# Uso: TV_HOST=<ip> ./scripts/install.sh [ruta.apk] [--disable-stock]
#   --disable-stock  deshabilita el launcher de Google si el TV ignora el Home por defecto (reversible con restore.sh)
set -euo pipefail

: "${TV_HOST:?Define TV_HOST con la IP de tu TV}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="dev.davidcevallos.launchertv"

adb connect "$TV_HOST:5555" >/dev/null
adb install -r "$APK"
adb shell cmd package set-home-activity "$PKG/.MainActivity"

if [[ "${2:-}" == "--disable-stock" ]]; then
  adb shell pm disable-user --user 0 com.google.android.tvlauncher
  echo "Launcher de fábrica deshabilitado (rollback: ./scripts/restore.sh)"
fi

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Listo. Presiona Home en el control."
