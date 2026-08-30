package com.midaspvp.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Downloads files to a local cache, skipping ones that already exist with a matching sha1. */
public final class Downloader {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Downloads one file if missing/mismatched. sha1 may be null to skip verification. */
	public static void fetch(String url, Path dest, String sha1) throws IOException, InterruptedException {
		if (Files.isRegularFile(dest) && (sha1 == null || sha1Matches(dest, sha1))) {
			return;
		}
		Files.createDirectories(dest.getParent());
		Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
		HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
		if (response.statusCode() != 200) {
			Files.deleteIfExists(tmp);
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}
		if (sha1 != null && !sha1Matches(tmp, sha1)) {
			Files.deleteIfExists(tmp);
			throw new IOException("sha1 mismatch for " + url);
		}
		Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** Downloads a batch of (url, dest, sha1) tasks in parallel, calling onProgress(done, total) as each completes. */
	public static void fetchAll(java.util.List<Task> tasks, BiConsumer<Integer, Integer> onProgress) throws IOException {
		int total = tasks.size();
		AtomicInteger done = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, Math.max(1, Runtime.getRuntime().availableProcessors() * 2)));
		try {
			java.util.List<Future<?>> futures = new java.util.ArrayList<>();
			for (Task t : tasks) {
				futures.add(pool.submit(() -> {
					try {
						fetch(t.url, t.dest, t.sha1);
					} catch (Exception e) {
						throw new RuntimeException("Failed: " + t.url, e);
					}
					onProgress.accept(done.incrementAndGet(), total);
				}));
			}
			for (Future<?> f : futures) {
				try {
					f.get();
				} catch (Exception e) {
					throw new IOException(e.getCause() != null ? e.getCause() : e);
				}
			}
		} finally {
			pool.shutdown();
		}
	}

	private static boolean sha1Matches(Path file, String expected) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] digest = md.digest(Files.readAllBytes(file));
			return HexFormat.of().formatHex(digest).equalsIgnoreCase(expected);
		} catch (IOException | NoSuchAlgorithmException e) {
			return false;
		}
	}

	public static final class Task {
		public final String url;
		public final Path dest;
		public final String sha1;

		public Task(String url, Path dest, String sha1) {
			this.url = url;
			this.dest = dest;
			this.sha1 = sha1;
		}
	}

	private Downloader() {
	}
}
