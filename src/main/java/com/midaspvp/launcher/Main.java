package com.midaspvp.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

public final class Main extends Application {

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		WebView webView = new WebView();
		WebEngine engine = webView.getEngine();

		engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
			if (newState == Worker.State.SUCCEEDED) {
				JSObject window = (JSObject) engine.executeScript("window");
				window.setMember("midas", new Bridge(engine));
			}
		});

		engine.load(Main.class.getResource("/web/index.html").toExternalForm());

		Scene scene = new Scene(webView, 1100, 680);
		stage.setTitle("Midas Client");
		stage.setScene(scene);
		stage.show();
	}

	/** Java object exposed to the page as `window.midas`. */
	public static final class Bridge {
		private final WebEngine engine;

		Bridge(WebEngine engine) {
			this.engine = engine;
		}

		/** Called from JS: window.midas.play(username, version) */
		public void play(String username, String version) {
			new GameSession(this).start(username, version);
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
