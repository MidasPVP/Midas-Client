package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Downloads/resolves everything needed to run vanilla Minecraft + Fabric Loader for one version. */
public final class MinecraftInstaller {
	private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	public final String minecraftVersion;
	public final String fabricLoaderVersion;
	public final Path cacheDir;

	public MinecraftInstaller(String minecraftVersion, String fabricLoaderVersion, Path cacheDir) {
		this.minecraftVersion = minecraftVersion;
		this.fabricLoaderVersion = fabricLoaderVersion;
		this.cacheDir = cacheDir;
	}

	public InstallResult install(Consumer<String> log, BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
		log.accept("Fetching version manifest...");
		JsonObject manifest = getJson(VERSION_MANIFEST_URL);
		String versionJsonUrl = null;
		for (JsonElement el : manifest.getAsJsonArray("versions")) {
			JsonObject v = el.getAsJsonObject();
			if (v.get("id").getAsString().equals(minecraftVersion)) {
				versionJsonUrl = v.get("url").getAsString();
				break;
			}
		}
		if (versionJsonUrl == null) throw new IOException("Minecraft version not found: " + minecraftVersion);

		log.accept("Fetching version info for " + minecraftVersion + "...");
		JsonObject vanilla = getJson(versionJsonUrl);

		log.accept("Fetching Fabric Loader " + fabricLoaderVersion + "...");
		JsonObject fabric = getJson("https://meta.fabricmc.net/v2/versions/loader/" + minecraftVersion + "/" + fabricLoaderVersion + "/profile/json");

		List<Downloader.Task> tasks = new ArrayList<>();
		List<Path> classpath = new ArrayList<>();

		Path librariesDir = cacheDir.resolve("libraries");
		for (JsonElement el : vanilla.getAsJsonArray("libraries")) {
			JsonObject lib = el.getAsJsonObject();
			if (!OsMatch.allowed(lib)) continue;
			JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
			Path dest = librariesDir.resolve(artifact.get("path").getAsString());
			tasks.add(new Downloader.Task(artifact.get("url").getAsString(), dest, artifact.get("sha1").getAsString()));
			classpath.add(dest);
		}
		for (JsonElement el : fabric.getAsJsonArray("libraries")) {
			JsonObject lib = el.getAsJsonObject();
			String coord = lib.get("name").getAsString();
			String repoBase = lib.get("url").getAsString();
			String relPath = mavenCoordToPath(coord);
			Path dest = librariesDir.resolve(relPath);
			String sha1 = lib.has("sha1") ? lib.get("sha1").getAsString() : null;
			tasks.add(new Downloader.Task(joinUrl(repoBase, relPath), dest, sha1));
			classpath.add(dest);
		}

		JsonObject assetIndexInfo = vanilla.getAsJsonObject("assetIndex");
		String assetIndexId = assetIndexInfo.get("id").getAsString();
		Path assetIndexDest = cacheDir.resolve("assets/indexes/" + assetIndexId + ".json");
		Downloader.fetch(assetIndexInfo.get("url").getAsString(), assetIndexDest, assetIndexInfo.get("sha1").getAsString());
		JsonObject assetIndex = JsonParser.parseString(Files.readString(assetIndexDest, StandardCharsets.UTF_8)).getAsJsonObject();
		Path objectsDir = cacheDir.resolve("assets/objects");
		for (var entry : assetIndex.getAsJsonObject("objects").entrySet()) {
			JsonObject obj = entry.getValue().getAsJsonObject();
			String hash = obj.get("hash").getAsString();
			Path dest = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
			tasks.add(new Downloader.Task("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, dest, hash));
		}

		JsonObject clientDownload = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
		Path clientJar = cacheDir.resolve("versions/" + minecraftVersion + "/" + minecraftVersion + ".jar");
		tasks.add(new Downloader.Task(clientDownload.get("url").getAsString(), clientJar, clientDownload.get("sha1").getAsString()));
		classpath.add(clientJar);

		log.accept("Downloading " + tasks.size() + " files (libraries, assets, client)...");
		Downloader.fetchAll(tasks, progress);

		JsonArray jvmArgs = new JsonArray();
		if (vanilla.getAsJsonObject("arguments").has("jvm")) {
			jvmArgs.addAll(vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm"));
		}
		if (fabric.getAsJsonObject("arguments").has("jvm")) {
			jvmArgs.addAll(fabric.getAsJsonObject("arguments").getAsJsonArray("jvm"));
		}

		JsonArray gameArgs = fabric.getAsJsonObject("arguments").getAsJsonArray("game");
		if (gameArgs == null || gameArgs.isEmpty()) {
			gameArgs = vanilla.getAsJsonObject("arguments").getAsJsonArray("game");
		}

		return new InstallResult(
				fabric.get("mainClass").getAsString(),
				classpath,
				gameArgs,
				jvmArgs,
				cacheDir.resolve("assets"),
				assetIndexId
		);
	}

	private static String mavenCoordToPath(String coord) {
		String[] parts = coord.split(":");
		String group = parts[0].replace('.', '/');
		String artifact = parts[1];
		String version = parts[2];
		String classifier = parts.length > 3 ? "-" + parts[3] : "";
		return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
	}

	private static String joinUrl(String base, String path) {
		return (base.endsWith("/") ? base : base + "/") + path;
	}

	private static final int JSON_MAX_ATTEMPTS = 6;

	/** Same transient-failure retries as Downloader.fetch — a dropped connection here used to fail
	 *  the whole install with no retry at all, since this path (manifest/version/Fabric-profile
	 *  fetches) is separate from the parallel file downloader. */
	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		IOException lastError = null;
		for (int attempt = 1; attempt <= JSON_MAX_ATTEMPTS; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (IOException e) {
				lastError = e;
				if (attempt < JSON_MAX_ATTEMPTS) {
					Thread.sleep(400L * (1L << Math.min(attempt, 4)));
				}
			}
		}
		throw new IOException("Failed after " + JSON_MAX_ATTEMPTS + " attempts: " + url, lastError);
	}
}
