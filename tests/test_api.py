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
    monkeypatch.setattr(
        language, "probe_media",
        lambda url, ffprobe: {"duration": 100.0, "tracks": [
            {"n": 0, "language": "eng", "title": "", "codec": "ac3", "channels": 6},
            {"n": 1, "language": "jpn", "title": "", "codec": "aac", "channels": 2},
        ]} if ffprobe == "/x/ffprobe" else {"duration": None, "tracks": []},
    )
    r = client.post("/api/analyze", json={"items": [{"kind": "torrent", "id": 1}]})
    assert r.json()["results"][0]["status"] == "no_es"
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 1)
    assert item["audio_status"] == "no_es" and item["audio_source"] == "ffprobe"


def test_settings_api_key_from_browser(setup, monkeypatch):
    client, _ = setup
    s = client.get("/api/settings").json()
    assert s["api_key_source"] == "env" and "test" not in s["api_key_masked"]

    # Una clave inválida se rechaza
    r = client.put("/api/settings", json={"api_key": "mala"})
    assert r.status_code == 400 and "BAD_TOKEN" in r.json()["detail"]

    # Una clave válida se acepta, pero el servidor NO la guarda (la guarda el navegador)
    r = client.put("/api/settings", json={"api_key": " nueva "})
    assert r.status_code == 200 and r.json()["api_key_source"] == "navegador"
    assert "nueva" not in r.json()["api_key_masked"]
    monkeypatch.delenv("TORBOX_API_KEY")
    assert client.get("/api/status").json()["api_key_configured"] is False
    assert client.get("/api/items").status_code == 400

    # El token enviado por el navegador en la cabecera es el que se usa
    h = {"X-Torbox-Token": "nueva"}
    assert client.get("/api/status", headers=h).json()["api_key_source"] == "navegador"
    assert client.get("/api/items", headers=h).status_code == 200
    assert client.get("/api/items", headers={"X-Torbox-Token": "mala"}).status_code == 502


def test_browser_token_wins_over_legacy_server_token(tmp_path, monkeypatch):
    import json
    monkeypatch.delenv("TORBOX_API_KEY", raising=False)
    (tmp_path / "settings.json").write_text(json.dumps({"api_key": "viejo1234"}))
    seen = []

    def handler(r):
        seen.append(r.headers["authorization"])
        return httpx.Response(200, json={"success": True, "data": []})

    transport = httpx.MockTransport(handler)
    client = TestClient(create_app(client_factory=lambda k: TorboxClient(k, transport=transport), data_dir=tmp_path))
    assert client.get("/api/settings").json()["api_key_source"] == "ajustes"  # compatibilidad
    client.get("/api/items", headers={"X-Torbox-Token": "nuevo"})
    assert set(seen) == {"Bearer nuevo"}
    # «Borrar token» también elimina el antiguo del servidor
    client.delete("/api/settings/api_key")
    assert client.get("/api/status").json()["api_key_configured"] is False


def test_storage_failure_returns_readable_error(tmp_path, monkeypatch):
    import json
    from pathlib import Path
    monkeypatch.delenv("TORBOX_API_KEY", raising=False)
    (tmp_path / "settings.json").write_text(json.dumps({"api_key": "viejo1234"}))
    app = create_app(client_factory=lambda k: TorboxClient(k), data_dir=tmp_path)

    def boom(self, *a, **kw):
        raise PermissionError(13, "Permission denied")

    monkeypatch.setattr(Path, "write_text", boom)
    r = TestClient(app, raise_server_exceptions=False).delete("/api/settings/api_key")
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


def test_tracks_clip_and_manual_status(setup, monkeypatch):
    client, _ = setup
    from app import language
    monkeypatch.setattr(language, "find_ffprobe", lambda custom=None: "/x/ffprobe")
    monkeypatch.setattr(language, "find_ffmpeg", lambda p=None: "/x/ffmpeg")
    links = []
    monkeypatch.setattr(TorboxClient, "download_link", lambda self, k, i, f: links.append((k, i, f)) or "http://x/f.mkv")
    monkeypatch.setattr(language, "probe_media", lambda url, ffprobe: {
        "duration": 5400.0,
        "tracks": [{"n": 0, "language": "und", "title": "", "codec": "ac3", "channels": 6}],
    })
    calls = []

    def fake_clip(url, track, start, duration, ffmpeg):
        calls.append((url, track, start, duration, ffmpeg))
        return b"ID3fake", "audio/mpeg"

    monkeypatch.setattr(language, "extract_audio_clip", fake_clip)

    r = client.get("/api/items/torrent/2/tracks").json()
    assert r["duration"] == 5400.0 and r["tracks"][0]["language"] == "und" and r["can_play"]
    assert r["status"] == "unknown"

    r = client.get("/api/items/torrent/2/clip", params={"track": 0, "start": 600, "duration": 30})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/mpeg" and r.content == b"ID3fake"
    client.get("/api/items/torrent/2/clip", params={"track": 0, "start": 600, "duration": 30})
    assert len(calls) == 1          # el fragmento se reutiliza
    assert len(links) == 1          # el enlace de TorBox también
    assert calls[0][1:] == (0, 600.0, 30.0, "/x/ffmpeg")

    assert client.get("/api/items/torrent/2/clip", params={"duration": 9999}).status_code == 422
    assert client.get("/api/items/foo/2/clip").status_code == 404

    # Marcar a mano tras escuchar: prevalece sobre el análisis y sobrevive a re-analizar
    assert client.put("/api/items/torrent/2/audio_status", json={"status": "es"}).status_code == 200
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 2)
    assert item["audio_status"] == "es" and item["audio_source"] == "manual"
    client.post("/api/analyze", json={"items": [{"kind": "torrent", "id": 2}]})
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 2)
    assert item["audio_status"] == "es" and item["audio_source"] == "manual"
    # Quitar la marca vuelve al resultado automático
    client.put("/api/items/torrent/2/audio_status", json={"status": None})
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 2)
    assert item["audio_source"] == "ffprobe" and item["audio_status"] == "unknown"
    assert client.put("/api/items/torrent/2/audio_status", json={"status": "x"}).status_code == 422


def test_items_include_file_list_and_date(tmp_path, monkeypatch):
    monkeypatch.setenv("TORBOX_API_KEY", "x")
    data = [{"id": 9, "name": "Show.S01", "size": 30, "created_at": "2026-03-01T10:00:00Z", "files": [
        {"id": 1, "name": "Show.S01/e1.mkv", "short_name": "e1.mkv", "size": 10},
        {"id": 2, "name": "Show.S01/e2.mkv", "short_name": "e2.mkv", "size": 20},
    ]}]
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={
        "success": True, "data": data if "torrents" in r.url.path else []}))
    app = create_app(client_factory=lambda k: TorboxClient(k, transport=transport), data_dir=tmp_path)
    item = TestClient(app).get("/api/items").json()["items"][0]
    assert item["created_at"] == "2026-03-01T10:00:00Z"
    assert item["file_list"] == [{"name": "e2.mkv", "size": 20}, {"name": "e1.mkv", "size": 10}]
