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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Downloads files to a local cache, skipping ones that already exist with a matching sha1. */
public final class Downloader {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2) // lets many in-flight requests to the same host multiplex over one connection
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final int MAX_ATTEMPTS = 6;

	// How many downloads run at once. Higher than a handful mostly helps with the ~5000 tiny asset
	// files (each is a few round-trips of latency, not bandwidth-bound), and Mojang's asset CDN is a
	// real CDN built for the official launcher hammering it this way — but a single host still only
	// gets a slice of this via the per-host limiter below, so raising this mainly parallelizes across
	// the *different* hosts (Mojang assets, Mojang libraries, Fabric maven) rather than hammering one.
	private static final int WORKERS = Math.min(48, Math.max(8, Runtime.getRuntime().availableProcessors() * 6));

	// Separate cap on concurrent requests to any single host, independent of WORKERS, so we don't
	// open dozens of simultaneous connections to one server even when WORKERS is high.
	private static final int MAX_PER_HOST = 12;
	private static final java.util.Map<String, Semaphore> HOST_LIMITS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Downloads one file if missing/mismatched. sha1 may be null to skip verification.
	 *  Retries transient failures and HTTP 429s (honoring Retry-After when present) with backoff. */
	public static void fetch(String url, Path dest, String sha1) throws IOException, InterruptedException {
		if (Files.isRegularFile(dest) && (sha1 == null || sha1Matches(dest, sha1))) {
			return;
		}
		Semaphore hostLimit = hostLimiterFor(url);
		IOException lastError = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			hostLimit.acquire();
			long retryAfterMillis = -1;
			try {
				fetchOnce(url, dest, sha1);
				return;
			} catch (RateLimitedException e) {
				lastError = e;
				retryAfterMillis = e.retryAfterMillis;
			} catch (IOException e) {
				lastError = e;
			} finally {
				hostLimit.release();
			}
			if (attempt < MAX_ATTEMPTS) {
				long backoff = retryAfterMillis >= 0 ? retryAfterMillis : 400L * (1L << Math.min(attempt, 4));
				Thread.sleep(backoff);
			}
		}
		throw new IOException("Failed after " + MAX_ATTEMPTS + " attempts: " + url, lastError);
	}

	private static Semaphore hostLimiterFor(String url) {
		String host = URI.create(url).getHost();
		return HOST_LIMITS.computeIfAbsent(host == null ? "" : host, h -> new Semaphore(MAX_PER_HOST));
	}

	private static void fetchOnce(String url, Path dest, String sha1) throws IOException, InterruptedException {
		Files.createDirectories(dest.getParent());
		Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
		HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
		if (response.statusCode() == 429) {
			Files.deleteIfExists(tmp);
			long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(-1);
			throw new RateLimitedException(url, retryAfter >= 0 ? retryAfter * 1000 : 2000);
		}
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
	public static void fetchAll(List<Task> tasks, BiConsumer<Integer, Integer> onProgress) throws IOException {
		int total = tasks.size();
		AtomicInteger done = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
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

	private static final class RateLimitedException extends IOException {
		final long retryAfterMillis;

		RateLimitedException(String url, long retryAfterMillis) {
			super("HTTP 429 (rate limited) for " + url);
			this.retryAfterMillis = retryAfterMillis;
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
