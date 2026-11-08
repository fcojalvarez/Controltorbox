import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.torbox import TorboxClient

DATA = {
    "/torrents/mylist": [
        {"id": 1, "name": "Movie.2023.1080p.Castellano", "size": 100,
         "files": [{"id": 10, "name": "Movie.2023.1080p.Castellano/m.mkv", "size": 100}]},
        {"id": 2, "name": "Show.S01E01.1080p.WEB", "size": 300,
         "files": [{"id": 20, "name": "Show/s.mkv", "size": 300}]},
    ],
    "/usenet/mylist": [
        {"id": 5, "name": "Film.2020.VOSE", "size": 200,
         "files": [{"id": 50, "name": "f.mp4", "size": 200}]},
    ],
    "/webdl/mylist": [],
}


@pytest.fixture
def setup(tmp_path, monkeypatch):
    monkeypatch.setenv("TORBOX_API_KEY", "test")
    calls = []

    def handler(request: httpx.Request):
        path = request.url.path.removeprefix("/v1/api")
        if request.headers["authorization"] not in ("Bearer test", "Bearer nueva"):
            return httpx.Response(403, json={"success": False, "detail": "BAD_TOKEN"})
        if path in DATA:
            return httpx.Response(200, json={"success": True, "data": DATA[path]})
        if request.method == "POST":
            calls.append((path, request.read()))
            return httpx.Response(200, json={"success": True, "data": None})
        return httpx.Response(404, json={"success": False, "detail": "nope"})

    transport = httpx.MockTransport(handler)
    app = create_app(
        client_factory=lambda key: TorboxClient(key, transport=transport),
        data_dir=tmp_path,
    )
    return TestClient(app), calls


def test_list_items(setup):
    client, _ = setup
    items = client.get("/api/items").json()["items"]
    by_id = {(i["kind"], i["id"]): i for i in items}
    assert [i["id"] for i in items] == [2, 5, 1]  # ordenados por tamaño
    assert by_id[("torrent", 1)]["audio_status"] == "es"
    assert by_id[("torrent", 2)]["audio_status"] == "unknown"
    assert by_id[("usenet", 5)]["audio_status"] == "no_es"


def test_delete(setup):
    client, calls = setup
    r = client.post("/api/delete", json={"items": [{"kind": "torrent", "id": 2}, {"kind": "usenet", "id": 5}]})
    assert all(x["ok"] for x in r.json()["results"])
    assert calls[0][0] == "/torrents/controltorrent"
    assert b'"torrent_id":2' in calls[0][1] and b'"operation":"delete"' in calls[0][1]
    assert calls[1][0] == "/usenet/controlusenetdownload"


def test_delete_rejects_bad_kind(setup):
    client, _ = setup
    r = client.post("/api/delete", json={"items": [{"kind": "foo", "id": 1}]})
    assert r.status_code == 422


def test_analyze_uses_cache(setup, monkeypatch):
    client, _ = setup
    from app import language
    monkeypatch.setattr(language, "find_ffprobe", lambda custom=None: "/x/ffprobe")
    monkeypatch.setattr(TorboxClient, "download_link", lambda self, k, i, f: "http://x/file.mkv")
    monkeypatch.setattr(language, "probe_audio_languages", lambda url, ffprobe: ["eng", "jpn"] if ffprobe == "/x/ffprobe" else [])
    r = client.post("/api/analyze", json={"items": [{"kind": "torrent", "id": 1}]})
    assert r.json()["results"][0]["status"] == "no_es"
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 1)
    assert item["audio_status"] == "no_es" and item["audio_source"] == "ffprobe"


def test_settings_api_key(setup, monkeypatch):
    client, _ = setup
    s = client.get("/api/settings").json()
    assert s["api_key_source"] == "env" and "test" not in s["api_key_masked"]

    # Una clave inválida no se guarda
    r = client.put("/api/settings", json={"api_key": "mala"})
    assert r.status_code == 400 and "BAD_TOKEN" in r.json()["detail"]
    assert client.get("/api/settings").json()["api_key_source"] == "env"

    # La clave válida se guarda y tiene prioridad sobre el .env
    r = client.put("/api/settings", json={"api_key": " nueva "})
    assert r.status_code == 200
    assert r.json()["api_key_source"] == "ajustes"
    assert "nueva" not in r.json()["api_key_masked"][:-4]  # nunca se devuelve entera
    monkeypatch.delenv("TORBOX_API_KEY")
    assert client.get("/api/items").status_code == 200

    # Borrarla vuelve a dejar la app sin clave
    client.delete("/api/settings/api_key")
    assert client.get("/api/status").json()["api_key_configured"] is False
    assert client.get("/api/items").status_code == 400


def test_settings_persist_on_disk(tmp_path, monkeypatch):
    monkeypatch.delenv("TORBOX_API_KEY", raising=False)
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={"success": True, "data": []}))
    factory = lambda key: TorboxClient(key, transport=transport)
    TestClient(create_app(client_factory=factory, data_dir=tmp_path)).put("/api/settings", json={"api_key": "abcd1234"})
    again = TestClient(create_app(client_factory=factory, data_dir=tmp_path))
    assert again.get("/api/settings").json()["api_key_masked"].endswith("1234")


def test_settings_save_failure_returns_readable_error(tmp_path, monkeypatch):
    monkeypatch.delenv("TORBOX_API_KEY", raising=False)
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={"success": True, "data": []}))
    app = create_app(client_factory=lambda k: TorboxClient(k, transport=transport), data_dir=tmp_path)
    from pathlib import Path

    def boom(self, *a, **kw):
        raise PermissionError(13, "Permission denied")

    monkeypatch.setattr(Path, "write_text", boom)
    r = TestClient(app, raise_server_exceptions=False).put("/api/settings", json={"api_key": "abc12345"})
    assert r.status_code == 500
    assert "No se pudo guardar" in r.json()["detail"]


def test_unexpected_errors_are_json(tmp_path, monkeypatch):
    monkeypatch.setenv("TORBOX_API_KEY", "x")

    class Broken:
        def list(self, kind):
            raise ValueError("algo raro")

        def close(self):
            pass

    app = create_app(client_factory=lambda k: Broken(), data_dir=tmp_path)
    r = TestClient(app, raise_server_exceptions=False).get("/api/items")
    assert r.status_code == 500 and "algo raro" in r.json()["detail"]


def test_default_data_dir_is_project_dir(monkeypatch, tmp_path):
    from app import main
    monkeypatch.delenv("CONTROLTORBOX_DATA_DIR", raising=False)
    monkeypatch.chdir(tmp_path)
    assert main.resolve_data_dir() == main.PROJECT_DIR / "data"


def test_ffprobe_setting(setup, tmp_path, monkeypatch):
    client, _ = setup
    import shutil
    real = shutil.which("ffprobe")
    if not real:
        pytest.skip("ffprobe no instalado")
    # Una ruta que no existe se rechaza
    r = client.put("/api/settings/ffprobe", json={"path": str(tmp_path / "nope")})
    assert r.status_code == 400
    # Un ejecutable que no es ffprobe se rechaza
    fake = tmp_path / "ffprobe"
    fake.write_text("#!/bin/sh\nexit 1\n"); fake.chmod(0o755)
    assert client.put("/api/settings/ffprobe", json={"path": str(fake)}).status_code == 400
    # Se acepta la carpeta que lo contiene y queda guardado
    from pathlib import Path
    r = client.put("/api/settings/ffprobe", json={"path": str(Path(real).parent)})
    assert r.status_code == 200 and r.json()["ffprobe_custom"] == real
    # Sin PATH sigue encontrándolo gracias a la ruta guardada
    monkeypatch.setenv("PATH", "")
    assert client.get("/api/status").json()["ffprobe"] is True
    # Vacío -> detección automática
    r = client.put("/api/settings/ffprobe", json={"path": ""})
    assert r.json()["ffprobe_custom"] == ""
