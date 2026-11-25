"""Phase separation server (FastAPI).

Run:  uvicorn phase_server.app:app --host 0.0.0.0 --port 8000
Env:
  PHASE_DATA_DIR        working directory for uploads + stem cache (default ./data)
  PHASE_API_TOKEN       if set, clients must send "Authorization: Bearer <token>"
  PHASE_MAX_UPLOAD_MB   upload limit (default 300)
  PHASE_CACHE_TTL_HOURS how long results are kept (default 24)
  PHASE_DEFAULT_MODEL   model used when the client sends none (default htdemucs)
  PHASE_PRELOAD_MODEL   model to load at startup (optional)
"""
from __future__ import annotations

import logging
import os
import secrets
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse

from . import __version__
from .jobs import JobManager
from .separators import registry
from .separators.base import PHASE_STEMS
from .separators.demucs_separator import pick_device, preload

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

DATA_DIR = Path(os.environ.get("PHASE_DATA_DIR", "./data")).resolve()
API_TOKEN = os.environ.get("PHASE_API_TOKEN", "").strip()
MAX_UPLOAD = int(float(os.environ.get("PHASE_MAX_UPLOAD_MB", "300")) * 1024 * 1024)
DEFAULT_MODEL = os.environ.get("PHASE_DEFAULT_MODEL", "htdemucs")
ALLOWED_EXT = {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".mp4", ".wma", ".aif", ".aiff"}

manager = JobManager(DATA_DIR, float(os.environ.get("PHASE_CACHE_TTL_HOURS", "24")))


@asynccontextmanager
async def lifespan(_app: FastAPI):
    manager.start()
    preload(os.environ.get("PHASE_PRELOAD_MODEL"))
    yield
    manager.stop()


app = FastAPI(title="Phase separation server", version=__version__, lifespan=lifespan)


def auth(authorization: str | None = Header(default=None)) -> None:
    if not API_TOKEN:
        return
    expected = f"Bearer {API_TOKEN}"
    if not authorization or not secrets.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="invalid or missing token")


@app.get("/v1/health", dependencies=[Depends(auth)])
def health() -> dict:
    models = [
        {"id": m.info.id, "name": m.info.name, "stems": m.info.stems, "description": m.info.description}
        for m in registry.all_models()
        if m.available()
    ]
    return {
        "status": "ok",
        "version": __version__,
        "device": pick_device(),
        "models": models,
        "default_model": DEFAULT_MODEL,
        "stems": PHASE_STEMS,
    }


@app.post("/v1/jobs", status_code=202, dependencies=[Depends(auth)])
async def create_job(file: UploadFile = File(...), model: str = Form(DEFAULT_MODEL)) -> dict:
    sep = registry.get(model)
    if sep is None or not sep.available():
        raise HTTPException(status_code=400, detail=f"unknown or unavailable model '{model}'")
    ext = Path(file.filename or "").suffix.lower()
    if ext and ext not in ALLOWED_EXT and ext != ".audio":
        raise HTTPException(status_code=415, detail=f"unsupported file type '{ext}'")
    dest = manager.uploads / f"{uuid.uuid4().hex}{ext or '.bin'}"
    size = 0
    try:
        with open(dest, "wb") as out:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > MAX_UPLOAD:
                    raise HTTPException(status_code=413, detail="file too large")
                out.write(chunk)
    except HTTPException:
        dest.unlink(missing_ok=True)
        raise
    if size == 0:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="empty upload")
    job = manager.submit(dest, model)
    body = job.public()
    body["queue_position"] = manager.queue_position(job.id)
    return body


@app.get("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def job_status(job_id: str) -> dict:
    job = manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="no such job")
    body = job.public()
    if job.status == "queued":
        pos = manager.queue_position(job.id)
        body["queue_position"] = pos
        body["stage"] = f"Queued (#{pos})" if pos > 1 else "Queued"
    return body


@app.get("/v1/jobs/{job_id}/stems/{stem}", dependencies=[Depends(auth)])
def get_stem(job_id: str, stem: str):
    job = manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="no such job")
    if job.status != "done":
        raise HTTPException(status_code=409, detail=f"job is {job.status}")
    path = job.stems.get(stem)
    if path is None or not path.exists():
        raise HTTPException(status_code=404, detail=f"no stem '{stem}'")
    return FileResponse(path, media_type="audio/flac", filename=f"{stem}.flac")


@app.delete("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def delete_job(job_id: str) -> dict:
    if not manager.delete(job_id):
        raise HTTPException(status_code=404, detail="no such job")
    return {"deleted": job_id}
