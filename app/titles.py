"""Nombres legibles a partir del nombre técnico de la release (igual que en la app Android)."""
from __future__ import annotations

import re

from .duplicates import parse_name

_QUALITY = [
    (re.compile(r"\b(2160p|4k|uhd)\b", re.I), "4K"),
    (re.compile(r"\b1080[pi]\b", re.I), "1080p"),
    (re.compile(r"\b720p\b", re.I), "720p"),
    (re.compile(r"\b(480p|576p|dvdrip)\b", re.I), "SD"),
]
_SOURCE = [
    (re.compile(r"\bremux\b", re.I), "Remux"),
    (re.compile(r"\b(bluray|blu-ray|bdrip|brrip)\b", re.I), "BluRay"),
    (re.compile(r"\bweb[ ._-]?(dl|rip)?\b", re.I), "WEB"),
    (re.compile(r"\bhdtv\b", re.I), "HDTV"),
]
_SMALL = {"de", "del", "la", "el", "los", "las", "y", "of", "the", "and", "a", "en", "to"}
_EPISODE_RE = re.compile(r"[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})")
_X_RE = re.compile(r"\b(\d{1,2})x(\d{2,3})\b")


def _title_case(t: str) -> str:
    words = [w for w in t.split(" ") if w]
    return " ".join(w if i > 0 and w in _SMALL else w[:1].upper() + w[1:] for i, w in enumerate(words))


def clean(name: str) -> str:
    """«Oppenheimer (2023)», «The Bear · T3», «Shogun · T1 E1»."""
    p = parse_name(name or "")
    if len(p.title) < 2:
        return name
    out = _title_case(p.title)
    if p.is_series:
        seasons = sorted(p.seasons)
        out += f" · T{seasons[0]}–T{seasons[-1]}" if len(seasons) > 1 else f" · T{seasons[0]}"
        if p.episode is not None:
            out += f" E{p.episode}"
    elif p.year:
        out += f" ({p.year})"
    return out


def quality(name: str) -> str:
    """«4K HDR», «1080p BluRay», «VOSE»… (vacío si no se reconoce nada)."""
    parts = []
    q = next((label for rx, label in _QUALITY if rx.search(name or "")), None)
    if q:
        parts.append(q)
    if re.search(r"\bhdr(10)?\b|\bdv\b", name or "", re.I):
        parts.append("HDR")
    s = next((label for rx, label in _SOURCE if rx.search(name or "")), None)
    if s:
        parts.append(s)
    if re.search(r"\bvose\b", name or "", re.I):
        parts.append("VOSE")
    return " ".join(parts)


def episode_label(file_name: str) -> str:
    """«T3 E2» si el nombre lo indica; si no, el nombre del archivo sin extensión."""
    base = (file_name or "").rsplit("/", 1)[-1]
    m = _EPISODE_RE.search(base) or _X_RE.search(base)
    if m:
        return f"T{int(m.group(1))} E{int(m.group(2))}"
    return base.rsplit(".", 1)[0] if "." in base else base
