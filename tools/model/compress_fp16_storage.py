"""Shrink an fp32 ONNX model ~2x by storing large weights as float16.

Each large float32 initializer W is replaced by a float16 initializer plus a
`Cast(to=float32)` node. ONNX Runtime constant-folds those casts when the
session is created, so inference still runs entirely in float32 (fp16
*compute* overflows in HT-Demucs and yields NaNs); only the file shrinks.
"""
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto


def main(src, dst, min_elems=1024):
    m = onnx.load(src)
    g = m.graph
    new_inits, casts = [], []
    for init in g.initializer:
        if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= min_elems:
            arr = numpy_helper.to_array(init)
            h = numpy_helper.from_array(arr.astype(np.float16), init.name + "__fp16")
            new_inits.append(h)
            casts.append(helper.make_node("Cast", [h.name], [init.name], to=TensorProto.FLOAT, name=init.name + "__cast"))
        else:
            new_inits.append(init)
    del g.initializer[:]
    g.initializer.extend(new_inits)
    nodes = list(g.node)
    del g.node[:]
    g.node.extend(casts + nodes)
    onnx.save(m, dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
