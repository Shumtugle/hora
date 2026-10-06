# Widens 16-bit float inputs and outputs of an ONNX graph to 32-bit, as the engine feeds 32-bit states.
# Usage: python3 widen.py in.onnx out.onnx   (needs: pip install onnx)
import sys, onnx
from onnx import helper, TensorProto
def widen(a, b):
    m = onnx.load(a); g = m.graph; head = []; tail = []
    for i in g.input:
        if i.type.tensor_type.elem_type == TensorProto.FLOAT16:
            old = i.name; inner = old + "__h"
            for n in g.node:
                for k, x in enumerate(n.input):
                    if x == old: n.input[k] = inner
            i.type.tensor_type.elem_type = TensorProto.FLOAT
            head.append(helper.make_node("Cast", [old], [inner], to=TensorProto.FLOAT16, name="widen_in_" + old))
    for o in g.output:
        if o.type.tensor_type.elem_type == TensorProto.FLOAT16:
            old = o.name; inner = old + "__h"
            for n in g.node:
                for k, x in enumerate(n.output):
                    if x == old: n.output[k] = inner
                for k, x in enumerate(n.input):
                    if x == old: n.input[k] = inner
            o.type.tensor_type.elem_type = TensorProto.FLOAT
            tail.append(helper.make_node("Cast", [inner], [old], to=TensorProto.FLOAT, name="widen_out_" + old))
    nodes = head + list(g.node) + tail; del g.node[:]; g.node.extend(nodes); onnx.save(m, b)
if __name__ == "__main__":
    widen(sys.argv[1], sys.argv[2])
