# Phase separation server

FastAPI service that splits songs into **vocals / drums / bass / other** with
[Demucs](https://github.com/facebookresearch/demucs) (Meta AI, MIT). The Phase
Android app uploads a song, polls progress and downloads four FLAC stems.

## Run it

### Docker (simplest)
```bash
cd backend
docker compose up -d --build
```
The first separation downloads the model (~80 MB) into the `phase-models`
volume.

### Python
Requires Python 3.10+ and `ffmpeg` on the PATH.
```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt   # install a CUDA torch build first for NVIDIA GPUs
uvicorn phase_server.app:app --host 0.0.0.0 --port 8000
```

Then in the app: **Settings → AI separation → Server URL** =
`http://<your-computer's-LAN-IP>:8000` → **Save & test**.

Speed (4-minute song, `htdemucs`): ~10–20 s on a modern NVIDIA GPU, ~1–3 min
on a recent 8-core CPU. `htdemucs_ft` is ~4× slower and slightly better.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PHASE_API_TOKEN` | *(none)* | Require `Authorization: Bearer <token>`. **Set this if the server is reachable from the internet.** |
| `PHASE_DATA_DIR` | `./data` | Uploads + stem cache |
| `PHASE_MAX_UPLOAD_MB` | `300` | Upload limit |
| `PHASE_CACHE_TTL_HOURS` | `24` | How long results stay cached |
| `PHASE_DEFAULT_MODEL` | `htdemucs` | Model when the client sends none |
| `PHASE_PRELOAD_MODEL` | *(none)* | Load a model at startup |
| `TORCH_HOME` | torch default | Where model weights are stored |

## API

| Method | Path | |
|---|---|---|
| GET | `/v1/health` | device (`cuda`/`mps`/`cpu`) and available models |
| POST | `/v1/jobs` | multipart `file`, `model` → `202 {id, status, progress, stage}` |
| GET | `/v1/jobs/{id}` | `status` = `queued \| running \| done \| error`, `progress` 0–1, `stage` |
| GET | `/v1/jobs/{id}/stems/{vocals\|drums\|bass\|other}` | FLAC |
| DELETE | `/v1/jobs/{id}` | forget the job (cache is kept until TTL) |

Jobs run one at a time (the model saturates the GPU/CPU). Identical uploads
for the same model are answered from cache.

## Adding a model

Implement `phase_server.separators.base.Separator` (return FLAC paths for
the four stems) and `register()` it in `separators/registry.py`. It will then
appear in `/v1/health` and in the app's model picker.

## Tests
```bash
pip install -r requirements-dev.txt
pytest            # API/job tests (fast)
pytest -m slow    # real htdemucs separation end-to-end (downloads weights)
```

## Privacy
Uploaded audio is deleted after processing; stems are kept only for the
cache TTL. Nothing is sent anywhere else.
