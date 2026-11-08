"""Controltorbox: gestiona y limpia tu almacenamiento de TorBox."""
from __future__ import annotations

import hashlib
import json
import logging
import os
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import language
from .duplicates import find_duplicates
from .torbox import DEFAULT_BASE_URL, KINDS, TorboxClient, TorboxError

STATIC_DIR = Path(__file__).parent / "static"
PROJECT_DIR = Path(__file__).resolve().parent.parent
log = logging.getLogger("controltorbox")


def static_version() -> str:
    """Huella de los archivos de la interfaz: cambia en cuanto cambia alguno."""
    h = hashlib.sha1()
    for path in sorted(STATIC_DIR.iterdir()):
        if path.is_file():
            h.update(path.name.encode())
            h.update(path.read_bytes())
    return h.hexdigest()[:10]


def app_version() -> str:
    """Commit de git en uso (si la app se descargó con git) para mostrarlo en pantalla."""
    git = PROJECT_DIR / ".git"
    try:
        head = (git / "HEAD").read_text(encoding="utf-8").strip()
        if head.startswith("ref: "):
            ref = head[5:]
            ref_file = git / ref
            if ref_file.exists():
                return ref_file.read_text(encoding="utf-8").strip()[:7]
            packed = git / "packed-refs"
            if packed.exists():
                for line in packed.read_text(encoding="utf-8").splitlines():
                    if line.endswith(" " + ref):
                        return line[:7]
            return "desconocida"
        return head[:7]
    except OSError:
        return static_version()[:7]


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
    """Guarda en disco el resultado del análisis de las pistas de audio."""

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
        """Guarda un análisis nuevo conservando la marca manual, si la hay."""
        with self._lock:
            manual = (self._data.get(key) or {}).get("manual")
            self._data[key] = {**value, **({"manual": manual} if manual else {})}
            self._save()

    def update(self, key: str, **fields) -> None:
        with self._lock:
            entry = dict(self._data.get(key) or {})
            for k, v in fields.items():
                if v is None:
                    entry.pop(k, None)
                else:
                    entry[k] = v
            if entry:
                self._data[key] = entry
            else:
                self._data.pop(key, None)
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


class ManualStatusRequest(BaseModel):
    status: str | None = Field(default=None, pattern="^(es|no_es)$")


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

    analysis = cache.get(_key(kind, raw.get("id"))) or {}
    if analysis.get("manual"):
        audio_status, source = analysis["manual"], "manual"
    elif analysis.get("status"):
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
        "hash": raw.get("hash") or "",
        "files": len(files),
        "file_list": sorted(
            (
                {"name": f.get("short_name") or f.get("name") or "", "size": f.get("size") or 0}
                for f in files
            ),
            key=lambda f: f["size"],
            reverse=True,
        ),
        "has_video": main is not None,
        "main_file": main_name,
        "audio_status": audio_status,
        "audio_source": source,
        "audio_languages": analysis.get("languages", []),
        "audio_auto_status": analysis.get("status") or name_guess,
        "analysis_error": analysis.get("error"),
    }


def create_app(client_factory=None, data_dir: Path | None = None) -> FastAPI:
    _load_dotenv()
    data_dir = resolve_data_dir(data_dir)
    cache = AnalysisCache(data_dir / "analysis.json")
    settings = SettingsStore(data_dir / "settings.json")

    def current_api_key(browser_token: str | None = None) -> tuple[str, str]:
        """Devuelve (clave, origen).

        Prioridad: token guardado en el navegador (cabecera X-Torbox-Token),
        token guardado en el servidor por versiones anteriores, y .env.
        """
        if browser_token and browser_token.strip():
            return browser_token.strip(), "navegador"
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

    def get_client(x_torbox_token: str | None = Header(default=None)):
        try:
            client = make_client(current_api_key(x_torbox_token)[0])
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
    def status(x_torbox_token: str | None = Header(default=None)):
        key, source = current_api_key(x_torbox_token)
        return {
            "api_key_configured": bool(key),
            "api_key_source": source,
            # True si se puede analizar (con ffprobe o, si no, con ffmpeg)
            "ffprobe": any(tools().values()),
        }

    def tools() -> dict:
        return language.find_tools()

    @app.get("/api/settings")
    def get_settings(x_torbox_token: str | None = Header(default=None)):
        key, source = current_api_key(x_torbox_token)
        return {
            "api_key_configured": bool(key),
            "api_key_masked": mask_key(key),
            "api_key_source": source,
        }

    @app.put("/api/settings")
    def save_settings(req: SettingsRequest):
        key = req.api_key.strip()
        if not key:
            raise HTTPException(status_code=400, detail="La API key no puede estar vacía.")
        # Solo se comprueba que funciona: el navegador la guarda en localStorage.
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
        return get_settings(key)

    @app.delete("/api/settings/api_key")
    def clear_api_key():
        # Borra el token que guardaban en el servidor versiones anteriores.
        if settings.get("api_key"):
            settings.set("api_key", None)
        return get_settings(None)

    @app.get("/api/items")
    def list_items(client=Depends(get_client)):
        items = _fetch_all(client)
        items.sort(key=lambda i: i["size"] or 0, reverse=True)
        dups = find_duplicates(items)
        for it in items:
            d = dups.get(_key(it["kind"], it["id"]))
            it["duplicate_group"] = d["group"] if d else None
            it["duplicate_label"] = d["label"] if d else None
        return {"items": items}

    # -- análisis y escucha ------------------------------------------------
    links: dict[tuple, tuple[float, str]] = {}
    clips: OrderedDict[tuple, tuple[bytes, str]] = OrderedDict()
    LINK_TTL = 30 * 60

    def link_for(client, kind: str, item_id: int, file_id: int) -> str:
        """Enlace de descarga de TorBox, reutilizado durante un rato."""
        k = (kind, item_id, file_id)
        now = time.monotonic()
        if k in links and now - links[k][0] < LINK_TTL:
            return links[k][1]
        url = client.download_link(kind, item_id, file_id)
        links[k] = (now, url)
        return url

    def probe_item(client, tl: dict, kind: str, raw: dict) -> dict:
        main = language.pick_main_video(raw.get("files") or [])
        if main is None:
            result = {"status": language.UNKNOWN, "languages": [], "tracks": [], "error": "Sin archivo de vídeo"}
        else:
            info = language.probe_with(tl, link_for(client, kind, raw["id"], main["id"]))
            langs = [t["language"] for t in info["tracks"]]
            result = {
                "status": language.classify_audio_languages(langs),
                "languages": langs,
                "tracks": info["tracks"],
                "duration": info["duration"],
                "error": None,
            }
        cache.set(_key(kind, raw.get("id")), result)
        return result

    def find_raw(client, kind: str, item_id: int) -> dict:
        try:
            for raw in client.list(kind):
                if raw.get("id") == item_id:
                    return raw
        except TorboxError as exc:
            raise HTTPException(status_code=502, detail=str(exc))
        raise HTTPException(status_code=404, detail="No encontrado en TorBox")

    def require_tools() -> dict:
        tl = tools()
        if not any(tl.values()):
            raise HTTPException(
                status_code=400,
                detail="No se encuentra ffmpeg. Ejecuta «pip install -r requirements.txt» (lo instala solo) y reinicia la app.",
            )
        return tl

    @app.get("/api/items/{kind}/{item_id}/tracks")
    def item_tracks(kind: str, item_id: int, client=Depends(get_client)):
        if kind not in KINDS:
            raise HTTPException(status_code=404, detail="Tipo desconocido")
        entry = cache.get(_key(kind, item_id)) or {}
        if "tracks" not in entry:
            tl = require_tools()
            raw = find_raw(client, kind, item_id)
            try:
                probe_item(client, tl, kind, raw)
            except TorboxError as exc:
                raise HTTPException(status_code=502, detail=str(exc))
            except Exception as exc:  # noqa: BLE001
                raise HTTPException(status_code=502, detail=f"No se pudo leer el archivo: {str(exc)[:300]}")
            entry = cache.get(_key(kind, item_id)) or {}
        return {
            "tracks": entry.get("tracks", []),
            "duration": entry.get("duration"),
            "status": entry.get("status"),
            "manual": entry.get("manual"),
            "error": entry.get("error"),
            "can_play": tools()["ffmpeg"] is not None,
        }

    @app.get("/api/items/{kind}/{item_id}/clip")
    def item_clip(
        kind: str, item_id: int,
        track: int = Query(0, ge=0, le=64),
        start: float = Query(0, ge=0, le=24 * 3600),
        duration: float = Query(30, ge=5, le=90),
        client=Depends(get_client),
    ):
        if kind not in KINDS:
            raise HTTPException(status_code=404, detail="Tipo desconocido")
        ffmpeg = tools()["ffmpeg"]
        if not ffmpeg:
            raise HTTPException(status_code=400, detail="No se encuentra ffmpeg (necesario para escuchar el audio).")
        ck = (kind, item_id, track, round(start), round(duration))
        if ck not in clips:
            raw = find_raw(client, kind, item_id)
            main = language.pick_main_video(raw.get("files") or [])
            if main is None:
                raise HTTPException(status_code=400, detail="Este elemento no tiene archivo de vídeo")
            try:
                url = link_for(client, kind, item_id, main["id"])
                clips[ck] = language.extract_audio_clip(url, track, start, duration, ffmpeg)
            except TorboxError as exc:
                raise HTTPException(status_code=502, detail=str(exc))
            except Exception as exc:  # noqa: BLE001
                raise HTTPException(status_code=502, detail=f"No se pudo extraer el audio: {str(exc)[:300]}")
            while len(clips) > 12:
                clips.popitem(last=False)
        clips.move_to_end(ck)
        data, mime = clips[ck]
        return Response(content=data, media_type=mime, headers={"Cache-Control": "private, max-age=600"})

    @app.put("/api/items/{kind}/{item_id}/audio_status")
    def set_manual_status(kind: str, item_id: int, req: ManualStatusRequest):
        if kind not in KINDS:
            raise HTTPException(status_code=404, detail="Tipo desconocido")
        cache.update(_key(kind, item_id), manual=req.status)
        return {"ok": True, "manual": req.status}

    @app.post("/api/analyze")
    def analyze(req: ItemsRequest, client=Depends(get_client)):
        tl = require_tools()
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
            try:
                result = probe_item(client, tl, kind, raw)
            except Exception as exc:  # noqa: BLE001 - se informa al usuario
                return {"key": key, "error": str(exc)[:300]}
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
        # Se sirve sin caché y con los recursos versionados, para que tras
        # actualizar la app el navegador nunca muestre la versión anterior.
        version = static_version()
        html = (STATIC_DIR / "index.html").read_text(encoding="utf-8")
        html = html.replace("/static/style.css", f"/static/style.css?v={version}")
        html = html.replace("/static/app.js", f"/static/app.js?v={version}")
        html = html.replace("{{APP_VERSION}}", app_version())
        return HTMLResponse(html, headers={"Cache-Control": "no-store"})

    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


app = create_app()
