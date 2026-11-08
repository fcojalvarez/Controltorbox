import pytest

from app import language as L


@pytest.mark.parametrize("name,expected", [
    ("Dune.Part.Two.2024.1080p.WEB-DL.Castellano.AC3.mkv", L.ES),
    ("Oppenheimer (2023) [BluRay 1080p][Spanish+English]", L.ES),
    ("The.Bear.S03E01.1080p.WEB.h264-ETHEL", L.UNKNOWN),
    ("Pelicula.2020.VOSE.720p.mkv", L.NO_ES),
    ("Movie.2021.1080p.ENG.Subs.Spanish.mkv", L.UNKNOWN),
    ("Serie.S01.MULTi.1080p.Latino.mkv", L.ES),
    ("Film.2019.DUAL.SPA-ENG.1080p", L.ES),
])
def test_guess_from_name(name, expected):
    assert L.guess_from_name(name) == expected


@pytest.mark.parametrize("langs,expected", [
    (["eng", "spa"], L.ES),
    (["eng"], L.NO_ES),
    (["und"], L.UNKNOWN),
    ([], L.UNKNOWN),
    (["jpn", "eng"], L.NO_ES),
    (["es-419"], L.ES),
])
def test_classify_audio_languages(langs, expected):
    assert L.classify_audio_languages(langs) == expected


def test_pick_main_video():
    files = [
        {"id": 1, "name": "a/sample.mkv", "size": 10},
        {"id": 2, "name": "a/movie.mkv", "size": 1000},
        {"id": 3, "name": "a/info.nfo", "size": 5000},
    ]
    assert L.pick_main_video(files)["id"] == 2
    assert L.pick_main_video([{"id": 1, "name": "x.txt"}]) is None


def test_find_ffprobe_uses_custom_then_candidates(tmp_path, monkeypatch):
    exe = tmp_path / "bin" / "ffprobe"
    exe.parent.mkdir()
    exe.write_text("")
    monkeypatch.setenv("PATH", "")
    monkeypatch.delenv("FFPROBE_PATH", raising=False)
    monkeypatch.setattr(L, "_candidate_paths", lambda: [])
    assert L.find_ffprobe() is None
    assert L.find_ffprobe(str(exe.parent)) == str(exe)  # carpeta -> ejecutable
    monkeypatch.setattr(L, "_candidate_paths", lambda: [exe])
    assert L.find_ffprobe() == str(exe)
