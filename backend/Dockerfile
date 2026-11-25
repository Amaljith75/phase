# Phase separation server — CPU image. For NVIDIA GPUs, swap the base for a
# CUDA-enabled PyTorch image (e.g. pytorch/pytorch:*-cuda*-runtime) and run
# with `--gpus all`.
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch torchaudio \
    && pip install --no-cache-dir -r requirements.txt

COPY phase_server ./phase_server
ENV PHASE_DATA_DIR=/data TORCH_HOME=/models PHASE_PRELOAD_MODEL=htdemucs
VOLUME ["/data", "/models"]
EXPOSE 8000
CMD ["uvicorn", "phase_server.app:app", "--host", "0.0.0.0", "--port", "8000"]
