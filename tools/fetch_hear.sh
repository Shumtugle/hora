#!/bin/sh
# The small streaming speech recognizer the app carries for its microphone. Not kept in git:
# fetched into assets/hear before a build. Apache-2.0, see the About screen.
set -e
cd "$(dirname "$0")/.."
N=sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16
B=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
T=$(mktemp -d)
curl -sSL -o "$T/m.tar.bz2" "$B/$N.tar.bz2"
tar xjf "$T/m.tar.bz2" -C "$T"
mkdir -p assets/hear
for f in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt; do cp "$T/$N/$f" assets/hear/; done
rm -rf "$T"
ls -l assets/hear
