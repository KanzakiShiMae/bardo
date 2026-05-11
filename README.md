# ✦ Bardo

Reproductor de música de escritorio construido con JavaFX 21. Combina biblioteca local, reproducción multicanal simultánea, integración con YouTube y un modo Mashup para mezclar dos canciones en tiempo real.

---

## Características

- **Biblioteca local** — importa carpetas con archivos MP3, WAV, OGG, M4A, AAC, FLAC, AIF/AIFF
- **Búsqueda en YouTube** — busca canciones y descarga el audio automáticamente via yt-dlp
- **Importar playlists de YouTube** — sincroniza una playlist pública completa a la biblioteca
- **Multi-reproductor** — reproduce varias canciones a la vez, cada una con su propia pestaña y panel
- **Tipos de playlist**:
  - *Música* — reproducción normal
  - *Ambiente* — se duckea automáticamente cuando hay otra canción activa (volumen configurable)
  - *Mashup* — selecciona dos canciones y reprodúcelas simultáneamente con sliders independientes y crossfade animado
- **Barra de búsqueda** en cada playlist (insensible a mayúsculas y tildes)
- **Interfaz sin cromo nativo** — ventana completamente personalizada, redimensionable y con soporte para maximizar/pantalla completa
- **Persistencia automática** — la biblioteca y la configuración se guardan en disco sin intervención del usuario

---

## Requisitos

| Componente | Versión mínima |
|---|---|
| Java (JDK) | 17 |
| Maven | 3.8 |
| yt-dlp | incluido para Windows; ver nota abajo para otros SO |

> **yt-dlp en Linux/macOS:** instala yt-dlp y asegúrate de que esté en el `PATH`. El binario incluido en `src/main/resources/com/musicplayer/bin/` es solo para Windows.

---

## Configuración inicial

### 1. Clonar el repositorio

```bash
git clone https://github.com/tu-usuario/bardo.git
cd bardo
```

### 2. Obtener una clave de API de YouTube

Las funciones de YouTube requieren una clave de la **YouTube Data API v3**:

1. Ve a [Google Cloud Console](https://console.cloud.google.com/)
2. Crea un proyecto (o selecciona uno existente)
3. En *APIs y servicios → Biblioteca*, habilita **YouTube Data API v3**
4. En *APIs y servicios → Credenciales*, crea una **Clave de API**
5. Copia la clave

> Sin esta clave la aplicación funciona igualmente para reproducción local; solo las funciones de YouTube quedan desactivadas.

### 3. Ejecutar la aplicación

```bash
mvn clean javafx:run
```

Al arrancar por primera vez (sin clave configurada) aparecerá un diálogo que te redirige al panel de **Configuración**, donde puedes introducir tu clave de API. Los cambios se aplican al reiniciar.

---

## Atajos de teclado

| Tecla | Acción |
|---|---|
| `Space` | Play / Pausa |
| `←` / `→` | Retroceder / Avanzar 5 s |
| `↑` / `↓` | Subir / Bajar volumen 1 % |
| `F11` | Pantalla completa |
| `Esc` | Salir de pantalla completa |

---

## Tecnologías

| Componente | Tecnología |
|---|---|
| UI | JavaFX 21 (FXML + CSS) |
| Reproducción | `javafx.scene.media.MediaPlayer` |
| API de YouTube | YouTube Data API v3 |
| HTTP | OkHttp 4 |
| JSON | Gson |
| Descarga de audio | yt-dlp |
| Build | Maven |

---

## Estructura del proyecto

```
src/main/
├── java/com/musicplayer/
│   ├── App.java                          # Punto de entrada JavaFX
│   ├── controllers/
│   │   ├── MainController.java           # Coordinador principal
│   │   ├── PlayerInstance.java           # Estado de un reproductor activo
│   │   ├── PlayerPanelBuilder.java       # Panel completo del reproductor
│   │   ├── MashupPanelBuilder.java       # Panel del reproductor Mashup
│   │   ├── GroupDetailBuilder.java       # Vista de detalle de una playlist
│   │   ├── CardBuilder.java              # Tarjetas de inicio y búsqueda
│   │   ├── DownloadDialogs.java          # Diálogos de descarga masiva
│   │   ├── AppTab.java                   # Datos de una pestaña
│   │   ├── ResizeHelper.java             # Redimensionado de ventana UNDECORATED
│   │   └── UIUtils.java                  # Utilidades (formato, CSS, navegador)
│   ├── models/
│   │   ├── Song.java                     # Canción (YouTube o local)
│   │   ├── LibraryGroup.java             # Colección de canciones (playlist)
│   │   └── YouTubePlaylistInfo.java      # Metadatos de playlist de YouTube
│   └── services/
│       ├── ConfigLoader.java             # Lee config.properties
│       ├── YouTubeService.java           # Búsqueda y playlists via YouTube API
│       ├── DownloadService.java          # Descarga de audio (yt-dlp)
│       ├── LibraryService.java           # Singleton de biblioteca en memoria
│       └── PersistenceService.java       # Serialización JSON a disco
└── resources/com/musicplayer/
    ├── views/main.fxml                   # Layout principal
    ├── styles/main.css                   # Hoja de estilos
    ├── config.properties                 # Configuración (sin secretos)
    └── bin/yt-dlp.exe                    # Binario de descarga (solo Windows)
```

---

## Datos de usuario

La biblioteca y la configuración (incluida la clave de API) se guardan automáticamente en:

- **Windows:** `%APPDATA%\Bardo\`
- **Linux/macOS:** `~/.bardo/`

Estos directorios no forman parte del repositorio.

---

## Licencia

MIT © 2026
