package com.midaspvp.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Runs the install + mod-bundling + launch pipeline on a background thread, reporting into the page via the Bridge. */
final class GameSession {
	private static final String MINECRAFT_VERSION = "1.21.11";
	private static final String FABRIC_LOADER_VERSION = "0.19.3";

	private final Main.Bridge bridge;
	private final Path launcherHome = Path.of("").toAbsolutePath();
	private final Path cacheDir = launcherHome.resolve("cache");
	private final Path gameDir = launcherHome.resolve("instance");

	GameSession(Main.Bridge bridge) {
		this.bridge = bridge;
	}

	void start(String username) {
		Thread thread = new Thread(() -> run(username), "midas-game-session");
		thread.setDaemon(true);
		thread.start();
	}

	private void run(String username) {
		try {
			bridge.call("onProgress", "Checking Minecraft " + MINECRAFT_VERSION + "...", 0, 0);
			MinecraftInstaller installer = new MinecraftInstaller(MINECRAFT_VERSION, FABRIC_LOADER_VERSION, cacheDir);
			InstallResult result = installer.install(
					line -> bridge.call("onLog", line),
					(done, total) -> bridge.call("onProgress", "Downloading files...", done, total)
			);

			bridge.call("onProgress", "Installing mods...", 0, 0);
			ModBundler.installInto(gameDir.resolve("mods"));

			bridge.call("onProgress", "Launching...", 0, 0);
			String javaExe = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "javaw.exe";
			if (!new java.io.File(javaExe).isFile()) {
				javaExe = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
			}
			Process process = GameLauncher.launch(result, gameDir, cacheDir.resolve("natives").resolve(MINECRAFT_VERSION), javaExe, username);
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
