#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/main/assets/models
curl --fail --location --retry 3 --show-error --silent \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2 \
  -o app/src/main/assets/models/kokoro.tar.bz2
curl --fail --location --retry 3 --show-error --silent \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-vctk.tar.bz2 \
  -o app/src/main/assets/models/vits.tar.bz2
echo "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4  app/src/main/assets/models/kokoro.tar.bz2" | sha256sum -c -
echo "4f0a02db66914b3760b144cebc004e65dd4d1aeef43379f2b058849e74002490  app/src/main/assets/models/vits.tar.bz2" | sha256sum -c -
bash scripts/fetch-supertonic-voices.sh
