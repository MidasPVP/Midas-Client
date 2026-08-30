package com.midaspvp.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads a new release's MSI and re-runs the Windows installer over the current install. */
public final class SelfUpdater {

	/** Downloads the given release's .msi into the system temp dir. Blocking — run off the JavaFX thread. */
	public static Path downloadInstaller(UpdateChecker.UpdateInfo update) throws IOException, InterruptedException {
		if (update.msiUrl() == null) {
			throw new IOException("This release has no .msi attached to auto-update with.");
		}
		Path dest = Path.of(System.getProperty("java.io.tmpdir"), update.msiName());
		Downloader.fetch(update.msiUrl(), dest, null);
		return dest;
	}

	/** Launches the downloaded installer (shows a minimal progress UI) and exits this process so
	 *  it isn't holding its own files open while the installer replaces them. Never returns. */
	public static void applyAndExit(Path msiPath) throws IOException {
		new ProcessBuilder("msiexec", "/i", msiPath.toAbsolutePath().toString(), "/passive", "/norestart")
				.start();
		System.exit(0);
	}

	private SelfUpdater() {
	}
}
