# Contribuir a Harmony

Gracias por tu interés en mejorar Harmony. Las contribuciones deben ser claras,
reproducibles y compatibles con la licencia GPL-3.0 del proyecto.

## Antes de empezar

- Busca issues y pull requests existentes antes de abrir uno nuevo.
- Para cambios grandes, abre primero un issue para acordar el enfoque.
- No publiques vulnerabilidades ni credenciales en issues o pull requests. Consulta [SECURITY.md](SECURITY.md).

## Preparar el entorno

Se necesita JDK 21, Android SDK compileSdk 36, NDK/CMake y Git con soporte de
submódulos.

```bash
git clone --recurse-submodules <URL_DEL_REPOSITORIO>
cd <directorio-del-repositorio>
```

Para compilar y probar la variante principal:

```bash
./gradlew assembleCoreDebug
./gradlew lintCoreDebug testCoreDebugUnitTest
```

La variante `full` requiere las bibliotecas precompiladas de FFmpeg. Consulta
la sección de compilación del [README](README.md) si necesitas validarla.

## Flujo de trabajo

1. Crea una rama desde `dev`:

   ```bash
   git switch dev
   git pull --ff-only
   git switch -c tipo/descripcion-corta
   ```

2. Usa nombres de rama como `feat/`, `fix/`, `docs/`, `refactor/` o `chore/`.
3. Haz cambios pequeños y enfocados en un solo objetivo.
4. Añade o actualiza tests cuando cambie el comportamiento.
5. Ejecuta las comprobaciones relevantes antes de abrir la pull request.
6. Abre la pull request contra `dev`, salvo que los mantenedores indiquen otra rama.

## Mensajes de commit

Harmony usa [Conventional Commits](https://www.conventionalcommits.org/es/v1.0.0/):

```text
<tipo>(alcance opcional)!: <descripción>
```

El alcance y `!` son opcionales; no escribas los paréntesis ni el signo si no
los necesitas.

Tipos habituales:

| Tipo | Uso |
|---|---|
| `feat` | Nueva funcionalidad |
| `fix` | Corrección de un error |
| `docs` | Documentación |
| `refactor` | Cambio interno sin cambiar el comportamiento |
| `perf` | Mejora de rendimiento |
| `test` | Tests |
| `build` | Sistema de compilación o dependencias |
| `ci` | Integración continua |
| `chore` | Mantenimiento |
| `style` | Formato sin cambio de lógica |

Ejemplos:

```text
feat(player): añade control de velocidad
fix(music-service): corrige el fallback de reproducción
docs: actualiza las instrucciones de compilación
ci: ejecuta los tests de la variante core
```

Usa una descripción breve, específica y separada del cuerpo por una línea en
blanco. Para cambios incompatibles, añade `!` o un pie `BREAKING CHANGE:`.
Mantén un commit por cambio lógico cuando sea posible. No es necesario reescribir
commits anteriores de este repositorio para adoptar esta convención.

## Calidad y validación

La comprobación equivalente a CI es:

```bash
./gradlew assembleCoreDebug lintCoreDebug testCoreDebugUnitTest --stacktrace -DskipFormatKtlint
```

Para cambios solo de documentación, como mínimo ejecuta:

```bash
git diff --check
```

Si una comprobación no puede ejecutarse por falta de hardware, SDK, FFmpeg u
otra limitación, descríbelo en la pull request.

## Pull requests

Una pull request debe:

- Explicar qué cambia y por qué.
- Enlazar el issue relacionado, si existe.
- Incluir capturas o vídeo cuando cambie la interfaz.
- Indicar los comandos de validación ejecutados.
- Mantenerse enfocada en un único objetivo.
- No incluir secretos, archivos generados ni cambios no relacionados.
- Mencionar el uso sustancial de IA cuando corresponda.

Los mantenedores pueden solicitar cambios, combinar commits o ajustar el título
antes de integrar la pull request.

## Archivos sensibles

Nunca subas `keystore.properties`, archivos `*.jks`, `*.keystore`,
`local.properties`, `google-services.json`, tokens, contraseñas ni modelos locales.
El archivo `.gitignore` ya excluye varios de estos archivos, pero revisa siempre
`git diff` antes de hacer commit.

## Licencia

Las contribuciones se distribuyen bajo la [GPL-3.0](LICENSE), salvo que se acuerde
otra cosa de forma explícita.
