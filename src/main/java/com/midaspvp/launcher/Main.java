package com.midaspvp.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import com.google.gson.Gson;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main extends Application {

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		WebView webView = new WebView();
		WebEngine engine = webView.getEngine();
		Bridge bridge = new Bridge(engine);

		engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
			if (newState == Worker.State.SUCCEEDED) {
				JSObject window = (JSObject) engine.executeScript("window");
				window.setMember("midas", bridge);
				checkForUpdateInBackground(bridge); // runs once automatically whenever the launcher opens
				// The page's own bottom <script> already ran before this listener fires (window.midas
				// didn't exist yet), so anything that needs the bridge on first load re-runs from here.
				engine.executeScript("if (typeof onBridgeReady === 'function') onBridgeReady();");
			}
		});

		engine.load(Main.class.getResource("/web/index.html").toExternalForm());

		Scene scene = new Scene(webView, 1100, 680);
		stage.setTitle("Midas Client " + UpdateChecker.APP_VERSION);
		stage.getIcons().addAll(loadIcons());
		stage.setScene(scene);
		stage.show();
	}

	/** Window/taskbar icon at every size JavaFX can pick from — smaller ones are used in the
	 *  title bar/taskbar, the largest in Alt-Tab/Explorer. Missing files are skipped rather than
	 *  crashing the launch, since the icon is cosmetic. */
	private static java.util.List<javafx.scene.image.Image> loadIcons() {
		java.util.List<javafx.scene.image.Image> icons = new java.util.ArrayList<>();
		for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
			var url = Main.class.getResource("/icons/logo-" + size + ".png");
			if (url != null) icons.add(new javafx.scene.image.Image(url.toExternalForm()));
		}
		return icons;
	}

	private static void checkForUpdateInBackground(Bridge bridge) {
		Thread thread = new Thread(() -> {
			UpdateChecker.UpdateInfo update = UpdateChecker.checkForUpdate();
			if (update == null) return;

			if (update.installerUrl() == null) {
				// No installer attached to the release — just point at the page, nothing to auto-download.
				bridge.call("onUpdateAvailable", update.version(), update.htmlUrl());
				return;
			}

			bridge.call("onUpdateDownloading", update.version());
			try {
				Path installer = SelfUpdater.downloadInstaller(update);
				bridge.pendingUpdateInstaller = installer;
				bridge.pendingUpdateIsExe = update.isExe();
				bridge.call("onUpdateReady", update.version());
			} catch (Exception e) {
				// Download failed - still let them get it manually from the release page.
				bridge.call("onUpdateAvailable", update.version(), update.htmlUrl());
			}
		}, "midas-update-check");
		thread.setDaemon(true);
		thread.start();
	}

	/** Java object exposed to the page as `window.midas`. */
	public static final class Bridge {
		private final WebEngine engine;
		volatile Path pendingUpdateInstaller;
		volatile boolean pendingUpdateIsExe;

		Bridge(WebEngine engine) {
			this.engine = engine;
		}

		/** Called from JS: window.midas.play(username, version) */
		public void play(String username, String version) {
			new GameSession(this).start(username, version);
		}

		/** Called from JS: window.midas.playServer(username, version, serverName, serverAddress) — adds the
		 *  server to the player's Multiplayer list (if not already there) before launching. Not an
		 *  auto-connect: see the note on GameLauncher.launch for why. */
		public void playServer(String username, String version, String serverName, String serverAddress) {
			new GameSession(this).start(username, version, serverName, serverAddress);
		}

		/** Called from JS: window.midas.openUrl(url) — opens in the system's default browser. */
		public void openUrl(String url) {
			try {
				Desktop.getDesktop().browse(URI.create(url));
			} catch (Exception e) {
				call("onLog", "[error] Couldn't open browser: " + e.getMessage());
			}
		}

		/** Called from JS: window.midas.applyUpdate() — installs the already-downloaded update and restarts. */
		public void applyUpdate() {
			Path installer = pendingUpdateInstaller;
			if (installer == null) return;
			try {
				SelfUpdater.applyAndExit(installer, pendingUpdateIsExe);
			} catch (Exception e) {
				call("onLog", "[error] Couldn't launch the updater: " + e.getMessage());
			}
		}

		/** Called from JS: window.midas.searchMods(query, version, category, sortIndex) — results come
		 *  back via onSearchResults(json). category/sortIndex may be empty strings for "no filter"/default. */
		public void searchMods(String query, String version, String category, String sortIndex) {
			Thread thread = new Thread(() -> {
				String json;
				try {
					json = new Gson().toJson(ModrinthBrowser.search(query, version, category, sortIndex));
				} catch (Exception e) {
					json = "[]";
				}
				call("onSearchResults", json);
			}, "midas-mod-search");
			thread.setDaemon(true);
			thread.start();
		}

		/** Called from JS: window.midas.fetchPlayerStats(username) — result comes back via onPlayerStats(json). */
		public void fetchPlayerStats(String username) {
			Thread thread = new Thread(() -> call("onPlayerStats", TierApi.fetchPlayer(username)), "midas-tier-stats");
			thread.setDaemon(true);
			thread.start();
		}

		/** Called from JS: window.midas.installMod(slug, version) — installs into that version's mods folder. */
		public void installMod(String slug, String version) {
			Thread thread = new Thread(() -> {
				try {
					Path modsDir = UserData.DIR.resolve("instance-" + version).resolve("mods");
					Files.createDirectories(modsDir);
					String filename = ModrinthBrowser.install(slug, version, modsDir);
					call("onModInstalled", slug, filename);
				} catch (Exception e) {
					call("onModInstallError", slug, String.valueOf(e.getMessage()));
				}
			}, "midas-mod-install");
			thread.setDaemon(true);
			thread.start();
		}

		/** Called from JS: window.midas.listInstalledMods(version) — the *actual* jars on disk for that
		 *  instance (bundled + anything installed via the mod browser), not a hardcoded list. Result
		 *  comes back via onInstalledMods(version, json). */
		public void listInstalledMods(String version) {
			Thread thread = new Thread(() -> {
				java.util.List<String> names = new java.util.ArrayList<>();
				Path modsDir = UserData.DIR.resolve("instance-" + version).resolve("mods");
				try (var files = Files.list(modsDir)) {
					files.filter(p -> p.getFileName().toString().endsWith(".jar"))
							.map(p -> p.getFileName().toString())
							.sorted(String.CASE_INSENSITIVE_ORDER)
							.forEach(names::add);
				} catch (Exception e) {
					// No mods folder yet (instance never installed) — an empty list is the correct answer.
				}
				call("onInstalledMods", version, new Gson().toJson(names));
			}, "midas-list-mods");
			thread.setDaemon(true);
			thread.start();
		}

		/** Called from JS: window.midas.openGameFolder(version) — opens that instance's folder in Explorer. */
		public void openGameFolder(String version) {
			try {
				Path gameDir = UserData.DIR.resolve("instance-" + version);
				Files.createDirectories(gameDir);
				Desktop.getDesktop().open(gameDir.toFile());
			} catch (Exception e) {
				call("onLog", "[error] Couldn't open the game folder: " + e.getMessage());
			}
		}

		/** Called from JS: window.midas.checkForUpdates() — same check that already runs automatically
		 *  on startup, exposed again for a manual "Check for updates" button. */
		public void checkForUpdates() {
			checkForUpdateInBackground(this);
		}

		void call(String function, Object... args) {
			StringBuilder script = new StringBuilder(function).append('(');
			for (int i = 0; i < args.length; i++) {
				if (i > 0) script.append(',');
				Object a = args[i];
				if (a instanceof String s) {
					script.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
				} else {
					script.append(a);
				}
			}
			script.append(')');
			String js = script.toString();
			Platform.runLater(() -> engine.executeScript(js));
		}
	}
}
