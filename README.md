# Controltorbox

Aplicación web local para gestionar el almacenamiento de [TorBox](https://torbox.app):
lista tus torrents, descargas Usenet y descargas web, detecta cuáles **no tienen
audio en español** y te deja eliminarlos en bloque para liberar espacio.

## Funciones

- Lista todo lo que tienes en TorBox (torrents, Usenet y web) ordenado por tamaño.
- Filtros por idioma del audio, tipo, nombre y "solo vídeo".
- Detección del idioma del audio en dos niveles:
  1. **Por nombre** (instantáneo): busca marcas como `Castellano`, `Spanish`,
     `Latino`, `SPA`, `ESP`… y `VOSE`/`VOS` (versión original subtitulada = sin audio
     en español). Si no hay ninguna marca queda como **Desconocido**.
  2. **Análisis real con ffprobe** (botón *Analizar audio*): pide a TorBox el enlace del
     vídeo principal y lee sólo las cabeceras del archivo para obtener las pistas de
     audio reales (`eng`, `spa`, …). No descarga el archivo completo. El resultado se
     guarda en `data/analysis.json` para no repetirlo.
- Selección múltiple, total de espacio a liberar y eliminación con confirmación.

> El filtro por defecto es **"Sin español (incluye desconocido)"**. Muchos
> archivos en inglés no indican el idioma en el nombre, así que antes de borrar en
> bloque conviene seleccionar los *Desconocido* y pulsar **Analizar audio**. Los que
> resulten tener español se deseleccionan solos.

## Puesta en marcha

Arranca la app (abajo) y abre http://localhost:8000. La primera vez se abre
**⚙ Ajustes**: pega tu API token de TorBox (torbox.app → *Settings* → *API Key*) y
pulsa **Guardar**. La app comprueba que el token funciona antes de guardarlo en
`data/settings.json` (solo en tu equipo, con permisos restringidos). Desde Ajustes
puedes cambiarlo o borrarlo cuando quieras.

> Alternativa: también puedes definir `TORBOX_API_KEY` en un archivo `.env`
> (ver `.env.example`). Si hay un token guardado en Ajustes, tiene prioridad.

### Con Python (3.10+)

```bash
pip install -r requirements.txt
# opcional pero recomendado para el análisis real del audio:
#   Windows: winget install ffmpeg   ·  macOS: brew install ffmpeg  ·  Debian/Ubuntu: sudo apt install ffmpeg
uvicorn app.main:app --port 8000
```

Abre http://localhost:8000

### Con Docker (incluye ffmpeg)

```bash
docker build -t controltorbox .
docker run --rm -p 127.0.0.1:8000:8000 -v "$PWD/data:/data" controltorbox
```

## Seguridad

- El token se guarda sólo en tu equipo (`data/settings.json` o `.env`). La interfaz
  nunca lo recibe completo: solo ve los últimos 4 caracteres.
- Por defecto `uvicorn` escucha en `127.0.0.1`. No expongas la app a Internet: no
  tiene autenticación y permite borrar contenido de tu cuenta.
- Eliminar en TorBox es **irreversible**.

## Problemas frecuentes

- **Error al guardar el token**: la app guarda los ajustes en la carpeta `data/` del
  proyecto. Si no puede escribir ahí, usa `~/.controltorbox`. Puedes elegir otra
  carpeta con `CONTROLTORBOX_DATA_DIR=/ruta`. El mensaje de error en pantalla y en la
  consola de `uvicorn` indica la causa exacta.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```
