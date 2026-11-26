"""Reference implementation of the on-device pipeline (NumPy + ONNX Runtime).

This mirrors, step by step, what the Android app does in
OnDeviceDemucs.kt, and is checked against the original PyTorch
`demucs.apply.apply_model`. Usage:

    python reference_pipeline.py --onnx htdemucs.onnx --audio song.wav [--dump DIR]

--dump writes the test vectors used by the Kotlin unit test.
"""
from __future__ import annotations

import argparse
import math
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

NFFT, HOP, SEG, SR = 4096, 1024, 343980, 44100
WIN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / NFFT)).astype(np.float64)  # periodic Hann


def reflect_pad(x, left, right):
    return np.pad(x, ((0, 0), (left, right)), mode="reflect")


def stft(x):  # torch.stft(center=True, reflect, normalized=True, onesided)
    x = reflect_pad(x, NFFT // 2, NFFT // 2)
    frames = 1 + (x.shape[-1] - NFFT) // HOP
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(frames)[:, None]
    seg = x[:, idx] * WIN  # (C, T, NFFT)
    return np.fft.rfft(seg, axis=-1).transpose(0, 2, 1) / math.sqrt(NFFT)  # (C, F, T)


def istft(z, length):  # torch.istft(center=True, normalized=True, length)
    C, F, T = z.shape
    frames = np.fft.irfft(z.transpose(0, 2, 1) * math.sqrt(NFFT), n=NFFT, axis=-1) * WIN
    out_len = NFFT + HOP * (T - 1)
    y = np.zeros((C, out_len))
    wsum = np.zeros(out_len)
    for t in range(T):
        y[:, t * HOP:t * HOP + NFFT] += frames[:, t]
        wsum[t * HOP:t * HOP + NFFT] += WIN ** 2
    y = y[:, NFFT // 2:NFFT // 2 + length]
    w = wsum[NFFT // 2:NFFT // 2 + length]
    return y / np.where(w > 1e-11, w, 1.0)


def spec_in(mix):  # HTDemucs._spec + _magnitude (cac)
    le = math.ceil(mix.shape[-1] / HOP)
    pad = HOP // 2 * 3
    x = reflect_pad(mix, pad, pad + le * HOP - mix.shape[-1])
    z = stft(x)[:, :-1, 2:2 + le]  # (2, 2048, le)
    mag = np.stack([z[0].real, z[0].imag, z[1].real, z[1].imag])  # (4, F, T)
    return mag[None].astype(np.float32)


def spec_out(spec, length):  # HTDemucs._mask (cac) + _ispec
    S = spec.shape[1]
    out = np.zeros((S, 2, length))
    pad = HOP // 2 * 3
    le = HOP * math.ceil(length / HOP) + 2 * pad
    for s in range(S):
        z = spec[0, s, 0::2] + 1j * spec[0, s, 1::2]  # (2, F, T)
        z = np.pad(z, ((0, 0), (0, 1), (2, 2)))
        out[s] = istft(z, le)[:, pad:pad + length]
    return out


def run_segment(sess, chunk):
    mag = spec_in(chunk)
    spec, wave = sess.run(None, {"mix": chunk[None].astype(np.float32), "mag": mag})
    return spec_out(spec, SEG) + wave[0]


def separate(sess, wav, overlap=0.25, progress=None):
    """wav: (2, N) float. Returns (S, 2, N). Matches apply_model(shifts=0, split=True)."""
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std(ddof=1)
    wav = (wav - mean) / (std if std > 1e-8 else 1.0)
    N = wav.shape[-1]
    stride = int((1 - overlap) * SEG)
    weight = np.concatenate([np.arange(1, SEG // 2 + 1), np.arange(SEG - SEG // 2, 0, -1)]).astype(np.float64)
    weight /= weight.max()
    out = np.zeros((4, 2, N))
    wsum = np.zeros(N)
    offsets = list(range(0, N, stride))
    for i, off in enumerate(offsets):
        length = min(N - off, SEG)
        start = off - (SEG - length) // 2  # TensorChunk.padded: centred, real context
        lo, hi = max(0, start), min(N, start + SEG)
        chunk = np.pad(wav[:, lo:hi], ((0, 0), (lo - start, start + SEG - hi)))
        res = run_segment(sess, chunk)
        d = SEG - length  # center_trim
        res = res[..., d // 2:d // 2 + length]
        out[..., off:off + length] += weight[:length] * res
        wsum[off:off + length] += weight[:length]
        if progress:
            progress((i + 1) / len(offsets))
    out /= wsum
    return out * std + mean


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--audio", required=True)
    ap.add_argument("--dump")
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()

    import soundfile as sf
    import torch
    from demucs.apply import apply_model
    from demucs.pretrained import get_model

    wav, sr = sf.read(a.audio, always_2d=True)
    assert sr == SR, "reference test expects 44.1 kHz input"
    wav = wav.T.astype(np.float64)
    if wav.shape[0] == 1:
        wav = np.repeat(wav, 2, 0)

    opts = ort.SessionOptions()
    opts.intra_op_num_threads = a.threads
    sess = ort.InferenceSession(a.onnx, opts, providers=["CPUExecutionProvider"])
    t0 = time.time()
    ours = separate(sess, wav)
    t_ours = time.time() - t0

    model = get_model("htdemucs").eval()
    x = torch.tensor(wav, dtype=torch.float32)
    ref = x.mean(0)
    xn = (x - ref.mean()) / ref.std()
    t0 = time.time()
    with torch.no_grad():
        theirs = apply_model(model, xn[None], shifts=0, split=True, overlap=0.25)[0] * ref.std() + ref.mean()
    t_ref = time.time() - t0
    theirs = theirs.numpy()

    for i, name in enumerate(model.sources):
        err = ours[i] - theirs[i]
        sdr = 10 * np.log10(np.sum(theirs[i] ** 2) / max(np.sum(err ** 2), 1e-20))
        print(f"{name:7s} max|diff|={np.abs(err).max():.2e}  SNR vs PyTorch={sdr:6.1f} dB")
    dur = wav.shape[-1] / SR
    print(f"ONNX pipeline {t_ours:.1f}s, PyTorch {t_ref:.1f}s for {dur:.1f}s audio ({a.threads} threads)")

    if a.dump:
        d = Path(a.dump)
        d.mkdir(parents=True, exist_ok=True)
        wav.astype(np.float32).T.tofile(d / "input_f32_interleaved.raw")
        theirs.astype(np.float32).tofile(d / "expected_SxCxN_f32.raw")
        (d / "info.txt").write_text(f"frames={wav.shape[-1]}\nsources={','.join(model.sources)}\n")


if __name__ == "__main__":
    main()
