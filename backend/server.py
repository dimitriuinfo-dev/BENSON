import os
from pathlib import Path

from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from motor.motor_asyncio import AsyncIOMotorClient

# BENSON is a native, on-device Expo app: all its intelligence (LLM orchestration, voice, wake word,
# accessibility control) runs on the phone and talks to external APIs (Anthropic/OpenAI) directly
# with the user's own key. This minimal FastAPI service only: (1) satisfies the platform's
# expo+mongo deploy health-check contract, and (2) stores opt-in analytics events in MongoDB
# (replacing the removed Supabase Postgres path).

ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / ".env")

# DB config comes ONLY from the environment (no source-embedded fallbacks) — fail fast if missing
# so a misconfigured deploy is obvious instead of silently pointing at the wrong database.
MONGO_URL = os.environ["MONGO_URL"]
DB_NAME = os.environ["DB_NAME"]

mongo = AsyncIOMotorClient(MONGO_URL)
db = mongo[DB_NAME]

app = FastAPI(title="BENSON backend")

# All routes are prefixed with /api to match the Kubernetes ingress rules.
api_router = APIRouter(prefix="/api")


@api_router.get("/")
async def root():
    return {"message": "BENSON backend online"}


@api_router.get("/health")
async def health():
    return {"status": "ok"}


@api_router.post("/analytics")
async def analytics(payload: dict):
    """Store opt-in analytics events (best-effort — never fails the client)."""
    events = payload.get("events", []) if isinstance(payload, dict) else []
    if events:
        try:
            await db.analytics_events.insert_many(events)
        except Exception:
            pass
    return {"inserted": len(events)}


app.include_router(api_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.environ.get("CORS_ORIGINS", "*").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
