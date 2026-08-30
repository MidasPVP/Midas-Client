# Midas Client

The official launcher for [MidasPVP](https://midaspvp.com). Downloads and launches Minecraft
1.21.11 on Fabric Loader, with these mods pre-installed:

- **[Fabric API](https://modrinth.com/mod/fabric-api)** — required by the other two
- **Tier Tagger** — shows MidasPVP tier ranks above players in-game (built from this org's
  [tiertagger](https://github.com/MidasPVP) repo)
- **[In-Game Account Switcher](https://modrinth.com/mod/in-game-account-switcher)** by VidTu —
  sign in with your real Microsoft account *from inside the game* (press Esc), so this launcher
  itself never needs to touch your Microsoft login

## Using it

1. Download the latest release from the [Releases page](../../releases)
2. Run it, pick a display name, hit **Play**
3. First launch downloads Minecraft + Fabric (a few hundred MB) — subsequent launches are fast
4. Once in-game, press **Esc** → **Account Switcher** to log into your real Microsoft account

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

The `mods-bundle/` folder next to the jar must ship alongside it — it holds the mod jars that
get copied into the game instance on first launch.

## How it works

- `MinecraftInstaller` resolves the official Mojang version manifest + the Fabric Loader install
  profile, downloads all libraries/assets/the client jar, verifying sha1 hashes and caching
  everything under `cache/`.
- `GameLauncher` builds the JVM/game argument list from Mojang's own placeholder-substitution
  format and starts the game process.
- `ModBundler` copies the jars from `mods-bundle/` into the instance's `mods/` folder.
- The UI is a JavaFX `WebView` rendering local HTML/CSS (`src/main/resources/web/`), talking to
  the Java backend through a small `window.midas` JS↔Java bridge (`Main.Bridge`).

No account credentials of any kind are collected, stored, or transmitted by this launcher.

## License

The launcher's own source code is [MIT licensed](LICENSE) — do what you want with it.

The mod jars in `mods-bundle/` are third-party software redistributed unmodified under their
own licenses:

- **Fabric API** — Apache License 2.0
- **In-Game Account Switcher** by VidTu — GNU LGPL v3.0-or-later
- **Tier Tagger** — MIT, from [MidasPVP/tiertagger](https://github.com/MidasPVP)
