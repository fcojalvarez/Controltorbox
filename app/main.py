"""Controltorbox: gestiona y limpia tu almacenamiento de TorBox."""
from __future__ import annotations

import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import language
from .torbox import DEFAULT_BASE_URL, KINDS, TorboxClient, TorboxError

STATIC_DIR = Path(__file__).parent / "static"


def _load_dotenv(path: Path = Path(".env")) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


class AnalysisCache:
    """Guarda en disco el resultado del análisis con ffprobe."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()
        try:
            self._data: dict = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, ValueError):
            self._data = {}

    def get(self, key: str) -> dict | None:
        return self._data.get(key)

    def set(self, key: str, value: dict) -> None:
        with self._lock:
            self._data[key] = value
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self._data, indent=2), encoding="utf-8")

    def drop(self, key: str) -> None:
        with self._lock:
            if self._data.pop(key, None) is not None:
                self.path.write_text(json.dumps(self._data, indent=2), encoding="utf-8")


# ---------------------------------------------------------------------------
# Modelos
# ---------------------------------------------------------------------------
class SettingsStore:
    """Ajustes guardados desde la interfaz (p.ej. la API key de TorBox)."""

    def __init__(self, path: Path) -> None:
        self.path = path
        try:
            self._data: dict = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, ValueError):
            self._data = {}

    def get(self, key: str, default=None):
        return self._data.get(key, default)

    def set(self, key: str, value) -> None:
        if value is None:
            self._data.pop(key, None)
        else:
            self._data[key] = value
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, indent=2), encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)  # contiene la API key
        except OSError:
            pass
        tmp.replace(self.path)


def mask_key(key: str) -> str:
    if not key:
        return ""
    return ("•" * 8) + key[-4:] if len(key) > 4 else "•" * len(key)


class ItemRef(BaseModel):
    kind: str = Field(pattern="^(torrent|usenet|webdl)$")
    id: int


class ItemsRequest(BaseModel):
    items: list[ItemRef]


class SettingsRequest(BaseModel):
    api_key: str = Field(min_length=1, max_length=512)


# ---------------------------------------------------------------------------
# Lógica
# ---------------------------------------------------------------------------
def _key(kind: str, item_id) -> str:
    return f"{kind}:{item_id}"


def build_item(kind: str, raw: dict, cache: AnalysisCache) -> dict:
    files = raw.get("files") or []
    main = language.pick_main_video(files)
    main_name = (main or {}).get("short_name") or (main or {}).get("name") or ""
    name_guess = language.guess_from_name(raw.get("name") or "", main_name)

    analysis = cache.get(_key(kind, raw.get("id")))
    if analysis and analysis.get("status"):
        audio_status, source = analysis["status"], "ffprobe"
    else:
        audio_status, source = name_guess, "nombre"

    return {
        "kind": kind,
        "id": raw.get("id"),
        "name": raw.get("name") or main_name or f"{kind} {raw.get('id')}",
        "size": raw.get("size") or sum((f.get("size") or 0) for f in files),
        "created_at": raw.get("created_at"),
        "state": raw.get("download_state") or ("completed" if raw.get("download_finished") else ""),
        "files": len(files),
        "has_video": main is not None,
        "main_file": main_name,
        "audio_status": audio_status,
        "audio_source": source,
        "audio_languages": (analysis or {}).get("languages", []),
        "analysis_error": (analysis or {}).get("error"),
    }


def create_app(client_factory=None, data_dir: Path | None = None) -> FastAPI:
    _load_dotenv()
    data_dir = Path(data_dir or os.environ.get("CONTROLTORBOX_DATA_DIR", "data"))
    cache = AnalysisCache(data_dir / "analysis.json")
    settings = SettingsStore(data_dir / "settings.json")

    def current_api_key() -> tuple[str, str]:
        """Devuelve (clave, origen). La guardada en ajustes tiene prioridad."""
        saved = settings.get("api_key") or ""
        if saved:
            return saved, "ajustes"
        env = os.environ.get("TORBOX_API_KEY", "")
        return env, ("env" if env else "")

    def make_client(api_key: str):
        if client_factory is not None:
            return client_factory(api_key)
        return TorboxClient(api_key, os.environ.get("TORBOX_BASE_URL", DEFAULT_BASE_URL))

    app = FastAPI(title="Controltorbox")

    def get_client():
        try:
            client = make_client(current_api_key()[0])
        except TorboxError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        try:
            yield client
        finally:
            client.close()

    def _fetch_all(client) -> list[dict]:
        items, errors = [], []
        for kind in KINDS:
            try:
                for raw in client.list(kind):
                    items.append(build_item(kind, raw, cache))
            except TorboxError as exc:
                errors.append(f"{kind}: {exc}")
        if errors and not items:
            raise HTTPException(status_code=502, detail="; ".join(errors))
        return items

    @app.get("/api/status")
    def status():
        key, source = current_api_key()
        return {
            "api_key_configured": bool(key),
            "api_key_source": source,
            "ffprobe": language.ffprobe_available(),
        }

    @app.get("/api/settings")
    def get_settings():
        key, source = current_api_key()
        return {"api_key_configured": bool(key), "api_key_masked": mask_key(key), "api_key_source": source}

    @app.put("/api/settings")
    def save_settings(req: SettingsRequest):
        key = req.api_key.strip()
        if not key:
            raise HTTPException(status_code=400, detail="La API key no puede estar vacía.")
        # Comprobamos que la clave funciona antes de guardarla.
        try:
            client = make_client(key)
            try:
                client.list("torrent")
            finally:
                client.close()
        except TorboxError as exc:
            raise HTTPException(status_code=400, detail=f"La API key no es válida: {exc}")
        except Exception as exc:  # noqa: BLE001 - errores de red
            raise HTTPException(status_code=502, detail=f"No se pudo contactar con TorBox: {exc}")
        settings.set("api_key", key)
        return get_settings()

    @app.delete("/api/settings/api_key")
    def clear_api_key():
        settings.set("api_key", None)
        return get_settings()

    @app.get("/api/items")
    def list_items(client=Depends(get_client)):
        items = _fetch_all(client)
        items.sort(key=lambda i: i["size"] or 0, reverse=True)
        return {"items": items}

    @app.post("/api/analyze")
    def analyze(req: ItemsRequest, client=Depends(get_client)):
        if not language.ffprobe_available():
            raise HTTPException(
                status_code=400,
                detail="ffprobe no está instalado. Instala ffmpeg para analizar las pistas de audio.",
            )
        wanted = {_key(r.kind, r.id) for r in req.items}
        raws = {}
        for kind in {r.kind for r in req.items}:
            try:
                raw_list = client.list(kind)
            except TorboxError as exc:
                raise HTTPException(status_code=502, detail=str(exc))
            for raw in raw_list:
                if _key(kind, raw.get("id")) in wanted:
                    raws[_key(kind, raw.get("id"))] = (kind, raw)

        def work(key: str) -> dict:
            if key not in raws:
                return {"key": key, "error": "No encontrado en TorBox"}
            kind, raw = raws[key]
            main = language.pick_main_video(raw.get("files") or [])
            if main is None:
                result = {"status": language.UNKNOWN, "languages": [], "error": "Sin archivo de vídeo"}
            else:
                try:
                    url = client.download_link(kind, raw["id"], main["id"])
                    langs = language.probe_audio_languages(url)
                    result = {"status": language.classify_audio_languages(langs), "languages": langs, "error": None}
                except Exception as exc:  # noqa: BLE001 - se informa al usuario
                    return {"key": key, "error": str(exc)[:300]}
            cache.set(key, result)
            return {"key": key, **result}

        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(work, sorted(wanted)))
        return {"results": results}

    @app.post("/api/delete")
    def delete(req: ItemsRequest, client=Depends(get_client)):
        results = []
        for ref in req.items:
            try:
                client.delete(ref.kind, ref.id)
                cache.drop(_key(ref.kind, ref.id))
                results.append({"kind": ref.kind, "id": ref.id, "ok": True})
            except TorboxError as exc:
                results.append({"kind": ref.kind, "id": ref.id, "ok": False, "error": str(exc)})
        return {"results": results}

    @app.get("/")
    def index():
        return FileResponse(STATIC_DIR / "index.html")

    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


app = create_app()
