"""Job queue, result cache and housekeeping.

Jobs run one at a time on a worker thread: separation saturates a GPU (or
all CPU cores), so parallel jobs would only thrash memory. Results are cached
by (model, sha256 of the uploaded file) so re-submitting a song is instant.
"""
from __future__ import annotations

import hashlib
import logging
import queue
import shutil
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional

from .separators import registry
from .separators.base import PHASE_STEMS

log = logging.getLogger(__name__)


@dataclass
class Job:
    id: str
    model: str
    file_hash: str
    input_path: Path
    status: str = "queued"  # queued | running | done | error
    progress: float = 0.0
    stage: str = "Queued"
    error: Optional[str] = None
    created_at: float = field(default_factory=time.time)
    finished_at: Optional[float] = None
    stems: Dict[str, Path] = field(default_factory=dict)
    sample_rate: Optional[int] = None

    def public(self) -> dict:
        return {
            "id": self.id,
            "model": self.model,
            "status": self.status,
            "progress": round(self.progress, 4),
            "stage": self.stage,
            "error": self.error,
            "stems": sorted(self.stems.keys()),
            "sample_rate": self.sample_rate,
            "created_at": self.created_at,
            "finished_at": self.finished_at,
        }


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class JobManager:
    def __init__(self, data_dir: Path, cache_ttl_hours: float = 24.0):
        self.data_dir = data_dir
        self.uploads = data_dir / "uploads"
        self.cache = data_dir / "cache"
        self.uploads.mkdir(parents=True, exist_ok=True)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.cache_ttl = cache_ttl_hours * 3600
        self.jobs: Dict[str, Job] = {}
        self.lock = threading.Lock()
        self.queue: "queue.Queue[str]" = queue.Queue()
        self._stop = threading.Event()
        self.worker = threading.Thread(target=self._run, name="separation-worker", daemon=True)
        self.janitor = threading.Thread(target=self._housekeeping, name="janitor", daemon=True)

    def start(self) -> None:
        self.worker.start()
        self.janitor.start()

    def stop(self) -> None:
        self._stop.set()
        self.queue.put("")

    # ------------------------------------------------------------- API
    def submit(self, upload_path: Path, model: str) -> Job:
        file_hash = sha256_file(upload_path)
        job = Job(id=uuid.uuid4().hex, model=model, file_hash=file_hash, input_path=upload_path)
        cached = self._cached(model, file_hash)
        if cached:
            job.stems = cached
            job.status, job.progress, job.stage = "done", 1.0, "Cached"
            job.finished_at = time.time()
            job.sample_rate = self._rate(cached)
            upload_path.unlink(missing_ok=True)
        with self.lock:
            self.jobs[job.id] = job
        if job.status == "queued":
            self.queue.put(job.id)
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self.lock:
            return self.jobs.get(job_id)

    def delete(self, job_id: str) -> bool:
        with self.lock:
            job = self.jobs.pop(job_id, None)
        if job:
            job.input_path.unlink(missing_ok=True)
        return job is not None

    def queue_position(self, job_id: str) -> int:
        with self.lock:
            waiting = [j for j in self.jobs.values() if j.status == "queued"]
        waiting.sort(key=lambda j: j.created_at)
        for i, j in enumerate(waiting):
            if j.id == job_id:
                return i + 1
        return 0

    # ---------------------------------------------------------- worker
    def _cache_dir(self, model: str, file_hash: str) -> Path:
        return self.cache / model / file_hash

    def _cached(self, model: str, file_hash: str) -> Optional[Dict[str, Path]]:
        d = self._cache_dir(model, file_hash)
        stems = {s: d / f"{s}.flac" for s in PHASE_STEMS}
        if all(p.exists() and p.stat().st_size > 0 for p in stems.values()):
            d.touch()
            return stems
        return None

    @staticmethod
    def _rate(stems: Dict[str, Path]) -> Optional[int]:
        try:
            import soundfile as sf

            return int(sf.info(str(next(iter(stems.values())))).samplerate)
        except Exception:
            return None

    def _run(self) -> None:
        while not self._stop.is_set():
            job_id = self.queue.get()
            if not job_id:
                continue
            job = self.get(job_id)
            if job is None:
                continue  # deleted while queued
            self._process(job)

    def _process(self, job: Job) -> None:
        sep = registry.get(job.model)
        job.status, job.stage, job.progress = "running", "Starting", 0.0
        tmp_dir = self.cache / "_tmp" / job.id
        try:
            if sep is None or not sep.available():
                raise RuntimeError(f"model '{job.model}' is not available on this server")

            def report(frac: float, stage: str) -> None:
                job.progress = max(job.progress, min(1.0, float(frac)))
                job.stage = stage

            t0 = time.time()
            stems = sep.separate(job.input_path, tmp_dir, report)
            final = self._cache_dir(job.model, job.file_hash)
            if final.exists():
                shutil.rmtree(final)
            final.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(tmp_dir), str(final))
            job.stems = {name: final / p.name for name, p in stems.items()}
            job.sample_rate = self._rate(job.stems)
            job.status, job.progress, job.stage = "done", 1.0, "Done"
            log.info("job %s (%s) done in %.1fs", job.id, job.model, time.time() - t0)
        except Exception as e:  # noqa: BLE001 - report any failure to the client
            log.exception("job %s failed", job.id)
            job.status, job.error, job.stage = "error", str(e) or e.__class__.__name__, "Failed"
            shutil.rmtree(tmp_dir, ignore_errors=True)
        finally:
            job.finished_at = time.time()
            job.input_path.unlink(missing_ok=True)

    def _housekeeping(self) -> None:
        while not self._stop.wait(600):
            now = time.time()
            with self.lock:
                stale = [k for k, j in self.jobs.items() if j.finished_at and now - j.finished_at > self.cache_ttl]
                for k in stale:
                    self.jobs.pop(k, None)
            for model_dir in self.cache.iterdir():
                if model_dir.name == "_tmp" or not model_dir.is_dir():
                    continue
                for entry in model_dir.iterdir():
                    try:
                        if now - entry.stat().st_mtime > self.cache_ttl:
                            shutil.rmtree(entry, ignore_errors=True)
                    except FileNotFoundError:
                        pass
