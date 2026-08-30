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
				checkForUpdateInBackground(bridge);
			}
		});

		engine.load(Main.class.getResource("/web/index.html").toExternalForm());

		Scene scene = new Scene(webView, 1100, 680);
		stage.setTitle("Midas Client " + UpdateChecker.APP_VERSION);
		stage.setScene(scene);
		stage.show();
	}

	private void checkForUpdateInBackground(Bridge bridge) {
		Thread thread = new Thread(() -> {
			UpdateChecker.UpdateInfo update = UpdateChecker.checkForUpdate();
			if (update == null) return;

			if (update.msiUrl() == null) {
				// No installer attached to the release — just point at the page, nothing to auto-download.
				bridge.call("onUpdateAvailable", update.version(), update.htmlUrl());
				return;
			}

			bridge.call("onUpdateDownloading", update.version());
			try {
				Path msi = SelfUpdater.downloadInstaller(update);
				bridge.pendingUpdateInstaller = msi;
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

		Bridge(WebEngine engine) {
			this.engine = engine;
		}

		/** Called from JS: window.midas.play(username, version) */
		public void play(String username, String version) {
			new GameSession(this).start(username, version);
		}

		/** Called from JS: window.midas.playServer(username, version, serverAddress) — auto-connects on launch. */
		public void playServer(String username, String version, String serverAddress) {
			new GameSession(this).start(username, version, serverAddress);
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
			Path msi = pendingUpdateInstaller;
			if (msi == null) return;
			try {
				SelfUpdater.applyAndExit(msi);
			} catch (Exception e) {
				call("onLog", "[error] Couldn't launch the updater: " + e.getMessage());
			}
		}

		/** Called from JS: window.midas.searchMods(query, version) — results come back via onSearchResults(json). */
		public void searchMods(String query, String version) {
			Thread thread = new Thread(() -> {
				String json;
				try {
					json = new Gson().toJson(ModrinthBrowser.search(query, version));
				} catch (Exception e) {
					json = "[]";
				}
				call("onSearchResults", json);
			}, "midas-mod-search");
			thread.setDaemon(true);
			thread.start();
		}

		/** Called from JS: window.midas.installMod(slug, version) — installs into that version's mods folder. */
		public void installMod(String slug, String version) {
			Thread thread = new Thread(() -> {
				try {
					Path modsDir = Path.of("").toAbsolutePath().resolve("instance-" + version).resolve("mods");
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
