#!/usr/bin/env bash
# Fetches the YAMNet audio classifier (4.13 MB, ungated, MediaPipe GCS bucket)
# into app/src/main/assets/models/yamnet.tflite.
# The dev network resets long transfers, so loop with resume until complete.
set -u

URL="https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"
OUT="app/src/main/assets/models/yamnet.tflite"
TMP="$OUT.part"

mkdir -p "$(dirname "$OUT")"
for i in $(seq 1 20); do
  echo "attempt $i ($(stat -c%s "$TMP" 2>/dev/null || echo 0) bytes so far)"
  if curl -fsSL -C - --max-time 90 -o "$TMP" "$URL"; then
    mv "$TMP" "$OUT"
    echo "OK: $(stat -c%s "$OUT") bytes -> $OUT"
    exit 0
  fi
  sleep 2
done
echo "FAILED after 20 attempts" >&2
exit 1
