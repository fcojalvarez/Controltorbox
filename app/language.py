"""Detección del idioma del audio.

Dos niveles:
1. Heurística por nombre de archivo/carpeta (rápida, sin descargar nada).
2. Análisis real de las pistas de audio con ffprobe sobre el enlace de
   descarga de TorBox (solo lee las cabeceras del contenedor).
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ES = "es"            # tiene audio en español
NO_ES = "no_es"      # no tiene audio en español
UNKNOWN = "unknown"  # no se puede saber sin analizar

VIDEO_EXTS = {
    ".mkv", ".mp4", ".avi", ".m4v", ".mov", ".wmv", ".ts", ".m2ts",
    ".mpg", ".mpeg", ".webm", ".flv", ".vob", ".iso",
}

# Marcas que indican audio en español en el nombre de la release.
_ES_PATTERNS = [
    r"spanish", r"espa[nñ]ol", r"castellano", r"latino", r"latam",
    r"\bspa\b", r"\besp\b", r"\bcast\b",
]
# Marcas de "subtítulos en español" que NO implican audio en español.
_SUBS_RE = re.compile(
    r"\b(?:subs?|subtitulos?|subtitles?|subbed)\s+(?:en\s+)?(?:spa|esp|spanish|espa[nñ]ol|castellano)\b",
    re.IGNORECASE,
)
# Marcas que indican que el audio NO está en español (versión original subtitulada).
_NO_ES_RE = re.compile(r"\bvose\b|\bvos\b|\bvo\b", re.IGNORECASE)
_ES_RE = re.compile("|".join(_ES_PATTERNS), re.IGNORECASE)

SPANISH_CODES = {"spa", "es", "esp", "spanish", "español", "espanol", "castellano", "latino", "es-es", "es-419", "es-mx"}
UNDEFINED_CODES = {"", "und", "unk", "unknown", "mis", "zxx", "mul"}


def _normalize(name: str) -> str:
    # Separadores habituales de releases -> espacios para que funcione \b
    return re.sub(r"[._\[\]()\-+]+", " ", name)


def guess_from_name(*names: str) -> str:
    """Clasifica según los nombres (release y/o archivo)."""
    text = " ".join(_normalize(n or "") for n in names)
    text = _SUBS_RE.sub(" ", text)  # "subs esp" no cuenta como audio
    if _ES_RE.search(text):
        return ES
    if _NO_ES_RE.search(text):
        return NO_ES
    return UNKNOWN


def is_video(file_name: str) -> bool:
    name = (file_name or "").lower()
    return any(name.endswith(ext) for ext in VIDEO_EXTS)


def pick_main_video(files: list[dict]) -> dict | None:
    """Devuelve el archivo de vídeo más grande (el que suele importar)."""
    videos = [f for f in files or [] if is_video(f.get("name") or f.get("short_name") or "")]
    if not videos:
        return None
    return max(videos, key=lambda f: f.get("size") or 0)


def classify_audio_languages(languages: list[str]) -> str:
    """Clasifica a partir de las etiquetas de idioma de las pistas de audio."""
    if not languages:
        return UNKNOWN
    norm = [(lang or "").strip().lower() for lang in languages]
    if any(l in SPANISH_CODES or l.startswith("es-") for l in norm):
        return ES
    if all(l in UNDEFINED_CODES for l in norm):
        return UNKNOWN
    return NO_ES


def _exe_name() -> str:
    return "ffprobe.exe" if os.name == "nt" else "ffprobe"


def _candidate_paths() -> list[Path]:
    """Rutas típicas donde los instaladores dejan ffprobe."""
    home = Path.home()
    cands: list[Path] = []
    if os.name == "nt":
        local = Path(os.environ.get("LOCALAPPDATA", home / "AppData" / "Local"))
        cands += [
            local / "Microsoft" / "WinGet" / "Links" / "ffprobe.exe",
            Path("C:/ffmpeg/bin/ffprobe.exe"),
            Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "ffmpeg" / "bin" / "ffprobe.exe",
            Path(os.environ.get("ProgramData", "C:/ProgramData")) / "chocolatey" / "bin" / "ffprobe.exe",
            home / "scoop" / "shims" / "ffprobe.exe",
        ]
        # winget (Gyan.FFmpeg) sin el enlace en PATH
        packages = local / "Microsoft" / "WinGet" / "Packages"
        if packages.is_dir():
            cands += sorted(packages.glob("*FFmpeg*/*/bin/ffprobe.exe"), reverse=True)
    else:
        cands += [Path(p) / "ffprobe" for p in (
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin", str(home / ".local" / "bin"),
        )]
    return cands


def normalize_ffprobe_path(path: str) -> Path:
    """Acepta la ruta al ejecutable o a la carpeta que lo contiene."""
    p = Path(path.strip().strip('"').strip("'")).expanduser()
    if p.is_dir():
        p = p / _exe_name()
    return p


def check_ffprobe(path: str | Path) -> str:
    """Ejecuta `ffprobe -version`; devuelve la primera línea o lanza error."""
    try:
        proc = subprocess.run([str(path), "-version"], capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError(f"No se pudo ejecutar {path}: {exc}") from exc
    if proc.returncode != 0:
        raise RuntimeError(f"{path} no parece ffprobe: {proc.stderr.strip()[:200]}")
    return (proc.stdout.splitlines() or [""])[0]


def find_ffprobe(custom: str | None = None) -> str | None:
    """Localiza ffprobe: ruta configurada, FFPROBE_PATH, PATH o rutas típicas."""
    for path in (custom, os.environ.get("FFPROBE_PATH")):
        if path:
            p = normalize_ffprobe_path(path)
            if p.is_file():
                return str(p)
    found = shutil.which("ffprobe")
    if found:
        return found
    for p in _candidate_paths():
        if p.is_file():
            return str(p)
    return None


def ffprobe_available(custom: str | None = None) -> bool:
    return find_ffprobe(custom) is not None


def probe_audio_languages(url: str, ffprobe: str = "ffprobe", timeout: int = 60) -> list[str]:
    """Usa ffprobe para leer las pistas de audio de una URL remota.

    Si una pista no tiene etiqueta de idioma pero su título menciona
    el idioma (p.ej. "Castellano AC3 5.1"), se usa el título.
    """
    cmd = [
        ffprobe, "-v", "error",
        "-select_streams", "a",
        "-show_entries", "stream=index:stream_tags=language,title",
        "-of", "json", url,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "ffprobe falló")
    data = json.loads(proc.stdout or "{}")
    langs: list[str] = []
    for stream in data.get("streams", []):
        tags = {k.lower(): v for k, v in (stream.get("tags") or {}).items()}
        lang = (tags.get("language") or "").strip()
        title = tags.get("title") or ""
        if (not lang or lang.lower() in UNDEFINED_CODES) and title:
            if guess_from_name(title) == ES:
                lang = "spa"
        langs.append(lang or "und")
    return langs
