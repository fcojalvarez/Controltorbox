# Controltorbox

Aplicación web local para gestionar el almacenamiento de [TorBox](https://torbox.app):
lista tus torrents, descargas Usenet y descargas web, detecta cuáles **no tienen
audio en español** y te deja eliminarlos en bloque para liberar espacio.

## Funciones

- Lista todo lo que tienes en TorBox (torrents, Usenet y web) ordenado por tamaño.
- Filtros por idioma del audio, tipo, nombre y "solo vídeo".
- Ordenar por tamaño, fecha en que se añadió (más recientes o más antiguos) o nombre.
  La app recuerda el orden elegido.
- Ver los archivos de cada torrent o descarga (desplegable «N archivos»), con su tamaño.
  TorBox no permite borrar archivos sueltos de un torrent: al eliminar se borra el
  elemento completo.
- Detección del idioma del audio en dos niveles:
  1. **Por nombre** (instantáneo): busca marcas como `Castellano`, `Spanish`,
     `Latino`, `SPA`, `ESP`… y `VOSE`/`VOS` (versión original subtitulada = sin audio
     en español). Si no hay ninguna marca queda como **Desconocido**.
  2. **Análisis real con ffmpeg** (botón *Analizar audio*): pide a TorBox el enlace del
     vídeo principal y lee sólo las cabeceras del archivo para obtener las pistas de
     audio reales (`eng`, `spa`, …). No descarga el archivo completo. El resultado se
     guarda en `data/analysis.json` para no repetirlo.
- **Escuchar el audio** (botón *▶ Escuchar* en cada fila): muestra las pistas de audio
  del vídeo (idioma, códec, canales y título) y reproduce un fragmento de 15, 30 o 60 s
  de la pista que elijas, desde el punto que quieras. ffmpeg recorta solo ese trozo
  directamente desde TorBox y lo convierte a MP3, así que funciona con MKV, AC3, DTS,
  E-AC3… aunque el navegador no los soporte.
- **Marcar a mano** tras escucharlo: *Sí, tiene español* / *No tiene español* /
  *Automático*. La marca manual tiene prioridad sobre la detección automática y se
  conserva aunque vuelvas a analizar.
- Selección múltiple, total de espacio a liberar y eliminación con confirmación.

> El filtro por defecto es **"Sin español (incluye desconocido)"**. Muchos
> archivos en inglés no indican el idioma en el nombre, así que antes de borrar en
> bloque conviene seleccionar los *Desconocido* y pulsar **Analizar audio**. Los que
> resulten tener español se deseleccionan solos.

## Puesta en marcha

Arranca la app (abajo) y abre http://localhost:8000. La primera vez se abre
**⚙ Ajustes**: pega tu API token de TorBox (torbox.app → *Settings* → *API Key*) y
pulsa **Guardar**. La app comprueba que el token funciona y lo guarda en el
`localStorage` de tu navegador, así que **no tendrás que volver a ponerlo al
actualizar la app**. Desde Ajustes puedes cambiarlo o borrarlo cuando quieras.

> El `localStorage` va por dirección: si abres la app en `http://localhost:8000` y
> otro día en `http://127.0.0.1:8000`, o en otro navegador, te pedirá el token otra vez.

> Alternativa: también puedes definir `TORBOX_API_KEY` en un archivo `.env`
> (ver `.env.example`). Si hay un token guardado en el navegador, tiene prioridad.

### Con Python (3.10+)

```bash
pip install -r requirements.txt
uvicorn app.main:app --port 8000
```

#### ffmpeg (no hay que instalar nada aparte)

El análisis y la escucha del audio usan ffmpeg, que **se instala automáticamente** con
`pip install -r requirements.txt` (paquete `imageio-ffmpeg`, que trae ffmpeg ya
compilado para Windows, macOS y Linux).

Si ya tienes ffmpeg/ffprobe instalados en el sistema, la app los detecta y los usa
en su lugar.

### Con Docker (incluye ffmpeg)

```bash
docker build -t controltorbox .
docker run --rm -p 127.0.0.1:8000:8000 -v "$PWD/data:/data" controltorbox
```

## Seguridad

- El token se guarda en el `localStorage` de tu navegador y viaja solo entre tu
  navegador y la app local (cabecera `X-Torbox-Token`). El servidor no lo guarda.
- Por defecto `uvicorn` escucha en `127.0.0.1`. No expongas la app a Internet: no
  tiene autenticación y permite borrar contenido de tu cuenta.
- Eliminar en TorBox es **irreversible**.

## Problemas frecuentes

- **La app pide el token otra vez**: comprueba que abres la misma dirección
  (`localhost` y `127.0.0.1` cuentan como sitios distintos) y que no estás en modo
  incógnito.
- **Carpeta de datos**: los análisis se guardan en `data/` dentro del proyecto (o en
  `~/.controltorbox` si no se puede escribir ahí). Puedes cambiarla con
  `CONTROLTORBOX_DATA_DIR=/ruta`.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```
