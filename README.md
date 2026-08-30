# Midas Client

The official launcher for [MidasPVP](https://midaspvp.com). Downloads and launches Minecraft
on Fabric Loader — pick a version from the dropdown on the home screen (currently **1.21.11**,
**26.1.2**, or **26.2**; each gets its own separate instance folder so worlds/configs don't mix).
These mods come pre-installed:

- **[Fabric API](https://modrinth.com/mod/fabric-api)** — required by the other two, all versions
- **Tier Tagger** — shows MidasPVP tier ranks above players in-game, and press **P** in-game to
  see your own tier in every kit (built from this org's [tiertagger](https://github.com/MidasPVP)
  repo). **1.21.11 only** for now — it hasn't been ported to 26.1.2/26.2 yet
- **[In-Game Account Switcher](https://modrinth.com/mod/in-game-account-switcher)** by VidTu —
  sign in with your real Microsoft account *from inside the game* (press Esc), so this launcher
  itself never needs to touch your Microsoft login. All versions.

## Installing

1. Grab `Midas Client-x.x.x.msi` from the [Releases page](../../releases) and run it
2. It installs to your user profile (no admin rights needed), adds a Start Menu shortcut under
   **MidasPVP**, and bundles its own Java runtime — nothing else to install
3. Open it from the Start Menu, pick a display name, hit **Play**
4. First launch downloads Minecraft + Fabric (a few hundred MB) — subsequent launches are fast
5. Once in-game, press **Esc** → **Account Switcher** to log into your real Microsoft account

The launcher checks GitHub Releases on startup and shows an update pill in the top bar if a
newer version is out; clicking it opens the release page in your browser.

The display name you type into the launcher is just a local placeholder profile name — it is
**not** an account, password, or authentication of any kind. Real Microsoft/Xbox login happens
entirely inside Minecraft via Account Switcher.

## Building from source

Requires JDK 21+.

```bash
./gradlew shadowJar
```

Output: `build/libs/MidasLauncher.jar`. Run it with:

```bash
java -jar build/libs/MidasLauncher.jar
```

The `mods-bundle/` folder next to the jar must ship alongside it — it holds one subfolder per
supported Minecraft version (`mods-bundle/1.21.11/`, `mods-bundle/26.1.2/`, `mods-bundle/26.2/`),
each with the mod jars that get copied into that version's instance on first launch. Adding a new
version means adding a matching `mods-bundle/{version}/` folder and listing it in
`GameSession.SUPPORTED_VERSIONS`.

### Building the installer

Requires [WiX Toolset v3](https://github.com/wixtoolset/wix3/releases) (`candle.exe`/`light.exe`
on PATH) and `jpackage` (bundled with the JDK since 14):

```bash
mkdir jpackage-input && cp build/libs/MidasLauncher.jar jpackage-input/
jpackage --type msi --name "Midas Client" --input jpackage-input \
  --main-jar MidasLauncher.jar --main-class com.midaspvp.launcher.Launcher \
  --app-content mods-bundle --dest dist-msi --vendor MidasPVP --app-version 1.1.1 \
  --win-menu --win-menu-group "MidasPVP" --win-shortcut --win-dir-chooser --win-per-user-install \
  --jlink-options "--strip-debug --no-man-pages --no-header-files"
```

The `--jlink-options` override is required: jpackage's default jlink options strip
`java.exe`/`javaw.exe` from the bundled runtime (via `--strip-native-commands`), which breaks
`GameLauncher` spawning a separate `java` process to run Minecraft. Omitting the default's strip
flag keeps those binaries.

Bumping the version needs two edits kept in sync: `--app-version` above, and
`UpdateChecker.APP_VERSION` in source (used for the update-check comparison).

## How it works

- `MinecraftInstaller` resolves the official Mojang version manifest + the Fabric Loader install
  profile, downloads all libraries/assets/the client jar, verifying sha1 hashes and caching
  everything under `cache/`.
- `GameLauncher` builds the JVM/game argument list from Mojang's own placeholder-substitution
  format and starts the game process.
- `ModBundler` copies the jars from `mods-bundle/` into the instance's `mods/` folder.
- The UI is a JavaFX `WebView` rendering local HTML/CSS (`src/main/resources/web/`), talking to
  the Java backend through a small `window.midas` JS↔Java bridge (`Main.Bridge`).
- `UpdateChecker` compares its own `APP_VERSION` against GitHub's latest release tag on startup.

No account credentials of any kind are collected, stored, or transmitted by this launcher.

## License

The launcher's own source code is [MIT licensed](LICENSE) — do what you want with it.

The mod jars in `mods-bundle/` are third-party software redistributed unmodified under their
own licenses:

- **Fabric API** — Apache License 2.0
- **In-Game Account Switcher** by VidTu — GNU LGPL v3.0-or-later
- **Tier Tagger** — MIT, from [MidasPVP/tiertagger](https://github.com/MidasPVP)
