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
        assert request.headers["authorization"] == "Bearer test"
        if path in DATA:
            return httpx.Response(200, json={"success": True, "data": DATA[path]})
        if request.method == "POST":
            calls.append((path, request.read()))
            return httpx.Response(200, json={"success": True, "data": None})
        return httpx.Response(404, json={"success": False, "detail": "nope"})

    transport = httpx.MockTransport(handler)
    app = create_app(
        client_factory=lambda: TorboxClient("test", transport=transport),
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
    monkeypatch.setattr(language, "ffprobe_available", lambda: True)
    monkeypatch.setattr(TorboxClient, "download_link", lambda self, k, i, f: "http://x/file.mkv")
    monkeypatch.setattr(language, "probe_audio_languages", lambda url: ["eng", "jpn"])
    r = client.post("/api/analyze", json={"items": [{"kind": "torrent", "id": 1}]})
    assert r.json()["results"][0]["status"] == "no_es"
    item = next(i for i in client.get("/api/items").json()["items"] if i["id"] == 1)
    assert item["audio_status"] == "no_es" and item["audio_source"] == "ffprobe"
