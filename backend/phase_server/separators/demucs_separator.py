"""Demucs (Meta AI) source separation: Hybrid Transformer Demucs and friends.

Models are downloaded by Demucs on first use (to TORCH_HOME) and kept in
memory between jobs. Inference runs on CUDA, Apple MPS or CPU.
"""
from __future__ import annotations

import logging
import threading
import types
from pathlib import Path
from typing import Dict, Optional

from .base import PHASE_STEMS, ModelInfo, ProgressFn, Separator

log = logging.getLogger(__name__)

_model_cache: Dict[str, object] = {}
_cache_lock = threading.Lock()
_progress_lock = threading.Lock()


def pick_device() -> str:
    try:
        import torch

        if torch.cuda.is_available():
            return "cuda"
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return "mps"
    except ImportError:
        pass
    return "cpu"


class _TqdmShim:
    """Stands in for ``tqdm`` inside demucs.apply to observe chunk progress.

    Demucs evaluates chunks lazily while iterating its tqdm-wrapped list, so
    counting iterations gives true inference progress. A bag of models (e.g.
    htdemucs_ft) runs several passes; each pass is one ``tqdm`` call.
    """

    def __init__(self, passes: int, report: ProgressFn):
        self.passes = max(1, passes)
        self.done_passes = 0
        self.report = report

    def tqdm(self, iterable, **_kwargs):
        items = list(iterable)
        total = max(1, len(items))
        pass_idx = self.done_passes
        for i, item in enumerate(items):
            frac = (pass_idx + i / total) / self.passes
            stage = "Separating" if self.passes == 1 else f"Separating (pass {pass_idx + 1}/{self.passes})"
            self.report(frac, stage)
            yield item
        self.done_passes += 1
        self.report(self.done_passes / self.passes, "Separating")


class DemucsSeparator(Separator):
    def __init__(self, model_id: str, name: str, description: str = "", shifts: int = 1, overlap: float = 0.25):
        self.info = ModelInfo(id=model_id, name=name, description=description)
        self.shifts = shifts
        self.overlap = overlap

    def available(self) -> bool:
        try:
            import demucs  # noqa: F401
            import torch  # noqa: F401

            return True
        except ImportError:
            return False

    def _model(self, device: str):
        with _cache_lock:
            m = _model_cache.get(self.info.id)
            if m is None:
                from demucs.pretrained import get_model

                log.info("loading demucs model %s", self.info.id)
                m = get_model(self.info.id)
                m.eval()
                _model_cache[self.info.id] = m
            return m.to(device)

    def separate(self, audio_path: Path, out_dir: Path, progress: ProgressFn) -> Dict[str, Path]:
        import numpy as np
        import soundfile as sf
        import torch
        import demucs.apply as dapply
        from demucs.apply import BagOfModels, apply_model
        from demucs.audio import AudioFile

        device = pick_device()
        progress(0.0, "Loading model")
        model = self._model(device)
        progress(0.0, "Decoding audio")
        # AudioFile shells out to ffmpeg: handles MP3/WAV/FLAC/M4A/AAC/OGG.
        wav = AudioFile(audio_path).read(streams=0, samplerate=model.samplerate, channels=model.audio_channels)
        if wav.numel() == 0:
            raise ValueError("decoded audio is empty")
        ref = wav.mean(0)
        mean, std = ref.mean(), ref.std()
        if float(std) < 1e-8:
            std = torch.tensor(1.0)
        wav = (wav - mean) / std

        passes = (len(model.models) if isinstance(model, BagOfModels) else 1) * max(1, self.shifts)
        shim = _TqdmShim(passes, lambda f, s: progress(min(0.97, f * 0.97), s))
        with _progress_lock, torch.no_grad():
            original = dapply.tqdm
            dapply.tqdm = types.SimpleNamespace(tqdm=shim.tqdm)
            try:
                sources = apply_model(
                    model, wav[None], device=device, shifts=self.shifts, split=True,
                    overlap=self.overlap, progress=True, num_workers=0,
                )[0]
            finally:
                dapply.tqdm = original
        sources = sources * std + mean

        progress(0.97, "Writing stems")
        out_dir.mkdir(parents=True, exist_ok=True)
        result: Dict[str, Path] = {}
        for name, src in zip(model.sources, sources):
            if name not in PHASE_STEMS:
                continue
            data = src.cpu().numpy().T  # (T, C)
            data = np.clip(data, -1.0, 1.0)
            path = out_dir / f"{name}.flac"
            sf.write(str(path), data, model.samplerate, subtype="PCM_16", format="FLAC")
            result[name] = path
        missing = [s for s in PHASE_STEMS if s not in result]
        if missing:
            raise RuntimeError(f"model {self.info.id} did not produce stems: {missing}")
        if device == "cuda":
            torch.cuda.empty_cache()
        progress(1.0, "Done")
        return result


def default_demucs_models() -> list[DemucsSeparator]:
    return [
        DemucsSeparator("htdemucs", "Hybrid Transformer Demucs", "Default. Best speed/quality balance."),
        DemucsSeparator("htdemucs_ft", "HT Demucs fine-tuned", "Highest quality; ~4x slower (bag of 4 models)."),
        DemucsSeparator("hdemucs_mmi", "Hybrid Demucs v3", "Previous generation, lighter."),
        DemucsSeparator("mdx_extra", "MDX extra", "MDX challenge model trained with extra data."),
    ]


def preload(model_id: Optional[str]) -> None:
    """Optionally warm a model at startup so the first job starts faster."""
    if not model_id:
        return
    for m in default_demucs_models():
        if m.info.id == model_id and m.available():
            m._model(pick_device())
