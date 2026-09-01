package com.midaspvp.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads a new release's installer and re-runs it over the current install. */
public final class SelfUpdater {

	/** Downloads the given release's installer into the system temp dir. Blocking — run off the JavaFX thread. */
	public static Path downloadInstaller(UpdateChecker.UpdateInfo update) throws IOException, InterruptedException {
		if (update.installerUrl() == null) {
			throw new IOException("This release has no .exe/.msi attached to auto-update with.");
		}
		Path dest = Path.of(System.getProperty("java.io.tmpdir"), update.installerName());
		Downloader.fetch(update.installerUrl(), dest, null);
		return dest;
	}

	/** Launches the downloaded installer (shows a minimal progress UI) and exits this process so
	 *  it isn't holding its own files open while the installer replaces them. Never returns.
	 *  The WiX Burn .exe bootstrapper accepts the same /passive /norestart switches directly;
	 *  an .msi needs them routed through msiexec /i instead. */
	public static void applyAndExit(Path installerPath, boolean isExe) throws IOException {
		String path = installerPath.toAbsolutePath().toString();
		ProcessBuilder pb = isExe
				? new ProcessBuilder(path, "/passive", "/norestart")
				: new ProcessBuilder("msiexec", "/i", path, "/passive", "/norestart");
		pb.start();
		System.exit(0);
	}

	private SelfUpdater() {
	}
}
