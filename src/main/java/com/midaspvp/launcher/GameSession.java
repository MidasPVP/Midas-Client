package com.midaspvp.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

/** Runs the install + mod-bundling + launch pipeline on a background thread, reporting into the page via the Bridge. */
final class GameSession {
	private static final String FABRIC_LOADER_VERSION = "0.19.3";

	/** Minecraft versions this launcher ships mod bundles for — keep in sync with mods-bundle/. */
	static final Set<String> SUPPORTED_VERSIONS = Set.of("1.21.11", "26.1.2", "26.2");
	static final String DEFAULT_VERSION = "1.21.11";

	private final Main.Bridge bridge;
	// Deliberately NOT the app's own install directory — see UserData's javadoc for why.
	private final Path userDataDir = UserData.DIR;
	private final Path cacheDir = userDataDir.resolve("cache");

	GameSession(Main.Bridge bridge) {
		this.bridge = bridge;
	}

	void start(String username, String minecraftVersion) {
		start(username, minecraftVersion, null, null);
	}

	/** serverName/serverAddress may both be null for a plain launch. When set, that server is
	 *  added to the player's Multiplayer server list (if not already there) before launch — not
	 *  auto-connected; see the note on GameLauncher.launch for why. */
	void start(String username, String minecraftVersion, String serverName, String serverAddress) {
		String version = SUPPORTED_VERSIONS.contains(minecraftVersion) ? minecraftVersion : DEFAULT_VERSION;
		Thread thread = new Thread(() -> run(username, version, serverName, serverAddress), "midas-game-session");
		thread.setDaemon(true);
		thread.start();
	}

	private void run(String username, String version, String serverName, String serverAddress) {
		try {
			UserData.migrateFromAppDirIfNeeded();

			// Each Minecraft version gets its own instance folder so worlds/configs/mods don't clash.
			Path gameDir = userDataDir.resolve("instance-" + version);

			bridge.call("onProgress", "Checking Minecraft " + version + "...", 0, 0);
			MinecraftInstaller installer = new MinecraftInstaller(version, FABRIC_LOADER_VERSION, cacheDir);
			InstallResult result = installer.install(
					line -> bridge.call("onLog", line),
					(done, total) -> bridge.call("onProgress", "Downloading files...", done, total)
			);

			bridge.call("onProgress", "Installing mods...", 0, 0);
			ModBundler.installInto(gameDir.resolve("mods"), version);

			if (serverAddress != null && !serverAddress.isBlank()) {
				try {
					ServerListNbt.upsertServer(gameDir.resolve("servers.dat"), serverName != null ? serverName : serverAddress, serverAddress);
					bridge.call("onLog", "Added " + serverAddress + " to your Multiplayer server list.");
				} catch (Exception e) {
					// Not fatal — the player can still add the server manually.
					bridge.call("onLog", "[warn] Couldn't add " + serverAddress + " to your server list: " + e.getMessage());
				}
			}

			bridge.call("onProgress", "Launching...", 0, 0);
			String javaExe = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "javaw.exe";
			if (!new java.io.File(javaExe).isFile()) {
				// java.exe, not "java" — a bare extensionless name isn't a runnable Windows executable.
				javaExe = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java.exe";
			}
			Process process = GameLauncher.launch(result, gameDir, cacheDir.resolve("natives").resolve(version), javaExe, username);
			bridge.call("onLaunched");
			streamOutput(process);
		} catch (Exception e) {
			bridge.call("onError", String.valueOf(e.getMessage()));
		}
	}

	private void streamOutput(Process process) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				bridge.call("onLog", "[mc] " + line);
			}
		}
		int exit = process.waitFor();
		bridge.call("onLog", "Minecraft exited with code " + exit);
		bridge.call("onExit", exit);
	}
}
