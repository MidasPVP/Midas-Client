package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Evaluates the "rules" arrays Mojang's version JSON uses to gate libraries/arguments by OS. */
public final class OsMatch {

	public static final String OS_NAME = detectOsName();
	public static final String OS_ARCH = System.getProperty("os.arch", "").toLowerCase();

	private static String detectOsName() {
		String name = System.getProperty("os.name", "").toLowerCase();
		if (name.contains("win")) return "windows";
		if (name.contains("mac") || name.contains("darwin")) return "osx";
		return "linux";
	}

	/** True if there are no rules (always allowed), or the rules evaluate to "allow" for this OS. */
	public static boolean allowed(JsonObject entry) {
		if (!entry.has("rules")) return true;
		JsonArray rules = entry.getAsJsonArray("rules");
		boolean allowed = false;
		for (var el : rules) {
			JsonObject rule = el.getAsJsonObject();
			boolean matches = true;
			if (rule.has("os")) {
				JsonObject os = rule.getAsJsonObject("os");
				if (os.has("name") && !os.get("name").getAsString().equals(OS_NAME)) matches = false;
				if (os.has("arch") && !os.get("arch").getAsString().equals(OS_ARCH)) matches = false;
			}
			if (matches) {
				allowed = rule.get("action").getAsString().equals("allow");
			}
		}
		return allowed;
	}

	private OsMatch() {
	}
}
