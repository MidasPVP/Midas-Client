package com.midaspvp.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where player data actually lives — worlds, mod configs, the download cache. Deliberately NOT
 * inside the app's own install directory (what {@code Path.of("").toAbsolutePath()} resolves to
 * for the installed app). Every install/update replaces that directory's contents, which was
 * silently deleting instance-{version}/ and cache/ (worlds, mods, everything) on every single auto-update.
 * A launcher update should never cost someone their world.
 */
final class UserData {
	static final Path DIR = resolveBaseDir().resolve("MidasPVP");

	private static Path resolveBaseDir() {
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null && !localAppData.isBlank()) return Path.of(localAppData);
		return Path.of(System.getProperty("user.home"));
	}

	/** One-time move of any instance-{version}/ or cache/ folders that still exist next to the app (from before
	 *  this fix, or from a version that hasn't updated yet) into the real user-data directory.
	 *  Never overwrites something already in the new location — if in doubt, it leaves the old
	 *  copy alone rather than risk clobbering newer data. Safe to call on every startup. */
	static void migrateFromAppDirIfNeeded() {
		Path appDir = Path.of("").toAbsolutePath();
		try {
			Files.createDirectories(DIR);
		} catch (IOException e) {
			return; // Can't create the destination — nothing safe to do here.
		}

		try (DirectoryStream<Path> entries = Files.newDirectoryStream(appDir)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				boolean isOldGameData = name.equals("cache") || name.startsWith("instance-");
				if (!isOldGameData || !Files.isDirectory(entry)) continue;

				Path dest = DIR.resolve(name);
				if (Files.exists(dest)) continue; // Already migrated (or the new copy is the real one) — don't touch it.
				try {
					Files.move(entry, dest, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicFailed) {
					try {
						moveRecursively(entry, dest);
					} catch (IOException e) {
						// Best-effort — an update in progress or a locked file shouldn't block launching.
					}
				}
			}
		} catch (IOException ignored) {
			// No old data next to the app (fresh install, or already migrated) — nothing to do.
		}
	}

	private static void moveRecursively(Path source, Path dest) throws IOException {
		Files.createDirectories(dest);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
			for (Path entry : entries) {
				Path target = dest.resolve(entry.getFileName());
				if (Files.isDirectory(entry)) {
					moveRecursively(entry, target);
				} else {
					Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		Files.deleteIfExists(source);
	}

	private UserData() {
	}
}
