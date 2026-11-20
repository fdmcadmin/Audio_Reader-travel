#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="build/supertonic-upstream"
mkdir -p "$work"
curl --fail --location --retry 3 --show-error --silent \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2 \
  -o "$work/stock.tar.bz2"
curl --fail --location --retry 3 --show-error --silent \
  https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/aafc6e3/LICENSE \
  -o "$work/MODEL-LICENSE.txt"
python3 scripts/build_supertonic_voice_pack.py --bundle "$work/stock.tar.bz2" \
  --license "$work/MODEL-LICENSE.txt" --out app/src/main/assets/supertonic3 --quads
