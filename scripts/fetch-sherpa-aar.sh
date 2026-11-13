#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
file="app/libs/sherpa-onnx-2.0.1.aar"
curl --fail --location --retry 3 --show-error "$url" -o "$file"
echo "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  $file" | sha256sum -c -
echo "Pinned sherpa-onnx v2.0.1 installed: $file"
