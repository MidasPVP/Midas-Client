package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Checks GitHub Releases for a newer version than this build. */
public final class UpdateChecker {
	/** Must match the --app-version passed to jpackage. */
	public static final String APP_VERSION = "1.4.4";

	private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/MidasPVP/Midas-Client/releases/latest";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** installerUrl/installerName are null if the release has no .exe/.msi asset attached (nothing to
	 *  auto-download). isExe tells SelfUpdater which switches/launch style to apply it with. */
	public record UpdateInfo(String version, String htmlUrl, String installerUrl, String installerName, boolean isExe) {
	}

	/** Blocking network call — run off the JavaFX thread. Returns null if up to date or the check fails. */
	public static UpdateInfo checkForUpdate() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
					.header("Accept", "application/vnd.github+json")
					.timeout(Duration.ofSeconds(10))
					.GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;

			JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
			String tag = release.get("tag_name").getAsString();
			String latest = tag.startsWith("v") ? tag.substring(1) : tag;
			if (!isNewer(latest, APP_VERSION)) return null;

			String htmlUrl = release.has("html_url") ? release.get("html_url").getAsString()
					: "https://github.com/MidasPVP/Midas-Client/releases/latest";

			String installerUrl = null;
			String installerName = null;
			boolean isExe = false;
			if (release.has("assets")) {
				JsonArray assets = release.getAsJsonArray("assets");
				// Prefer .exe over .msi if a release has both — .exe is the format actually
				// shipped to players; a release with only .msi still auto-updates fine with it.
				for (JsonElement el : assets) {
					JsonObject asset = el.getAsJsonObject();
					String name = asset.get("name").getAsString();
					String lower = name.toLowerCase();
					if (lower.endsWith(".exe")) {
						installerUrl = asset.get("browser_download_url").getAsString();
						installerName = name;
						isExe = true;
						break;
					}
					if (lower.endsWith(".msi") && installerUrl == null) {
						installerUrl = asset.get("browser_download_url").getAsString();
						installerName = name;
						isExe = false;
					}
				}
			}

			return new UpdateInfo(latest, htmlUrl, installerUrl, installerName, isExe);
		} catch (Exception e) {
			return null;
		}
	}

	/** Simple dotted-numeric version comparison (e.g. "1.2.0" > "1.10.0" is false, compares part by part). */
	private static boolean isNewer(String candidate, String current) {
		String[] a = candidate.split("[.\\-]");
		String[] b = current.split("[.\\-]");
		int len = Math.max(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int av = i < a.length ? parseIntSafe(a[i]) : 0;
			int bv = i < b.length ? parseIntSafe(b[i]) : 0;
			if (av != bv) return av > bv;
		}
		return false;
	}

	private static int parseIntSafe(String s) {
		try {
			return Integer.parseInt(s.replaceAll("[^0-9]", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private UpdateChecker() {
	}
}
