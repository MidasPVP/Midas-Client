package com.midaspvp.launcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * JavaFX's WebView can't decode WebP — confirmed by testing (it just silently renders nothing).
 * Modrinth serves every mod icon as WebP, so the mod browser showed blank icons for almost every
 * result. This downloads the icon once, decodes it with the twelvemonkeys WebP plugin (registered
 * via build.gradle's dependency, no native libs involved), re-encodes it as PNG, and caches the
 * result on disk so repeat searches for the same mod don't re-download/re-decode it.
 */
final class IconCache {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final Path CACHE_DIR = Path.of("").toAbsolutePath().resolve("cache").resolve("icons");

	/** Blocking — run off the JavaFX thread. Returns a local file:// URI for a re-encoded PNG, or
	 *  null if the icon couldn't be fetched/decoded (the caller should just show no icon then). */
	static String toLocalPngUri(String remoteUrl) {
		if (remoteUrl == null || remoteUrl.isBlank()) return null;
		try {
			Path dest = CACHE_DIR.resolve(sha256Hex(remoteUrl) + ".png");
			if (!Files.isRegularFile(dest)) {
				byte[] sourceBytes = download(remoteUrl);
				BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(sourceBytes));
				if (image == null) return null; // Format ImageIO still can't decode even with the WebP plugin.
				Files.createDirectories(CACHE_DIR);
				Path tmp = Files.createTempFile(CACHE_DIR, "icon", ".tmp");
				ImageIO.write(image, "png", tmp.toFile());
				Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return dest.toUri().toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] download(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
		return response.body();
	}

	private static String sha256Hex(String s) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for (byte b : digest) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private IconCache() {
	}
}
