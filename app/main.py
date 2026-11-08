"""Controltorbox: gestiona y limpia tu almacenamiento de TorBox."""
from __future__ import annotations

import json
import logging
import os
import platform
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import language
from .torbox import DEFAULT_BASE_URL, KINDS, TorboxClient, TorboxError

STATIC_DIR = Path(__file__).parent / "static"
PROJECT_DIR = Path(__file__).resolve().parent.parent
log = logging.getLogger("controltorbox")


class StorageError(RuntimeError):
    pass


def _writable_dir(path: Path) -> bool:
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe = path / ".write_test"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


def resolve_data_dir(explicit: Path | str | None = None) -> Path:
    """Carpeta donde se guardan ajustes y caché.

    Por defecto es <proyecto>/data (no depende de desde dónde se lance la app).
    Si no se puede escribir ahí, se usa ~/.controltorbox.
    """
    if explicit:
        return Path(explicit)
    env = os.environ.get("CONTROLTORBOX_DATA_DIR")
    if env:
        return Path(env)
    default = PROJECT_DIR / "data"
    if _writable_dir(default):
        return default
    fallback = Path.home() / ".controltorbox"
    log.warning("No se puede escribir en %s; usando %s", default, fallback)
    return fallback


def _load_dotenv(path: Path | None = None) -> None:
    if path is None:
        path = Path(".env") if Path(".env").exists() else PROJECT_DIR / ".env"
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
        except (OSError, ValueError):
            self._data = {}

    def get(self, key: str) -> dict | None:
        return self._data.get(key)

    def _save(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self._data, indent=2), encoding="utf-8")
        except OSError as exc:  # la caché es opcional: no rompemos nada
            log.warning("No se pudo guardar la caché de análisis: %s", exc)

    def set(self, key: str, value: dict) -> None:
        with self._lock:
            self._data[key] = value
            self._save()

    def drop(self, key: str) -> None:
        with self._lock:
            if self._data.pop(key, None) is not None:
                self._save()


# ---------------------------------------------------------------------------
# Modelos
# ---------------------------------------------------------------------------
class SettingsStore:
    """Ajustes guardados desde la interfaz (p.ej. la API key de TorBox)."""

    def __init__(self, path: Path) -> None:
        self.path = path
        try:
            self._data: dict = json.loads(path.read_text(encoding="utf-8-sig"))
            if not isinstance(self._data, dict):
                self._data = {}
        except (OSError, ValueError):
            self._data = {}

    def get(self, key: str, default=None):
        return self._data.get(key, default)

    def set(self, key: str, value) -> None:
        if value is None:
            self._data.pop(key, None)
        else:
            self._data[key] = value
        content = json.dumps(self._data, indent=2)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            # Escritura directa (sin renombrar un temporal): en Windows,
            # OneDrive o el antivirus pueden bloquear el renombrado.
            self.path.write_text(content, encoding="utf-8")
        except OSError as exc:
            raise StorageError(
                f"No se pudo guardar en {self.path}: {exc.strerror or exc}. "
                "Comprueba los permisos de la carpeta o define CONTROLTORBOX_DATA_DIR."
            ) from exc
        try:
            os.chmod(self.path, 0o600)  # contiene la API key
        except OSError:
            pass


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


class FfprobeRequest(BaseModel):
    path: str = Field(default="", max_length=1024)


INSTALL_HELP = {
    "Windows": "Abre PowerShell y ejecuta: winget install Gyan.FFmpeg  (o descarga ffmpeg de gyan.dev y extráelo en C:\\ffmpeg)",
    "Darwin": "En Terminal: brew install ffmpeg",
    "Linux": "En la terminal: sudo apt install ffmpeg  (o el gestor de paquetes de tu distribución)",
}


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
    data_dir = resolve_data_dir(data_dir)
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

    @app.exception_handler(StorageError)
    async def storage_error(request, exc: StorageError):
        log.error("%s", exc)
        return JSONResponse(status_code=500, content={"detail": str(exc)})

    @app.exception_handler(Exception)
    async def unexpected_error(request, exc: Exception):
        # Siempre devolvemos JSON con la causa para que la interfaz la muestre.
        log.exception("Error inesperado en %s", request.url.path)
        return JSONResponse(
            status_code=500,
            content={"detail": f"Error interno: {type(exc).__name__}: {exc}"},
        )

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
            "ffprobe": language.ffprobe_available(settings.get("ffprobe_path")),
        }

    def ffprobe_info() -> dict:
        custom = settings.get("ffprobe_path") or ""
        found = language.find_ffprobe(custom)
        return {
            "ffprobe_found": found or "",
            "ffprobe_custom": custom,
            "install_help": INSTALL_HELP.get(platform.system(), INSTALL_HELP["Linux"]),
        }

    @app.get("/api/settings")
    def get_settings():
        key, source = current_api_key()
        return {
            "api_key_configured": bool(key),
            "api_key_masked": mask_key(key),
            "api_key_source": source,
            **ffprobe_info(),
        }

    @app.put("/api/settings/ffprobe")
    def save_ffprobe(req: FfprobeRequest):
        raw = req.path.strip()
        if not raw:
            settings.set("ffprobe_path", None)  # volver a la detección automática
            return get_settings()
        path = language.normalize_ffprobe_path(raw)
        if not path.is_file():
            raise HTTPException(status_code=400, detail=f"No existe el archivo {path}")
        try:
            language.check_ffprobe(path)
        except RuntimeError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        settings.set("ffprobe_path", str(path))
        return get_settings()

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
        ffprobe = language.find_ffprobe(settings.get("ffprobe_path"))
        if not ffprobe:
            raise HTTPException(
                status_code=400,
                detail="No se encuentra ffprobe. Instala ffmpeg o indica su ruta en ⚙ Ajustes.",
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
                    langs = language.probe_audio_languages(url, ffprobe)
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
