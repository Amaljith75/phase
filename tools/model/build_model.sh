#!/usr/bin/env bash
# Builds the on-device AI model into the Android app's assets:
#   htdemucs (PyTorch) -> ONNX core -> fp16 weight storage (~86 MB)
# then verifies the ONNX pipeline against PyTorch Demucs on a test signal.
# Requires: python3 with tools/model/requirements.txt installed, ffmpeg.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/android/app/src/main/assets/models"
WORK="${WORK:-$(mktemp -d)}"
PY="${PYTHON:-python3}"
mkdir -p "$OUT" "$WORK"

"$PY" "$HERE/export_htdemucs_onnx.py" --out "$WORK/htdemucs_fp32.onnx"
"$PY" "$HERE/compress_fp16_storage.py" "$WORK/htdemucs_fp32.onnx" "$OUT/htdemucs.onnx"

# Equivalence check vs PyTorch on a 20 s synthetic mix.
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=55:duration=20" -f lavfi -i "anoisesrc=d=20:c=pink:a=0.05" \
  -f lavfi -i "sine=frequency=660:duration=20" -filter_complex "[0][1][2]amix=inputs=3,volume=2" -ac 2 -ar 44100 "$WORK/check.wav"
"$PY" "$HERE/reference_pipeline.py" --onnx "$OUT/htdemucs.onnx" --audio "$WORK/check.wav" --dump "$WORK/vectors" | tee "$WORK/check.txt"
if grep -E "SNR vs PyTorch= *([0-9]|[1-3][0-9])\.[0-9] dB" "$WORK/check.txt"; then
  echo "Model check FAILED (SNR below 40 dB)" >&2; exit 1
fi
sha256sum "$OUT/htdemucs.onnx"
echo "Model written to $OUT/htdemucs.onnx (test vectors: $WORK/vectors)"
