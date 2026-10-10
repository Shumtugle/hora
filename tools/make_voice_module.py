"""Packs the voice and the full decoder as modules the app fetches.

Usage: python3 make_voice_module.py <voice dir> <full decoder .onnx> <license dir> <out dir>
The voice dir holds the speech model as the app reads it (widened graphs, tokenizer tables).
Writes hora-voice-ru-1.zip and hora-decoder-full-1.zip, and prints size and checksum of each."""
import sys, os, json, zipfile, hashlib

voice, full, lic, out = sys.argv[1:5]
os.makedirs(out, exist_ok=True)
VOICE = ["lm_main.onnx", "lm_flow.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json",
         "encoder.onnx", "decoder.onnx"]
notice = open(os.path.join(lic, "voice-model.txt"), encoding="utf-8").read()


def write(name, files, meta):
    path = os.path.join(out, name)
    # Fixed times keep the archive identical from one build to the next.
    stamp = (2026, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for arc, src in files:
            info = zipfile.ZipInfo(arc, stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(src, "rb") as f:
                z.writestr(info, f.read())
        for arc, text in (("pack.json", json.dumps(meta)), ("LICENSE.txt", notice)):
            info = zipfile.ZipInfo(arc, stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, text)
    h = hashlib.sha256(open(path, "rb").read()).hexdigest()
    print(json.dumps({"id": meta["id"], "file": name, "bytes": os.path.getsize(path), "sha256": h}))


write("hora-voice-ru-1.zip", [(f, os.path.join(voice, f)) for f in VOICE],
      {"id": "ru", "language": "ru", "version": 1})
write("hora-decoder-full-1.zip", [("decoder_full.onnx", full)],
      {"id": "decoder", "language": "", "version": 1})
