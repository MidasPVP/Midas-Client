package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the java command line from an InstallResult + a chosen offline username, and starts the process. */
public final class GameLauncher {

	/** Note: deliberately does NOT use Mojang's --quickPlayMultiplayer to auto-connect on launch.
	 *  It would race the in-game Microsoft login (In-Game Account Switcher) — the game connects
	 *  the instant the world loads, using whatever session is active at that moment, which is
	 *  always the local offline profile since the real login only happens after the player opens
	 *  the Account Switcher manually. That's fine for a cracked/offline-mode server but silently
	 *  breaks premium ones. Featured servers are instead added to the player's own Multiplayer
	 *  server list (see ServerListNbt) so they can log in first, then connect when ready. */
	public static Process launch(InstallResult install, Path gameDir, Path nativesDir, String javaExecutable, String username) throws IOException {
		Files.createDirectories(gameDir);
		Files.createDirectories(nativesDir);

		String offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.toString().replace("-", "");

		Map<String, String> placeholders = new HashMap<>();
		placeholders.put("auth_player_name", username);
		placeholders.put("version_name", "midas-fabric");
		placeholders.put("game_directory", gameDir.toString());
		placeholders.put("assets_root", install.assetsDir.toString());
		placeholders.put("assets_index_name", install.assetIndexId);
		placeholders.put("auth_uuid", offlineUuid);
		placeholders.put("auth_access_token", "0");
		placeholders.put("clientid", "");
		placeholders.put("auth_xuid", "0");
		placeholders.put("user_type", "legacy");
		placeholders.put("version_type", "release");
		placeholders.put("natives_directory", nativesDir.toString());
		placeholders.put("launcher_name", "midas-launcher");
		placeholders.put("launcher_version", "1.0.0");
		placeholders.put("classpath", install.classpath.stream()
				.map(p -> p.toAbsolutePath().toString())
				.collect(Collectors.joining(java.io.File.pathSeparator)));

		Set<String> features = Set.of();

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);
		// Prefer IPv4 for outgoing connections. A very common real-world cause of "some servers
		// just time out" reports across the whole Minecraft community: the JVM tries a server's
		// IPv6 address first when one exists, and on a network where IPv6 is enabled but not
		// actually routed properly, that attempt hangs until timeout before ever falling back to
		// the IPv4 address that would have worked immediately.
		command.add("-Djava.net.preferIPv4Stack=true");
		command.addAll(resolveArgs(install.jvmArgs, placeholders, features));
		command.add(install.mainClass);
		command.addAll(resolveArgs(install.gameArgs, placeholders, features));

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(gameDir.toFile());
		pb.redirectErrorStream(true);
		return pb.start();
	}

	/** Resolves a mixed array of plain-string and {rules,value} entries, substituting ${...} placeholders.
	 *  Entries whose rules require a "features" flag only apply if that flag is in enabledFeatures;
	 *  entries gated only by "os" are evaluated normally regardless. */
	private static List<String> resolveArgs(JsonArray raw, Map<String, String> placeholders, Set<String> enabledFeatures) {
		List<String> out = new ArrayList<>();
		for (JsonElement el : raw) {
			if (el.isJsonPrimitive()) {
				out.add(substitute(el.getAsString(), placeholders));
				continue;
			}
			JsonObject entry = el.getAsJsonObject();
			if (!ruleAllows(entry, enabledFeatures)) continue;
			JsonElement value = entry.get("value");
			if (value.isJsonArray()) {
				for (JsonElement v : value.getAsJsonArray()) out.add(substitute(v.getAsString(), placeholders));
			} else {
				out.add(substitute(value.getAsString(), placeholders));
			}
		}
		return out;
	}

	private static boolean ruleAllows(JsonObject entry, Set<String> enabledFeatures) {
		if (!entry.has("rules")) return true;
		boolean allowed = false;
		for (JsonElement el : entry.getAsJsonArray("rules")) {
			JsonObject rule = el.getAsJsonObject();
			boolean matches = true;
			if (rule.has("features")) {
				JsonObject features = rule.getAsJsonObject("features");
				for (var e : features.entrySet()) {
					boolean required = e.getValue().getAsBoolean();
					boolean have = enabledFeatures.contains(e.getKey());
					if (required != have) matches = false;
				}
			}
			if (rule.has("os")) {
				JsonObject os = rule.getAsJsonObject("os");
				if (os.has("name") && !os.get("name").getAsString().equals(OsMatch.OS_NAME)) matches = false;
				if (os.has("arch") && !os.get("arch").getAsString().equals(OsMatch.OS_ARCH)) matches = false;
			}
			if (matches) allowed = rule.get("action").getAsString().equals("allow");
		}
		return allowed;
	}

	private static String substitute(String s, Map<String, String> placeholders) {
		String result = s;
		for (var entry : placeholders.entrySet()) {
			result = result.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		return result;
	}

	private GameLauncher() {
	}
}
