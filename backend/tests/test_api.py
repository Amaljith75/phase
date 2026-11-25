"""API contract tests.

The fast tests swap in a test double for the separator so the HTTP/job
plumbing can be verified without model weights. The real Demucs path is
covered by test_demucs.py (marked slow; needs network on first run).
"""
from __future__ import annotations

import io
import importlib
import time
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

from phase_server.separators import registry
from phase_server.separators.base import PHASE_STEMS, ModelInfo, Separator


class CopySeparatorDouble(Separator):
    """Test double: writes the input as each stem (NOT a real separator)."""

    info = ModelInfo(id="test-double", name="Test double")

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls = 0

    def available(self) -> bool:
        return True

    def separate(self, audio_path: Path, out_dir: Path, progress):
        self.calls += 1
        if self.fail:
            raise RuntimeError("boom")
        data, sr = sf.read(str(audio_path))
        out_dir.mkdir(parents=True, exist_ok=True)
        res = {}
        for i, s in enumerate(PHASE_STEMS):
            progress((i + 1) / 4, f"stem {s}")
            p = out_dir / f"{s}.flac"
            sf.write(str(p), data, sr, format="FLAC")
            res[s] = p
        return res


def wav_bytes(seconds=1.0, sr=44100) -> bytes:
    t = np.arange(int(seconds * sr)) / sr
    x = 0.2 * np.sin(2 * np.pi * 440 * t)
    buf = io.BytesIO()
    sf.write(buf, np.stack([x, x], 1), sr, format="WAV")
    return buf.getvalue()


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("PHASE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("PHASE_API_TOKEN", "secret")
    import phase_server.app as appmod

    importlib.reload(appmod)
    double = CopySeparatorDouble()
    registry.register(double)
    with TestClient(appmod.app) as c:
        c.headers["Authorization"] = "Bearer secret"
        c.double = double  # type: ignore[attr-defined]
        yield c
    registry._registry.pop("test-double", None)


def wait_done(c, job_id, timeout=20):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st = c.get(f"/v1/jobs/{job_id}").json()
        if st["status"] in ("done", "error"):
            return st
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def test_auth_required(client):
    r = client.get("/v1/health", headers={"Authorization": "Bearer wrong"})
    assert r.status_code == 401


def test_health_lists_models(client):
    body = client.get("/v1/health").json()
    assert body["status"] == "ok"
    assert "test-double" in [m["id"] for m in body["models"]]
    assert body["stems"] == PHASE_STEMS


def test_full_job_lifecycle_and_cache(client):
    r = client.post("/v1/jobs", files={"file": ("song.wav", wav_bytes(), "audio/wav")}, data={"model": "test-double"})
    assert r.status_code == 202, r.text
    st = wait_done(client, r.json()["id"])
    assert st["status"] == "done" and st["progress"] == 1.0
    assert sorted(st["stems"]) == sorted(PHASE_STEMS)
    assert st["sample_rate"] == 44100
    for s in PHASE_STEMS:
        d = client.get(f"/v1/jobs/{st['id']}/stems/{s}")
        assert d.status_code == 200 and d.headers["content-type"] == "audio/flac"
        data, sr = sf.read(io.BytesIO(d.content))
        assert sr == 44100 and len(data) == 44100
    # Same file again: served from cache without re-running the model.
    calls = client.double.calls
    r2 = client.post("/v1/jobs", files={"file": ("again.wav", wav_bytes(), "audio/wav")}, data={"model": "test-double"})
    assert r2.json()["status"] == "done" and r2.json()["stage"] == "Cached"
    assert client.double.calls == calls
    assert client.delete(f"/v1/jobs/{st['id']}").status_code == 200
    assert client.get(f"/v1/jobs/{st['id']}").status_code == 404


def test_rejects_bad_input(client):
    assert client.post("/v1/jobs", files={"file": ("x.wav", b"", "audio/wav")}, data={"model": "test-double"}).status_code == 400
    assert client.post("/v1/jobs", files={"file": ("x.exe", b"abc", "application/x")}, data={"model": "test-double"}).status_code == 415
    assert client.post("/v1/jobs", files={"file": ("x.wav", b"abc", "audio/wav")}, data={"model": "nope"}).status_code == 400


def test_failure_is_reported(client):
    registry.register(type("Failing", (CopySeparatorDouble,), {"info": ModelInfo(id="test-fail", name="f")})(fail=True))
    try:
        r = client.post("/v1/jobs", files={"file": ("s.wav", wav_bytes(), "audio/wav")}, data={"model": "test-fail"})
        st = wait_done(client, r.json()["id"])
        assert st["status"] == "error" and "boom" in st["error"]
        assert client.get(f"/v1/jobs/{st['id']}/stems/vocals").status_code == 409
    finally:
        registry._registry.pop("test-fail", None)
