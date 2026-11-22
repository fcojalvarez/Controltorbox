import pytest

from app import language as L
from app import titles


@pytest.mark.parametrize("name,expected", [
    ("Oppenheimer.2023.1080p.BluRay.x264", "Oppenheimer (2023)"),
    ("The.Bear.S03E02.720p.HDTV", "The Bear · T3 E2"),
    ("Breaking.Bad.S01-S05.COMPLETE.1080p", "Breaking Bad · T1–T5"),
    ("La.Casa.de.Papel.S02.1080p", "La Casa de Papel · T2"),
    ("x", "x"),
])
def test_clean(name, expected):
    assert titles.clean(name) == expected


def test_quality_and_episode_label():
    assert titles.quality("Oppenheimer (2023) [4K HDR][Dual]") == "4K HDR"
    assert titles.quality("Shogun.2024.S01E01.VOSE.1080p") == "1080p VOSE"
    assert titles.episode_label("x/Show S01 E10 720p.mkv") == "T1 E10"
    assert titles.episode_label("Los Simpson 3x05.avi") == "T3 E5"


def test_combine_and_video_by_mimetype():
    assert L.combine(["es", "es"]) == "es"
    assert L.combine(["es", "no_es"]) == "no_es"
    assert L.combine(["es", "unknown"]) == "unknown"
    files = [{"id": 1, "name": "A/Episodio 1", "mimetype": "video/mp4", "size": 5}, {"id": 2, "name": "notas", "mimetype": "text/plain"}]
    assert [f["id"] for f in L.video_files(files)] == [1]
