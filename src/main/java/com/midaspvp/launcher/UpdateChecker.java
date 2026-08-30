package com.midaspvp.launcher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks GitHub Releases for a newer version than this build. */
public final class UpdateChecker {
	/** Must match the --app-version passed to jpackage. */
	public static final String APP_VERSION = "1.0.0";

	private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/MidasPVP/Midas-Client/releases/latest";
	private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
	private static final Pattern HTML_URL_PATTERN = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");

	public record UpdateInfo(String version, String url) {
	}

	/** Blocking network call — run off the JavaFX thread. Returns null if up to date or the check fails. */
	public static UpdateInfo checkForUpdate() {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
					.header("Accept", "application/vnd.github+json")
					.timeout(Duration.ofSeconds(10))
					.GET().build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;

			Matcher tagMatcher = TAG_PATTERN.matcher(response.body());
			if (!tagMatcher.find()) return null;
			String latest = tagMatcher.group(1);

			if (!isNewer(latest, APP_VERSION)) return null;

			Matcher urlMatcher = HTML_URL_PATTERN.matcher(response.body());
			String url = urlMatcher.find() ? urlMatcher.group(1) : "https://github.com/MidasPVP/Midas-Client/releases/latest";
			return new UpdateInfo(latest, url);
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
