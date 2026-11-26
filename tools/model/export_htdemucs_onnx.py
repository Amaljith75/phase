"""Export Hybrid Transformer Demucs (htdemucs) for on-device inference.

ONNX has no inverse-STFT operator, and STFT export is fragile, so the model
is split the same way on every platform:

    app:   waveform --STFT--> complex spectrogram (as 4 real channels)
    ONNX:  (waveform, spectrogram) --> (spectrogram estimate, waveform estimate)
    app:   spectrogram estimate --iSTFT--> + waveform estimate = 4 stems

The ONNX graph is everything inside HTDemucs.forward between `_magnitude`
and `_mask` (both branches, the cross-transformer, normalisation). Input
length is fixed to the training segment (7.8 s @ 44.1 kHz = 343980 samples);
the app reproduces demucs.apply's overlap-add chunking around it.

Usage:
    python export_htdemucs_onnx.py --out htdemucs.onnx [--fp16]
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch
from torch import nn
from demucs.pretrained import get_model
from demucs.htdemucs import HTDemucs
from einops import rearrange


class HTDemucsCore(nn.Module):
    def __init__(self, m: HTDemucs):
        super().__init__()
        self.m = m

    def forward(self, mix: torch.Tensor, mag: torch.Tensor):
        m = self.m
        x = mag
        B, C, Fq, T = x.shape
        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)
        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)

        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)
        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = rearrange(x, "b c f t-> b c (f t)")
                x = m.channel_upsampler(x)
                x = rearrange(x, "b c (f t)-> b c f t", f=f)
                xt = m.channel_upsampler_t(xt)
            x, xt = m.crosstransformer(x, xt)
            if m.bottom_channels:
                x = rearrange(x, "b c f t-> b c (f t)")
                x = m.channel_downsampler(x)
                x = rearrange(x, "b c (f t)-> b c f t", f=f)
                xt = m.channel_downsampler_t(xt)
        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)
        S = len(m.sources)
        x = x.view(B, S, -1, Fq, T)
        x = x * std[:, None] + mean[:, None]
        xt = xt.view(B, S, -1, mix.shape[-1])
        xt = xt * stdt[:, None] + meant[:, None]
        return x, xt


class BlockwiseMHA(nn.Module):
    """Drop-in for nn.MultiheadAttention (batch_first, no masks) that computes
    softmax(QK^T)V in query blocks. Mathematically identical, but peak memory
    is one block of scores instead of the full 8 x 2688 x 2688 matrix
    (~230 MB per layer) — essential on phones."""

    def __init__(self, mha: nn.MultiheadAttention, block: int = 512):
        super().__init__()
        assert mha.batch_first and mha._qkv_same_embed_dim
        self.mha = mha
        self.block = block

    def forward(self, query, key, value, attn_mask=None, key_padding_mask=None, need_weights=False, **_):
        assert attn_mask is None and key_padding_mask is None
        m = self.mha
        E, H = m.embed_dim, m.num_heads
        d = E // H
        W, b = m.in_proj_weight, m.in_proj_bias
        q = torch.nn.functional.linear(query, W[:E], b[:E])
        k = torch.nn.functional.linear(key, W[E:2 * E], b[E:2 * E])
        v = torch.nn.functional.linear(value, W[2 * E:], b[2 * E:])
        B, Lq, _ = q.shape
        Lk = k.shape[1]
        q = q.view(B, Lq, H, d).transpose(1, 2) * (d ** -0.5)
        kt = k.view(B, Lk, H, d).permute(0, 2, 3, 1)
        v = v.view(B, Lk, H, d).transpose(1, 2)
        outs = []
        for s in range(0, Lq, self.block):
            p = torch.softmax(q[:, :, s:s + self.block] @ kt, dim=-1)
            outs.append(p @ v)
        o = torch.cat(outs, dim=2).transpose(1, 2).reshape(B, Lq, E)
        return m.out_proj(o), None


def use_blockwise_attention(module: nn.Module, block: int = 512) -> int:
    n = 0
    for name, child in list(module.named_children()):
        if isinstance(child, nn.MultiheadAttention):
            setattr(module, name, BlockwiseMHA(child, block))
            n += 1
        else:
            n += use_blockwise_attention(child, block)
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="htdemucs")
    ap.add_argument("--out", default="htdemucs.onnx")
    ap.add_argument("--fp16", action="store_true", help="store weights as float16 (half the size)")
    ap.add_argument("--attn-block", type=int, default=512, help="attention query block size (memory vs speed)")
    args = ap.parse_args()

    # The fused attention fast path has no ONNX equivalent; trace the plain ops.
    torch.backends.mha.set_fastpath_enabled(False)
    model = get_model(args.model)
    if not isinstance(model, HTDemucs):  # bag of models -> take the single model
        model = model.models[0]
    model.eval()
    length = int(model.segment * model.samplerate)
    hl = model.hop_length
    frames = int(-(-length // hl))
    core = HTDemucsCore(model).eval()
    mix = torch.randn(1, 2, length)
    mag = torch.randn(1, 4, model.nfft // 2, frames)
    with torch.no_grad():
        ref = core(mix, mag)
    replaced = use_blockwise_attention(core, args.attn_block)
    with torch.no_grad():
        new = core(mix, mag)
    err = max((a - b).abs().max().item() for a, b in zip(ref, new))
    print(f"blockwise attention: {replaced} layers, max deviation {err:.2e}")
    assert err < 1e-3, "blockwise attention changed the output"
    with torch.no_grad():
        torch.onnx.export(
            core, (mix, mag), args.out, opset_version=17, dynamo=False,
            input_names=["mix", "mag"], output_names=["spec", "wave"],
            do_constant_folding=True,
        )
    if args.fp16:
        import onnx
        from onnxruntime.transformers.float16 import convert_float_to_float16
        mdl = onnx.load(args.out)
        mdl = convert_float_to_float16(mdl, keep_io_types=True)
        onnx.save(mdl, args.out)
    data = Path(args.out).read_bytes()
    meta = {
        "model": args.model,
        "sources": list(model.sources),
        "samplerate": model.samplerate,
        "segment_samples": length,
        "nfft": model.nfft,
        "hop": hl,
        "frames": frames,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    Path(args.out).with_suffix(".json").write_text(json.dumps(meta, indent=2))
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
