# Kompaser

App Android que muestra los acordes de una canción compás a compás (el actual y los dos siguientes, con su digitación) para tocar sin tocar el móvil.

## Compilar e instalar
    ./build.sh --install      # genera kompaser-<versión>.apk e instala por adb

## Servidor (Postgres + PostgREST)
    cd server && sudo docker compose up -d
    adb reverse tcp:3000 tcp:3000     # móvil por USB → http://localhost:3000
En la app: icono de servidor → URL → sincronizar. Desde la wifi usa http://<ip-del-pc>:3000.

## Uso
- Importar: pega un enlace de Ultimate Guitar (o compártelo desde el navegador/app de UG a Kompaser).
- Cada acorde dura 1 compás por defecto; ajústalo en Editar (lápiz).
- Vídeo: pon la URL de YouTube en Editar; en el reproductor pulsa «Empieza aquí» cuando suene el primer acorde.

## Calcular los tiempos desde el vídeo (analyzer/)
Descarga el audio del vídeo de YouTube, detecta los pulsos y alinea los acordes de la partitura con lo que suena:
duración de cada acorde, pausas (en los [Riff]/[Solo]/tablaturas), BPM y segundo del primer acorde.
Necesita el servidor en marcha; el móvil recibe el resultado al sincronizar.

    analyzer/analyze.sh --list                 # canciones del servidor
    analyzer/analyze.sh "Mama Said" --dry-run  # ver el resultado sin guardar
    analyzer/analyze.sh "Mama Said"            # guardar en Postgres
    analyzer/analyze.sh --all                  # todas las que tienen vídeo

Opciones: `--half`/`--double` si el tempo sale al doble/mitad, `--grid 1` para no redondear a medio compás,
`--youtube <id>` para usar otro vídeo. Sobrescribe los tiempos de la canción: revisa con `--dry-run` si los habías editado a mano.
