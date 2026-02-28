<div align="center">

<img src="assets/Harmony.webp" width="120" alt="Harmony logo"/>

# Harmony

**Reproductor de música local + cliente de YouTube Music en una sola app para Android.**  
Basado en [InnerTune](https://github.com/z-huang/InnerTune) y el ecosistema de [OuterTune](https://github.com/OuterTune/OuterTune).

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen?style=flat-square)
![compileSdk](https://img.shields.io/badge/compileSdk-36-blue?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-purple?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--3.0-red?style=flat-square)

<img src="assets/main-interface.jpg" width="720" alt="Harmony main interface"/>

</div>

---

## Galería

<div align="center">

### Móvil

| Player | Homepage | Library |
|:---:|:---:|:---:|
| <img src="assets/gallery/player.png" width="200" alt="Player"/> | <img src="assets/gallery/homepage.png" width="200" alt="Homepage"/> | <img src="assets/gallery/library.png" width="200" alt="Library"/> |

| Queue (collapsed) | Queue (expanded) | Library songs |
|:---:|:---:|:---:|
| <img src="assets/gallery/queue_collapsed.png" width="200" alt="Queue collapsed"/> | <img src="assets/gallery/queue_expanded.png" width="200" alt="Queue expanded"/> | <img src="assets/gallery/library_songs.png" width="200" alt="Library songs"/> |

| Folders | Artist | Lyrics |
|:---:|:---:|:---:|
| <img src="assets/gallery/folders.png" width="200" alt="Folders"/> | <img src="assets/gallery/artist_page.png" width="200" alt="Artist page"/> | <img src="assets/gallery/lyrics.png" width="200" alt="Lyrics"/> |

| AI Chatbot | Settings |
|:---:|:---:|
| <img src="assets/gallery/chatbot.png" width="200" alt="Chatbot"/> | <img src="assets/gallery/settings.png" width="200" alt="Settings"/> |

### Tablet

| Light | Dark |
|:---:|:---:|
| <img src="assets/gallery/tablet_queue_home_light.png" width="380" alt="Tablet light"/> | <img src="assets/gallery/tablet_lyrics_library_dark.png" width="380" alt="Tablet dark"/> |

</div>

---

## Características

- 🎵 **Música local** — MP3, FLAC, OGG y más.
- 📺 **YouTube Music** — reproducción en segundo plano y descargas.
- 📚 **Biblioteca unificada** — contenido local y online en una sola vista.
- 🔄 **Sincronización YTM** — vincula tu cuenta de YouTube Music.
- 🎤 **Letras sincronizadas** — LRC / TTML / SRT con modo karaoke palabra por palabra.
- 🎛️ **Múltiples colas de reproducción.**
- 🚗 **Android Auto** — integración nativa.
- 🏷️ **Metadatos mejorados** — TagLib / FFmpeg / MediaStore según configuración.
- 🤖 **Modo AI experimental** — LLM local vía `llama.cpp` (modelo: Qwen2-500m).

---

## Requisitos

| Componente | Versión |
|---|---|
| Android | 8.0+ (minSdk 24) |
| JDK | 21 |
| Android SDK | compileSdk 36 |
| NDK + CMake | requerido (código nativo) |
| Git | con soporte de submódulos |

---

## Instalación

Descarga el APK desde la sección **[Releases](../../releases)** del repositorio.

> La variante `full` incluye el extractor FFmpeg para metadatos avanzados.

---

## Compilación local

### 1. Clonar el repositorio

```bash
git clone --recurse-submodules <URL_DEL_REPOSITORIO>
cd Harmony_OuterTune
```

### 2. Variante `core`

```bash
# Debug
./gradlew assembleCoreDebug

# Lint + tests (igual que CI)
./gradlew lintCoreDebug testCoreDebugUnitTest

# Release
./gradlew assembleCoreRelease
```

### 3. Variante `full` (requiere FFmpeg precompilado)

```bash
git clone https://github.com/mikooomich/ffmpeg-android-maker-prebuilt/ \
  -b audio ffMetadataEx/ffmpeg-android-maker

./gradlew assembleFullDebug
./gradlew assembleFullRelease
```

---

## Variantes

| Variante | Descripción |
|---|---|
| `core` | Build por defecto, más ligero. |
| `full` | Añade extractor FFmpeg y componentes avanzados. |

| Tipo de build | Descripción |
|---|---|
| `debug` | Sin optimizaciones, con logs. |
| `userdebug` | Similar a release pero sin minificación. |
| `release` | Optimizado y minificado. |

---

## Estructura de módulos

| Módulo | Descripción |
|---|---|
| `app` | Aplicación principal: UI (Compose), reproducción, Room, configuración. |
| `innertube` | Cliente/API para YouTube Music. |
| `kugou` | Integración de letras vía KuGou. |
| `lrclib` | Integración de letras vía LrcLib. |
| `ffMetadataEx` | Integración nativa NDK/CMake con FFmpeg para metadatos. |
| `taglib` | Parsing de metadatos de audio (C++ / JNI). |
| `material-color-utilities` | Utilidades de color Material You. |

---

## Tecnologías

`Kotlin` · `Jetpack Compose` · `Media3 / ExoPlayer` · `Room` · `Hilt` · `Ktor` · `NDK / CMake` · `llama.cpp` · `FFmpeg` · `TagLib`

---

## Créditos

Basado en ideas y trabajo de **InnerTune** y el ecosistema de **OuterTune**.

---

## Licencia

Este proyecto se distribuye bajo licencia **GPL-3.0**. Consulta [LICENSE](LICENSE).
