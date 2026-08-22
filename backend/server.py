import os
from pathlib import Path

from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware

# BENSON is a native, on-device Expo app: all its intelligence (LLM orchestration, voice, wake word,
# accessibility control) runs on the phone and talks to external APIs directly. It has no server-side
# business logic. This minimal FastAPI service exists only to satisfy the platform's expo+mongo
# deploy contract (a backend with a health endpoint must start and pass the health check).

ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / ".env")

app = FastAPI(title="BENSON backend")

# All routes are prefixed with /api to match the Kubernetes ingress rules.
api_router = APIRouter(prefix="/api")


@api_router.get("/")
async def root():
    return {"message": "BENSON backend online"}


@api_router.get("/health")
async def health():
    return {"status": "ok"}


app.include_router(api_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.environ.get("CORS_ORIGINS", "*").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
