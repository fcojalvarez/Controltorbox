"""Detección de películas y series duplicadas a partir del nombre de la release."""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

# Palabras que marcan el final del título (calidad, origen, códec, idioma...).
_STOP_WORDS = {
    "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "uhd", "hdr", "hdr10", "dv", "sdr",
    "bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "microhd", "web", "webrip", "webdl",
    "hdtv", "dvdrip", "dvd", "dvdscr", "hdrip", "screener", "cam", "ts",
    "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "10bit",
    "castellano", "spanish", "espanol", "latino", "multi", "dual", "vose", "vos", "subs",
    "ac3", "dts", "aac", "eac3", "ddp", "atmos", "truehd",
    "proper", "repack", "extended", "remastered", "unrated", "internal", "limited",
    "complete", "completa", "integral",
}
_YEAR_RE = re.compile(r"^(19[2-9]\d|20[0-4]\d)$")
_SE_RE = re.compile(r"^s(\d{1,2})(?:e(\d{1,3}))?(?:e\d{1,3})*$")          # S03, S03E01, S03E01E02
_SRANGE_RE = re.compile(r"^s(\d{1,2})s(\d{1,2})$")                        # S01-S03 -> "s01s03" tras unir
_X_RE = re.compile(r"^(\d{1,2})x(\d{2,3})$")                               # 3x01
_SEASON_WORDS = {"season", "temporada", "temp"}
_EXTS = (".mkv", ".mp4", ".avi", ".m4v", ".ts", ".iso", ".mov", ".wmv")


@dataclass
class ParsedName:
    title: str
    year: int | None = None
    seasons: set[int] = field(default_factory=set)
    episode: int | None = None

    @property
    def is_series(self) -> bool:
        return bool(self.seasons)


def _strip_accents(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))


def _tokens(name: str) -> list[str]:
    name = _strip_accents(name or "").lower()
    for ext in _EXTS:
        if name.endswith(ext):
            name = name[: -len(ext)]
    # Prefijos de webs: "[ www.site.com ] - Título" / "www.site.org - Título"
    name = re.sub(r"^\W*www\.[a-z0-9-]+\.[a-z]{2,4}\W*", " ", name)
    name = re.sub(r"\bs(\d{1,2})\s*-\s*s(\d{1,2})\b", r"s\1s\2", name)  # rango de temporadas
    return [t for t in re.split(r"[^a-z0-9]+", name) if t]


def parse_name(name: str) -> ParsedName:
    tokens = _tokens(name)
    title: list[str] = []
    year = None
    seasons: set[int] = set()
    episode = None
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if title and _YEAR_RE.match(tok):
            year = year or int(tok)
            break
        m = _SE_RE.match(tok)
        if m:
            seasons.add(int(m.group(1)))
            episode = int(m.group(2)) if m.group(2) else None
            break
        m = _SRANGE_RE.match(tok)
        if m:
            a, b = sorted((int(m.group(1)), int(m.group(2))))
            seasons.update(range(a, b + 1))
            break
        m = _X_RE.match(tok)
        if m and title:
            seasons.add(int(m.group(1)))
            episode = int(m.group(2))
            break
        if tok in _SEASON_WORDS and i + 1 < len(tokens) and tokens[i + 1].isdigit() and title:
            seasons.add(int(tokens[i + 1]))
            break
        if title and tok in _STOP_WORDS:
            break
        title.append(tok)
        i += 1

    # Si el año vino primero (antes de temporada/calidad), buscamos también
    # temporada/episodio en lo que queda: "Show 2019 S02E03".
    for tok in tokens[i + 1:] if year else []:
        m = _SE_RE.match(tok)
        if m:
            seasons.add(int(m.group(1)))
            episode = int(m.group(2)) if m.group(2) else None
            break
        if tok in _STOP_WORDS:
            break
    return ParsedName(" ".join(title), year, seasons, episode)


def _overlap(a: ParsedName, b: ParsedName) -> bool:
    """¿Contienen lo mismo dos releases del mismo título?"""
    if a.is_series != b.is_series:
        return False
    if not a.is_series:
        return a.year is None or b.year is None or a.year == b.year
    if a.year and b.year and a.year != b.year:
        return False  # remakes / series distintas con el mismo nombre
    if not (a.seasons & b.seasons):
        return False
    # Mismo episodio, o al menos uno es la temporada completa.
    return a.episode is None or b.episode is None or a.episode == b.episode


def find_duplicates(items: list[dict]) -> dict[str, dict]:
    """Agrupa los elementos duplicados.

    `items` necesita "kind", "id", "name" y opcionalmente "hash" y "main_file".
    Devuelve {clave_elemento: {"group": id, "label": texto}} solo para los
    elementos que tienen duplicados.
    """
    keys = [f'{it["kind"]}:{it["id"]}' for it in items]
    parent = list(range(len(items)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    # 1) Mismo torrent (mismo hash) añadido dos veces.
    by_hash: dict[str, int] = {}
    for i, it in enumerate(items):
        h = (it.get("hash") or "").lower()
        if h:
            if h in by_hash:
                union(by_hash[h], i)
            else:
                by_hash[h] = i

    # 2) Mismo título (y año / temporada / episodio compatibles).
    parsed = []
    for it in items:
        p = parse_name(it.get("name") or "")
        if len(p.title) < 2 and it.get("main_file"):
            p = parse_name(it["main_file"].rsplit("/", 1)[-1])
        parsed.append(p)
    by_title: dict[str, list[int]] = {}
    for i, p in enumerate(parsed):
        if len(p.title) >= 2:
            by_title.setdefault(p.title, []).append(i)
    for idxs in by_title.values():
        for x in range(len(idxs)):
            for y in range(x + 1, len(idxs)):
                if _overlap(parsed[idxs[x]], parsed[idxs[y]]):
                    union(idxs[x], idxs[y])

    groups: dict[int, list[int]] = {}
    for i in range(len(items)):
        groups.setdefault(find(i), []).append(i)
    result: dict[str, dict] = {}
    for root, members in groups.items():
        if len(members) > 1:
            p = parsed[root]
            label = p.title.title()
            if p.year:
                label += f" ({p.year})"
            if p.is_series:
                label += " · T" + ",".join(str(s) for s in sorted(p.seasons))
            for m in members:
                result[keys[m]] = {"group": f"g{root}", "label": label or items[root].get("name", "")}
    return result
