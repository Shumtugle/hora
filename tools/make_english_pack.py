"""Builds the English language pack from the upstream full-precision English pack.
Usage: python3 make_english_pack.py <english-fp32.zip> <out.zip>
Prints the size and checksum for packs.json."""
import sys, os, json, zipfile, tempfile, hashlib, subprocess
from onnxruntime.quantization import quantize_dynamic, QuantType
import sentencepiece as spm
src, out = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
t = tempfile.mkdtemp(); zipfile.ZipFile(src).extractall(t); M = t + "/models/"
# The engine feeds float32 states; the model declares float16 ones.
subprocess.run([sys.executable, here + "/widen.py", M + "flow_lm_main.onnx", t + "/main_w.onnx"], check=True)
quantize_dynamic(t + "/main_w.onnx", t + "/lm_main.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
quantize_dynamic(M + "flow_lm_flow.onnx", t + "/lm_flow.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
sp = spm.SentencePieceProcessor(model_file=M + "tokenizer.model"); n = sp.get_piece_size()
json.dump({sp.id_to_piece(i): i for i in range(n)}, open(t + "/vocab.json", "w"), ensure_ascii=False)
json.dump({sp.id_to_piece(i): sp.get_score(i) for i in range(n)}, open(t + "/token_scores.json", "w"), ensure_ascii=False)
# Reading settings travel with the pack: tempo heard as the same person, a lead-in that keeps the first word.
json.dump({"language": "en", "version": 1, "tempo": 0.75, "lead": "\u2014 "}, open(t + "/pack.json", "w"))
lic = open(t + "/MODEL_LICENSE.txt").read()
lic += "\n\nChanges for this pack: weights quantized to int8, state inputs and outputs widened to float32.\n"
open(t + "/LICENSE.txt", "w").write(lic)
with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
    for f in ("lm_main.onnx", "lm_flow.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json", "pack.json", "LICENSE.txt"):
        z.write((M if f == "text_conditioner.onnx" else t + "/") + f, f)
h = hashlib.sha256(open(out, "rb").read()).hexdigest()
print(json.dumps({"id": "en", "bytes": os.path.getsize(out), "sha256": h}))
