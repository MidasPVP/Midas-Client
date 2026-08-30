package com.midaspvp.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Copies the mod jars shipped in "mods-bundle/{version}/" next to the launcher into the
 * instance's mods folder. (Not embedded inside the launcher jar itself: fat-jar tooling unpacks
 * any nested ".jar" resource it finds during merging, which silently destroys embedded mod jars.)
 */
public final class ModBundler {

	public static void installInto(Path modsDir, String minecraftVersion) throws IOException {
		Path bundleDir = Path.of("").toAbsolutePath().resolve("mods-bundle").resolve(minecraftVersion);
		if (!Files.isDirectory(bundleDir)) {
			throw new IOException("No mods-bundle for Minecraft " + minecraftVersion + " next to the launcher: " + bundleDir);
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
