#!/usr/bin/env bash
# Optimiza y sube tus fondos (imágenes y videos) al TV. Nada de esto se guarda en el repo.
#  - Imágenes: 1920x1080 (center-crop), JPEG q85, sin EXIF (GPS, cámara, fecha)
#  - Videos:   1080p, sin audio, sin metadatos, H.264 (o VP9 si tu ffmpeg no trae H.264)
#  - Renombra a 001, 002... → ese orden define la rotación (1 fondo por día)
#
# Uso: TV_HOST=<ip> ./scripts/push-wallpapers.sh <carpeta> [<carpeta> ...]
set -euo pipefail

: "${TV_HOST:?Define TV_HOST con la IP de tu TV}"
[[ $# -ge 1 ]] || { echo "Pasa al menos una carpeta de fondos"; exit 1; }

PKG="dev.davidcevallos.launchertv"
REMOTE="/sdcard/Android/data/$PKG/files/wallpapers"
VF="scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,fps=30"

need() { command -v "$1" >/dev/null || { echo "Falta $1 → $2"; exit 1; }; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Elige el mejor encoder disponible (ffmpeg-free de Fedora no trae libx264; el de RPM Fusion sí)
video_encoder() {
  local enc; enc="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
  if   grep -q libx264    <<<"$enc"; then echo "x264"
  elif grep -q libopenh264 <<<"$enc"; then echo "openh264"
  elif grep -q libvpx-vp9 <<<"$enc"; then echo "vp9"
  else echo "none"; fi
}

i=0
ENC=""
for SRC in "$@"; do
  while IFS= read -r -d '' f; do
    i=$((i + 1)); n="$(printf '%03d' "$i")"
    case "${f,,}" in
      *.jpg|*.jpeg|*.png|*.webp|*.heic)
        need magick "sudo dnf install ImageMagick"
        magick "$f" -auto-orient -resize 1920x1080^ -gravity center -extent 1920x1080 \
          -strip -quality 85 "$TMP/$n.jpg"
        echo "✓ img  $(basename "$f") → $n.jpg" ;;
      *.mp4|*.mkv|*.webm|*.mov)
        need ffmpeg "sudo dnf install ffmpeg-free  (o ffmpeg de RPM Fusion para H.264)"
        [[ -z "$ENC" ]] && ENC="$(video_encoder)"
        case "$ENC" in
          x264)     ffmpeg -nostdin -loglevel error -y -i "$f" -an -map_metadata -1 -vf "$VF" \
                      -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -movflags +faststart "$TMP/$n.mp4"; out="$n.mp4" ;;
          openh264) ffmpeg -nostdin -loglevel error -y -i "$f" -an -map_metadata -1 -vf "$VF" \
                      -c:v libopenh264 -b:v 6M -pix_fmt yuv420p -movflags +faststart "$TMP/$n.mp4"; out="$n.mp4" ;;
          vp9)      ffmpeg -nostdin -loglevel error -y -i "$f" -an -map_metadata -1 -vf "$VF" \
                      -c:v libvpx-vp9 -b:v 0 -crf 33 -deadline realtime -cpu-used 8 -row-mt 1 "$TMP/$n.webm"; out="$n.webm" ;;
          *)        echo "Tu ffmpeg no tiene encoder H.264/VP9"; exit 1 ;;
        esac
        echo "✓ vid  $(basename "$f") → $out ($ENC)" ;;
      *) i=$((i - 1)) ;;
    esac
  done < <(find "$SRC" -maxdepth 1 -type f -print0 | sort -z)
done

[[ $i -gt 0 ]] || { echo "No se encontraron imágenes ni videos"; exit 1; }

adb connect "$TV_HOST:5555" >/dev/null
adb shell "mkdir -p '$REMOTE' && rm -f '$REMOTE'/*"
adb push "$TMP"/. "$REMOTE/" >/dev/null
echo "Subidos $i fondos a $REMOTE ($(du -sh "$TMP" | cut -f1))"
echo "Se aplican al volver a Home."
