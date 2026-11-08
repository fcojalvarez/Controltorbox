import pytest

from app.duplicates import find_duplicates, parse_name


@pytest.mark.parametrize("name,title,year,seasons,episode", [
    ("Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3", "dune part two", 2024, set(), None),
    ("Dune Part Two (2024) [1080p][Castellano AC3 5.1]", "dune part two", 2024, set(), None),
    ("Oppenheimer [BluRay 1080p][Castellano AC3]", "oppenheimer", None, set(), None),
    ("[ www.Torrent.com ] - Oppenheimer.2023.1080p.BluRay.x264.mkv", "oppenheimer", 2023, set(), None),
    ("The.Bear.S03E01.1080p.WEB.h264-ETHEL", "the bear", None, {3}, 1),
    ("The.Bear.S03.1080p.WEB.h264-ETHEL", "the bear", None, {3}, None),
    ("The Bear - Temporada 3 [HDTV 720p][Cap.301]", "the bear", None, {3}, None),
    ("Breaking.Bad.S01-S05.COMPLETE.1080p", "breaking bad", None, {1, 2, 3, 4, 5}, None),
    ("Shogun.2024.S01E01.VOSE.1080p", "shogun", 2024, {1}, 1),
    ("Los Simpson 3x05 [HDTV]", "los simpson", None, {3}, 5),
    ("1917.2019.1080p.BluRay", "1917", 2019, set(), None),
    ("Pelicula Española.2020.mkv", "pelicula espanola", 2020, set(), None),
])
def test_parse_name(name, title, year, seasons, episode):
    p = parse_name(name)
    assert (p.title, p.year, p.seasons, p.episode) == (title, year, seasons, episode)


def _items(*names, hashes=None):
    return [
        {"kind": "torrent", "id": i, "name": n, "hash": (hashes or {}).get(i, "")}
        for i, n in enumerate(names)
    ]


def _groups(result):
    groups = {}
    for key, g in result.items():
        groups.setdefault(g["group"], set()).add(int(key.split(":")[1]))
    return sorted(sorted(v) for v in groups.values())


def test_same_movie_different_quality():
    r = find_duplicates(_items(
        "Dune.Part.Two.2024.2160p.WEB-DL.Castellano.AC3",
        "Dune Part Two (2024) [1080p][Castellano AC3 5.1]",
        "Oppenheimer.2023.1080p",
    ))
    assert _groups(r) == [[0, 1]]
    assert r["torrent:0"]["label"] == "Dune Part Two (2024)"


def test_remakes_with_different_year_are_not_duplicates():
    assert find_duplicates(_items("Dune.1984.1080p.BluRay", "Dune.2021.2160p.WEB")) == {}


def test_movie_without_year_matches():
    assert _groups(find_duplicates(_items("Oppenheimer [BluRay 1080p]", "Oppenheimer.2023.2160p"))) == [[0, 1]]


def test_series_episodes_and_packs():
    r = find_duplicates(_items(
        "The.Bear.S03E01.1080p.WEB",      # 0 } mismo episodio
        "The Bear S03E01 720p HDTV",      # 1 }
        "The.Bear.S03E02.1080p.WEB",      # 2 -> incluido en el pack
        "The.Bear.S03.1080p.WEB",         # 3 pack T3
        "The.Bear.S02E01.1080p",          # 4 otra temporada: no
        "The.Bear.S04E01.1080p",          # 5 otra temporada: no
    ))
    assert _groups(r) == [[0, 1, 2, 3]]


def test_different_episodes_are_not_duplicates():
    assert find_duplicates(_items("Show.S01E01.1080p", "Show.S01E02.1080p")) == {}


def test_complete_series_covers_seasons():
    assert _groups(find_duplicates(_items("Breaking.Bad.S01-S05.1080p", "Breaking Bad S04E10 720p"))) == [[0, 1]]


def test_movie_and_series_with_same_title_are_not_duplicates():
    assert find_duplicates(_items("Fargo.1996.1080p", "Fargo.S01E01.1080p")) == {}


def test_same_hash_is_duplicate_even_if_names_differ():
    r = find_duplicates(_items("abc", "xyz", hashes={0: "ABC123", 1: "abc123"}))
    assert _groups(r) == [[0, 1]]
