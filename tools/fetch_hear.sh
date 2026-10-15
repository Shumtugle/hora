#!/bin/sh
# The small streaming speech recognizer the app carries for its microphone. Not kept in git:
# fetched into assets/hear before a build. Apache-2.0, see the About screen.
set -e
cd "$(dirname "$0")/.."
N=sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16
B=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
curl -sSL -o "$T/m.tar.bz2" "$B/$N.tar.bz2"
tar xjf "$T/m.tar.bz2" -C "$T"
mkdir -p assets/hear
for f in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt; do cp "$T/$N/$f" assets/hear/; done
# The word pieces with their scores, as text: what lets the app tell the model which names to expect.
python3 -c 'import sentencepiece' 2>/dev/null || python3 -m pip install --quiet --user sentencepiece \
  || python3 -m pip install --quiet --break-system-packages sentencepiece
python3 - "$T/$N/bpe.model" assets/hear/bpe.vocab <<'PY'
import sys, sentencepiece as s
m = s.SentencePieceProcessor(model_file=sys.argv[1])
with open(sys.argv[2], "w", encoding="utf-8") as f:
    for i in range(m.get_piece_size()):
        f.write("%s\t%s\n" % (m.id_to_piece(i), m.get_score(i)))
PY
ls -l assets/hear
