# AGENTS.md

Estas instrucciones se aplican a todo el repositorio. Si una carpeta contiene
otro `AGENTS.md`, sus instrucciones son más específicas para esa carpeta.

## Proyecto

Harmony es una aplicación Android de música local y YouTube Music. El proyecto
usa Kotlin, Jetpack Compose, Media3, Room, Hilt, NDK/CMake y varios submódulos de
Git.

## Reglas de trabajo

- Lee el código y la documentación relacionada antes de editar.
- Mantén los cambios pequeños y centrados en la tarea solicitada.
- Reutiliza patrones y utilidades existentes antes de crear otros nuevos.
- No añadas dependencias, abstracciones o configuraciones sin una necesidad concreta.
- No edites archivos generados ni código de terceros salvo que la tarea lo pida.
- No ejecutes `git reset --hard`, `git checkout --` ni otros comandos destructivos.
- No hagas `commit`, `push` o cambios de historial salvo que se soliciten explícitamente.
- Nunca leas, copies o incluyas credenciales, keystores, tokens o archivos locales.

## Entorno y comandos

Requisitos principales: JDK 21, Android SDK compileSdk 36, NDK/CMake y Git con
submódulos.

```bash
git clone --recurse-submodules <URL_DEL_REPOSITORIO>
./gradlew assembleCoreDebug
./gradlew lintCoreDebug testCoreDebugUnitTest
```

La validación que ejecuta CI para la variante `core` es:

```bash
./gradlew assembleCoreDebug lintCoreDebug testCoreDebugUnitTest --stacktrace -DskipFormatKtlint
```

La variante `full` requiere descargar las bibliotecas precompiladas de FFmpeg.
No la uses para cambios normales si `core` cubre la modificación.

## Validación

- Cambios de Kotlin, Compose o la aplicación: ejecuta lint y tests unitarios de `core`.
- Cambios de módulos: ejecuta al menos los tests del módulo afectado y la validación de `core` cuando corresponda.
- Cambios nativos: comprueba también la variante o tarea nativa afectada.
- Cambios de documentación: ejecuta `git diff --check` y revisa los enlaces modificados.
- Si no puedes ejecutar una comprobación, indícalo junto con el motivo; no inventes resultados.

## Git y pull requests

- Usa una rama descriptiva basada en `dev`, por ejemplo `feat/music-recognition` o `fix/player-crash`.
- Sigue las reglas de `CONTRIBUTING.md` para commits y pull requests.
- Mantén un commit por cambio lógico cuando sea posible.
- Incluye capturas para cambios visibles de UI y enlaza el issue relacionado.

## Uso de IA

- El código generado por IA debe revisarse, entenderse y probarse antes de proponerlo.
- La persona que abre la pull request es responsable del código y de sus licencias.
- Si la IA generó una parte sustancial o compleja, indícalo en la pull request.
- No presentes como ejecutados comandos o tests que no se hayan ejecutado.
- No envíes secretos ni datos privados a herramientas de IA.

El subárbol `app/src/main/llama.cpp` contiene su propio `AGENTS.md`; léelo antes
de modificarlo y respeta sus reglas específicas.
