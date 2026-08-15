# Small Python image. Slim not alpine — sentence-transformers has C
# extensions that don't build on alpine's musl libc.
FROM python:3.11-slim

WORKDIR /app

# System deps for sentence-transformers (needs libgomp for OpenMP threads)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
 && rm -rf /var/lib/apt/lists/*

# Install Python deps first for better layer caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the model at build time so container startup is fast.
# Without this, the first request would pay ~60s of model download.
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"

COPY main.py .

EXPOSE 8000

# Uvicorn workers=1: this is a CPU-bound model, more workers doesn't
# help. If we hit real load, we'd scale by adding container replicas.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]