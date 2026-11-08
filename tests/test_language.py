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


@pytest.fixture(scope="module")
def sample_mkv(tmp_path_factory):
    import shutil, subprocess
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        pytest.skip("ffmpeg no instalado")
    out = tmp_path_factory.mktemp("media") / "m.mkv"
    subprocess.run([
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", "color=c=black:s=64x64:d=20",
        "-f", "lavfi", "-i", "sine=f=440:d=20", "-f", "lavfi", "-i", "sine=f=880:d=20",
        "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-c:a", "ac3",
        "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "title=Castellano AC3 5.1",
        str(out),
    ], check=True)
    return out


def test_probe_media_real_file(sample_mkv):
    info = L.probe_media(str(sample_mkv))
    assert [t["language"] for t in info["tracks"]] == ["eng", "spa"]
    assert info["tracks"][0]["codec"] == "ac3"
    assert 19 < info["duration"] < 21


def test_extract_audio_clip_real_file(sample_mkv):
    data, mime = L.extract_audio_clip(str(sample_mkv), track=1, start=5, duration=5)
    assert mime in ("audio/mpeg", "audio/wav") and len(data) > 10_000
    with pytest.raises(RuntimeError):
        L.extract_audio_clip(str(sample_mkv), track=7, start=0, duration=5)


MKV_INFO = """Input #0, matroska,webm, from 'x.mkv':
  Duration: 01:52:10.50, start: 0.000000, bitrate: 594 kb/s
  Stream #0:0: Video: h264 (High), yuv420p(tv, progressive), 1920x1080, 23.98 fps
    Metadata:
      DURATION        : 01:52:10.500000000
  Stream #0:1(eng): Audio: eac3, 48000 Hz, 5.1(side), fltp, 640 kb/s (default)
    Metadata:
      title           : English DD+ 5.1
  Stream #0:2: Audio: ac3, 48000 Hz, stereo, fltp, 192 kb/s
    Metadata:
      title           : Castellano AC3 2.0
  Stream #0:3(jpn): Audio: dts (DTS-HD MA), 48000 Hz, 7.1, s32p (24 bit)
  Stream #0:4(spa): Subtitle: subrip
    Metadata:
      title           : Forzados
At least one output file must be specified
"""

MP4_INFO = """Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'x.mp4':
  Duration: 00:42:00.00, start: 0.000000, bitrate: 4000 kb/s
  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p, 1920x1080
  Stream #0:1[0x2](eng): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, 5.1, fltp, 384 kb/s (default)
  Stream #0:2[0x3](und): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, 6 channels, fltp
"""


def test_parse_ffmpeg_info_mkv():
    info = L.parse_ffmpeg_info(MKV_INFO)
    assert info["duration"] == pytest.approx(6730.5)
    assert [(t["n"], t["language"], t["codec"], t["channels"]) for t in info["tracks"]] == [
        (0, "eng", "eac3", 6), (1, "spa", "ac3", 2), (2, "jpn", "dts", 8),
    ]
    assert info["tracks"][0]["title"] == "English DD+ 5.1"
    assert info["tracks"][1]["title"] == "Castellano AC3 2.0"  # idioma deducido del título
    assert info["tracks"][2]["title"] == ""                     # no hereda el título del subtítulo


def test_parse_ffmpeg_info_mp4():
    info = L.parse_ffmpeg_info(MP4_INFO)
    assert info["duration"] == 2520
    assert [(t["language"], t["channels"]) for t in info["tracks"]] == [("eng", 6), ("und", 6)]


def test_bundled_ffmpeg_probe_and_clip(sample_mkv):
    ff = L.bundled_ffmpeg()
    if not ff:
        pytest.skip("imageio-ffmpeg no instalado")
    info = L.probe_media_ffmpeg(str(sample_mkv), ff)
    assert [t["language"] for t in info["tracks"]] == ["eng", "spa"]
    data, mime = L.extract_audio_clip(str(sample_mkv), track=1, start=2, duration=5, ffmpeg=ff)
    assert mime == "audio/mpeg" and len(data) > 10_000


def test_probe_with_prefers_ffprobe_and_falls_back(monkeypatch):
    monkeypatch.setattr(L, "probe_media", lambda url, ffprobe: "ffprobe")
    monkeypatch.setattr(L, "probe_media_ffmpeg", lambda url, ffmpeg: "ffmpeg")
    assert L.probe_with({"ffprobe": "/a", "ffmpeg": "/b"}, "u") == "ffprobe"
    assert L.probe_with({"ffprobe": None, "ffmpeg": "/b"}, "u") == "ffmpeg"
    with pytest.raises(RuntimeError):
        L.probe_with({"ffprobe": None, "ffmpeg": None}, "u")
