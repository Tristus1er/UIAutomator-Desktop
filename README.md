This is a Kotlin Multiplatform project targeting Desktop (JVM).

📖 **User documentation: [docs/TUTORIEL.md](./docs/TUTORIEL.md)** (English) ·
**[docs/TUTORIEL_FR.md](./docs/TUTORIEL_FR.md)** (français) — every feature
(capture, manual and automatic exploration, rules, graph, sessions, settings).
Screenshots live in `docs/images/`.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
      folder is the appropriate location.

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

---

### Build native installers

`jpackage` (used by Compose Desktop) only builds an installer for the OS it
runs on. To build for the current OS:

```shell
./gradlew :composeApp:packageDistributionForCurrentOS
```

Output lands in `composeApp/build/compose/binaries/main/<format>/`:
`.msi` on Windows, `.dmg` on macOS, `.deb` on Linux.

**All three platforms at once** are produced by the GitHub Actions workflow
[.github/workflows/release.yml](.github/workflows/release.yml), which runs the
build on a Windows / macOS / Linux runner matrix:

- **Tag a release** — push a tag like `v1.2.3`; the workflow builds the three
  installers (version taken from the tag) and attaches them to a GitHub
  Release.
- **Ad-hoc build** — trigger the workflow manually (`Run workflow`); the
  installers are uploaded as downloadable run artifacts.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…