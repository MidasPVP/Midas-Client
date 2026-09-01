package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Search Modrinth for Fabric mods matching a Minecraft version, and install a chosen one into an instance. */
public final class ModrinthBrowser {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	// Real Modrinth loader slugs — Modrinth's "categories" field mixes these in with actual
	// categories (optimization, adventure, ...), so this is how results split the two apart for display.
	private static final Set<String> LOADER_SLUGS = Set.of(
			"fabric", "forge", "neoforge", "quilt", "liteloader", "rift", "modloader", "bukkit",
			"spigot", "paper", "purpur", "sponge", "bungeecord", "velocity", "waterfall", "folia", "datapack");

	public record ModResult(String slug, String title, String author, String description, String iconUrl,
							 int downloads, List<String> categories, List<String> loaders) {
	}

	/** Blocking — run off the JavaFX thread. Returns up to 20 results, most-downloaded first.
	 *  Icon URLs are already re-encoded to local PNGs (see IconCache) — Modrinth serves WebP, which
	 *  the launcher's WebView can't render. sortIndex is a Modrinth search "index" value:
	 *  relevance/downloads/follows/newest/updated. categoriesCsv is a comma-separated list of
	 *  category slugs (a result matches ANY of them), or null/blank for no filter. */
	public static List<ModResult> search(String query, String mcVersion, String categoriesCsv, String sortIndex) throws IOException, InterruptedException {
		String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		StringBuilder facetsJson = new StringBuilder("[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:")
				.append(mcVersion).append("\"]");
		if (categoriesCsv != null && !categoriesCsv.isBlank()) {
			facetsJson.append(",[");
			String[] cats = categoriesCsv.split(",");
			for (int i = 0; i < cats.length; i++) {
				if (i > 0) facetsJson.append(',');
				facetsJson.append("\"categories:").append(cats[i].trim()).append('"');
			}
			facetsJson.append(']');
		}
		facetsJson.append("]");
		String facets = URLEncoder.encode(facetsJson.toString(), StandardCharsets.UTF_8);
		String index = (sortIndex == null || sortIndex.isBlank()) ? "relevance" : sortIndex;
		String url = "https://api.modrinth.com/v2/search?query=" + encodedQuery
				+ "&facets=" + facets + "&limit=20&index=" + index;

		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());

		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray hits = root.getAsJsonArray("hits");
		List<ModResult> results = new ArrayList<>();
		for (JsonElement el : hits) {
			JsonObject hit = el.getAsJsonObject();
			List<String> categories = new ArrayList<>();
			List<String> loaders = new ArrayList<>();
			if (hit.has("display_categories")) {
				for (JsonElement c : hit.getAsJsonArray("display_categories")) {
					String name = c.getAsString();
					(LOADER_SLUGS.contains(name) ? loaders : categories).add(name);
				}
			}
			String iconUrl = hit.has("icon_url") && !hit.get("icon_url").isJsonNull() ? hit.get("icon_url").getAsString() : "";
			String localIcon = iconUrl.isEmpty() ? null : IconCache.toLocalPngUri(iconUrl);
			results.add(new ModResult(
					hit.get("slug").getAsString(),
					hit.get("title").getAsString(),
					hit.has("author") && !hit.get("author").isJsonNull() ? hit.get("author").getAsString() : "",
					hit.has("description") && !hit.get("description").isJsonNull() ? hit.get("description").getAsString() : "",
					localIcon == null ? "" : localIcon,
					hit.has("downloads") ? hit.get("downloads").getAsInt() : 0,
					categories, loaders
			));
		}
		return results;
	}

	/** Downloads the best matching Fabric build of `slug` for `mcVersion` into modsDir. Blocking. */
	public static String install(String slug, String mcVersion, Path modsDir) throws IOException, InterruptedException {
		String url = "https://api.modrinth.com/v2/project/" + slug + "/version"
				+ "?game_versions=" + URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8)
				+ "&loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8);

		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());

		JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
		if (versions.isEmpty()) throw new IOException("No Fabric build of '" + slug + "' for Minecraft " + mcVersion);

		JsonObject bestVersion = versions.get(0).getAsJsonObject();
		JsonArray files = bestVersion.getAsJsonArray("files");
		JsonObject primaryFile = files.get(0).getAsJsonObject();
		for (JsonElement el : files) {
			JsonObject f = el.getAsJsonObject();
			if (f.has("primary") && f.get("primary").getAsBoolean()) {
				primaryFile = f;
				break;
			}
		}

		String downloadUrl = primaryFile.get("url").getAsString();
		String filename = primaryFile.get("filename").getAsString();
		String sha1 = primaryFile.getAsJsonObject("hashes").has("sha1")
				? primaryFile.getAsJsonObject("hashes").get("sha1").getAsString() : null;

		Downloader.fetch(downloadUrl, modsDir.resolve(filename), sha1);
		return filename;
	}

	private ModrinthBrowser() {
	}
}
