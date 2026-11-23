"""Cliente mínimo de la API de TorBox (v1).

Cubre los tres tipos de descarga que ocupan almacenamiento:
torrents, usenet y descargas web.
"""
from __future__ import annotations

from dataclasses import dataclass

import httpx

DEFAULT_BASE_URL = "https://api.torbox.app/v1/api"


@dataclass(frozen=True)
class Kind:
    name: str            # "torrent" | "usenet" | "webdl"
    list_path: str
    control_path: str
    control_id_field: str
    requestdl_path: str
    requestdl_id_param: str


KINDS: dict[str, Kind] = {
    "torrent": Kind(
        "torrent",
        "/torrents/mylist",
        "/torrents/controltorrent",
        "torrent_id",
        "/torrents/requestdl",
        "torrent_id",
    ),
    "usenet": Kind(
        "usenet",
        "/usenet/mylist",
        "/usenet/controlusenetdownload",
        "usenet_id",
        "/usenet/requestdl",
        "usenet_id",
    ),
    "webdl": Kind(
        "webdl",
        "/webdl/mylist",
        "/webdl/controlwebdownload",
        "webdl_id",
        "/webdl/requestdl",
        "web_id",
    ),
}


class TorboxError(RuntimeError):
    pass


class TorboxClient:
    def __init__(
        self,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 30.0,
    ) -> None:
        if not api_key:
            raise TorboxError("Falta la API key de TorBox (TORBOX_API_KEY).")
        self._api_key = api_key
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=timeout,
            transport=transport,
        )

    def close(self) -> None:
        self._client.close()

    # -- helpers -----------------------------------------------------------
    def _unwrap(self, resp: httpx.Response):
        try:
            payload = resp.json()
        except ValueError:
            raise TorboxError(f"Respuesta no válida de TorBox (HTTP {resp.status_code})")
        if resp.status_code >= 400 or not payload.get("success", False):
            detail = payload.get("detail") or payload.get("error") or resp.text
            raise TorboxError(f"TorBox: {detail} (HTTP {resp.status_code})")
        return payload.get("data")

    # -- API ---------------------------------------------------------------
    def list(self, kind: str) -> list[dict]:
        k = KINDS[kind]
        resp = self._client.get(k.list_path, params={"bypass_cache": "true"})
        data = self._unwrap(resp)
        return data or []

    def delete(self, kind: str, item_id: int) -> None:
        self.control(kind, item_id, "delete")

    def control(self, kind: str, item_id: int, operation: str) -> None:
        """Operaciones sobre una descarga: delete, reannounce, pause, resume."""
        k = KINDS[kind]
        resp = self._client.post(
            k.control_path,
            # TorBox exige también "all" (False = solo este elemento).
            json={k.control_id_field: int(item_id), "operation": operation, "all": False},
        )
        self._unwrap(resp)

    def queued(self, kind: str) -> list[dict]:
        """Cola de TorBox (aún sin empezar) para un tipo de descarga."""
        resp = self._client.get("/queued/getqueued", params={"type": kind, "bypass_cache": "true"})
        return self._unwrap(resp) or []

    def control_queued(self, kind: str, queued_id: int, operation: str) -> None:
        """Iniciar («start») o borrar («delete») algo de la cola."""
        resp = self._client.post(
            "/queued/controlqueued",
            json={"queued_id": int(queued_id), "operation": operation, "type": kind, "all": False},
        )
        self._unwrap(resp)

    def account(self) -> dict:
        """Plan de la cuenta y, si TorBox lo indica, su límite de almacenamiento en bytes."""
        resp = self._client.get("/user/me", params={"settings": "false"})
        data = self._unwrap(resp) or {}
        return parse_account(data)

    def download_link(self, kind: str, item_id: int, file_id: int) -> str:
        k = KINDS[kind]
        resp = self._client.get(
            k.requestdl_path,
            params={
                "token": self._api_key,
                k.requestdl_id_param: int(item_id),
                "file_id": int(file_id),
                "redirect": "false",
            },
        )
        link = self._unwrap(resp)
        if not isinstance(link, str) or not link:
            raise TorboxError("TorBox no devolvió un enlace de descarga.")
        return link


PLAN_NAMES = {0: "Free", 1: "Essential", 2: "Pro", 3: "Standard"}


def parse_account(data: dict) -> dict:
    """La API documentada no da el almacenamiento máximo; si la respuesta real trae un
    campo numérico de almacenamiento y límite, se usa (en bytes, o en GB si es pequeño)."""
    plan = data.get("plan")
    plan = plan if isinstance(plan, int) else None
    limit = None
    for key, value in data.items():
        k = key.lower()
        about = "storage" in k or "space" in k or "quota" in k
        is_limit = "limit" in k or "max" in k or "total" in k or "quota" in k
        if about and is_limit and "used" not in k and isinstance(value, (int, float)) and value > 0:
            limit = int(value) if value > 1e7 else int(value * 1024 ** 3)
            break
    return {"plan": plan, "plan_name": PLAN_NAMES.get(plan), "storage_limit": limit}
