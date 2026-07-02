# Bardo v0.4.1-alpha

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
- **Espectrograma** — visualización en la barra de progreso generada con TarsosDSP y almacenada en caché en disco; se muestra como forma de onda con picos cuyo color sigue el acento de la canción en reproducción
- **Pantalla de carga animada** — overlay de inicio con animación de entrada de las piezas del icono desde las esquinas de la pantalla; se mantiene hasta que todas las tareas asíncronas han finalizado
- **Easter egg de icono** — el icono del sidebar tiene una animación oculta activable
- **Interfaz sin cromo nativo** — ventana completamente personalizada, redimensionable y con soporte para maximizar/pantalla completa
- **Icono de aplicación** — aparece en la barra de tareas de Windows, Alt+Tab y previsualización de ventana
- **Versión visible** — el título de la barra muestra `Bardo vX.Y.Z`; la versión se lee de `app.properties`, generado por Maven en tiempo de build
- **Persistencia automática** — la biblioteca y la configuración se guardan en disco sin intervención del usuario
- **Miniaturas 16:9** — tanto en el mini-reproductor inferior (78×44 px) como en el panel expandido (320×180 px), las portadas se muestran con la proporción de las miniaturas de YouTube
- **Colores dinámicos** — los colores de acento y del reproductor siguen automáticamente los colores dominantes extraídos de la miniatura de la canción en reproducción (extracción por cuantización de tono con 18 cubos de 20°)
- **Tema completamente personalizable** — todos los colores de la interfaz se configuran desde *Configuración → Apariencia*; cada variable tiene un selector de modo (estático / color primario de canción / color secundario de canción) y un botón de reset individual al color por defecto
- **Contraste de texto automático (WCAG)** — cuando el ratio de contraste texto/fondo cae por debajo del umbral 3.0, se aplica una sombra suave alrededor del texto para garantizar la legibilidad con cualquier combinación de colores
- **Contador de cuota YouTube API** — barra de progreso en la pantalla de inicio que muestra el consumo diario estimado de unidades (búsqueda = 100 unidades, resto = 1). Al alcanzar el límite: notificación toast, bloqueo de llamadas API y cuenta atrás hasta la medianoche (hora del Pacífico). El límite (por defecto 1 000 unidades/día) es configurable desde *Configuración*; las claves de desarrollador tienen el límite forzado a 1 000 y no pueden desactivarlo
- **Carpeta de música configurable** — desde *Configuración* puedes cambiar dónde se guardan las canciones descargadas; los archivos existentes se migran automáticamente a la nueva ubicación
- **Gestión de descargas** — pantalla "Ver descargas" con búsqueda, orden (por fecha/tamaño) y filtro por playlist; permite eliminar canciones descargadas para liberar espacio. Optimizada para bibliotecas grandes: carga los tamaños en segundo plano y usa una lista virtualizada
- **Delimitadores de bucle (A/B)** — arrastra dos marcadores sobre la barra de progreso/espectrograma de una canción para repetir en bucle solo ese fragmento

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
| Análisis de audio | TarsosDSP (FFT, espectrograma) |
| Conversión de audio | JAVE2 (wrapper de FFmpeg) |
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
│   ├── App.java                          # Punto de entrada JavaFX (stage, icono, título)
│   ├── Launcher.java                     # Wrapper para el fat JAR (evita restricción de JavaFX)
│   ├── controllers/
│   │   ├── MainController.java           # Coordinador principal
│   │   ├── ThemeManager.java             # Estado y lógica de temas y colores dinámicos
│   │   ├── SettingsPanelBuilder.java     # Panel de Configuración (incluye sección de cuota)
│   │   ├── PlayerInstance.java           # Estado de un reproductor activo
│   │   ├── PlayerPanelBuilder.java       # Panel completo del reproductor
│   │   ├── MashupPanelBuilder.java       # Panel del reproductor Mashup
│   │   ├── GroupDetailBuilder.java       # Vista de detalle de una playlist
│   │   ├── CardBuilder.java              # Tarjetas de inicio y búsqueda
│   │   ├── DownloadDialogs.java          # Diálogos de descarga masiva
│   │   ├── LoadingOverlay.java           # Overlay de carga animado en el arranque
│   │   ├── SpectrogramPanelBuilder.java  # Renderizador de espectrograma sobre el slider
│   │   ├── AppTab.java                   # Datos de una pestaña
│   │   ├── ResizeHelper.java             # Redimensionado de ventana UNDECORATED
│   │   └── UIUtils.java                  # Utilidades (formato, CSS, navegador)
│   ├── models/
│   │   ├── Song.java                     # Canción (YouTube o local)
│   │   ├── LibraryGroup.java             # Colección de canciones (playlist)
│   │   └── YouTubePlaylistInfo.java      # Metadatos de playlist de YouTube
│   └── services/
│       ├── ConfigLoader.java             # Carga config.properties y app.properties
│       ├── YouTubeService.java           # Búsqueda y playlists via YouTube API
│       ├── YouTubeQuotaTracker.java      # Estimación y límite de cuota diaria de la API
│       ├── DownloadService.java          # Descarga de audio (yt-dlp)
│       ├── SpectrogramService.java       # Cómputo y caché de espectrogramas (TarsosDSP)
│       ├── LibraryService.java           # Singleton de biblioteca en memoria
│       └── PersistenceService.java       # Serialización JSON a disco + ruta base de datos
└── resources/com/musicplayer/
    ├── views/main.fxml                   # Layout principal
    ├── styles/main.css                   # Hoja de estilos
    ├── config.properties                 # Configuración (sin secretos)
    ├── app.properties                    # Versión inyectada por Maven en build
    ├── icons/
    │   ├── icon.png                      # Icono del sidebar (capa base, coloreable)
    │   ├── icon_filter.png               # Capa intermedia del icono del sidebar
    │   ├── icon_border.png               # Capa superior del icono del sidebar (bordes)
    │   ├── icon_full.png                 # Icono de aplicación (taskbar, Alt+Tab)
    │   ├── icon_full1.png                # Pieza 1 del icono para la animación de carga
    │   ├── icon_full2.png                # Pieza 2 del icono para la animación de carga
    │   └── icon_full3.png                # Pieza 3 del icono para la animación de carga
    └── bin/yt-dlp.exe                    # Binario de descarga (solo Windows)
```

---

## Gestión de versiones

La versión se define **únicamente** en `<version>` de `pom.xml`. Maven la inyecta en `app.properties` al compilar, y `ConfigLoader.getVersion()` la expone al resto de la app. Para actualizar la versión basta con cambiar `pom.xml` y recompilar.

---

## Datos de usuario

La biblioteca, la configuración y la caché de espectrogramas se guardan automáticamente en:

- **Windows:** `%APPDATA%\Bardo\`
- **Linux/macOS:** `~/.bardo/`

| Archivo / Carpeta | Contenido |
|---|---|
| `library.json` | Grupos, canciones y canciones pineadas |
| `settings.json` | Volumen, ducking, clave de API y tema |
| `quota.json` | Consumo diario de unidades de la YouTube API |
| `audio/` | Archivos de audio descargados por grupo |
| `spectrograms/` | Espectrogramas precalculados (formato `.spg`) |
| `bin/yt-dlp.exe` | Binario extraído del JAR en el primer uso |

Estos directorios no forman parte del repositorio.

---

## Licencia

MIT © 2026
