"""
Pager embedding service — thin FastAPI wrapper around sentence-transformers.

Why a Python sidecar rather than Java-native?
- Java's ONNX story via DJL is workable but adds ~500 MB of Maven
  dependencies and heavier startup. A ~90 MB Python container is smaller.
- Sentence Transformers is the reference implementation. Any embedding
  quality issues can be diagnosed against the same library used in
  research papers and product benchmarks.
- If we ever swap to a hosted embedding API (OpenAI, Voyage, Cohere),
  only this service changes. Java's EmbeddingClient interface stays put.

Model choice: all-MiniLM-L6-v2 (384-dim). Small, fast on CPU, good
enough for English technical text. If we hit a quality wall, upgrade
to bge-small-en-v1.5 (same interface, better recall) or a hosted API.

Endpoints:
- POST /embed  — body: {"texts": ["a", "b"]}  → {"embeddings": [[...], [...]]}
- GET  /health — {"status": "up", "model": "..."}
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from typing import List

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIM = 384

app = FastAPI(title="pager-embedder", version="1.0")

# Model loads on first import. In production, we'd warm it during
# container startup so the first request doesn't pay the ~60s init cost.
print(f"[embedder] loading model: {MODEL_NAME}")
model = SentenceTransformer(MODEL_NAME)
print(f"[embedder] model loaded, dim={model.get_sentence_embedding_dimension()}")


class EmbedRequest(BaseModel):
    texts: List[str]


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    model: str
    dim: int


@app.get("/health")
def health():
    return {
        "status": "up",
        "model": MODEL_NAME,
        "dim": EMBEDDING_DIM,
    }


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        raise HTTPException(status_code=400, detail="texts must not be empty")
    if len(req.texts) > 128:
        raise HTTPException(status_code=400, detail="max 128 texts per request")

    # normalize_embeddings=True gives us L2-normalized vectors so cosine
    # similarity is equivalent to dot product — pgvector's <=> operator
    # is cosine distance which expects normalized vectors for correctness.
    vectors = model.encode(
        req.texts,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return EmbedResponse(
        embeddings=vectors.tolist(),
        model=MODEL_NAME,
        dim=EMBEDDING_DIM,
    )