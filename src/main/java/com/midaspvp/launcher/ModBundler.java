package com.midaspvp.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Copies the mod jars shipped in the "mods-bundle" folder next to the launcher into the instance's
 * mods folder. (Not embedded inside the launcher jar itself: fat-jar tooling unpacks any nested
 * ".jar" resource it finds during merging, which silently destroys embedded mod jars.)
 */
public final class ModBundler {

	public static void installInto(Path modsDir) throws IOException {
		Path bundleDir = Path.of("").toAbsolutePath().resolve("mods-bundle");
		if (!Files.isDirectory(bundleDir)) {
			throw new IOException("mods-bundle folder not found next to the launcher: " + bundleDir);
		}
		Files.createDirectories(modsDir);
		try (Stream<Path> files = Files.list(bundleDir)) {
			for (Path source : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".jar"))::iterator) {
				Path dest = modsDir.resolve(source.getFileName());
				if (Files.isRegularFile(dest)) continue;
				Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private ModBundler() {
	}
}
