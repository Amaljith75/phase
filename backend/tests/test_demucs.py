"""End-to-end test of the real Demucs separator (downloads ~80 MB of weights
on first run). Run with:  pytest -m slow
"""
from __future__ import annotations

import numpy as np
import pytest
import soundfile as sf

from phase_server.separators.base import PHASE_STEMS
from phase_server.separators.demucs_separator import DemucsSeparator


def band_energy(x: np.ndarray, sr: int, lo: float, hi: float) -> float:
    spec = np.abs(np.fft.rfft(x.mean(axis=1))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / sr)
    return float(spec[(f >= lo) & (f < hi)].sum())


@pytest.mark.slow
def test_htdemucs_separates_real_audio(tmp_path):
    sr = 44100
    t = np.arange(sr * 8) / sr
    # Bass line (low saw-ish tone) + kick/hat pattern: the model should route
    # the low tone to "bass" and the transients to "drums".
    bass = 0.25 * np.sign(np.sin(2 * np.pi * 55 * t)) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.5 * t))
    drums = np.zeros_like(t)
    for k in range(0, len(t), sr // 4):
        n = min(4000, len(t) - k)
        drums[k:k + n] += 0.6 * np.exp(-np.arange(n) / 600) * np.sin(2 * np.pi * 60 * np.arange(n) / sr * np.exp(-np.arange(n) / 2000))
    mix = np.stack([bass + drums, bass + drums], 1) * 0.8
    src = tmp_path / "mix.wav"
    sf.write(str(src), mix, sr)

    seen = []
    out = DemucsSeparator("htdemucs", "HT Demucs").separate(src, tmp_path / "out", lambda f, s: seen.append(f))
    assert set(PHASE_STEMS) <= set(out)
    assert seen and seen[-1] == 1.0 and all(b >= a - 1e-9 for a, b in zip(seen, seen[1:]))

    stems = {k: sf.read(str(v))[0] for k, v in out.items()}
    for k, v in stems.items():
        assert v.shape == mix.shape, k
    recon = sum(stems.values())
    resid = np.sqrt(np.mean((recon - mix) ** 2)) / np.sqrt(np.mean(mix ** 2))
    assert resid < 0.25, f"stems do not sum back to the mix (rel. err {resid:.2f})"
    total_low = sum(band_energy(v, sr, 30, 120) for v in stems.values())
    assert band_energy(stems["bass"], sr, 30, 120) + band_energy(stems["drums"], sr, 30, 120) > 0.8 * total_low
    assert band_energy(stems["vocals"], sr, 30, 120) < 0.1 * total_low
