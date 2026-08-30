package com.midaspvp.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Looks up a player's MidasPVP tier ranks from the tiers site's public API. */
public final class TierApi {
	private static final String PLAYERS_URL = "https://midaspvp.com/api/players";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** Blocking — run off the JavaFX thread. Returns {"found":false} if the name isn't on the board or the lookup fails. */
	public static String fetchPlayer(String username) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(PLAYERS_URL))
					.timeout(Duration.ofSeconds(10)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return "{\"found\":false}";

			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!root.has("players")) return "{\"found\":false}";
			JsonArray players = root.getAsJsonArray("players");
			for (JsonElement el : players) {
				JsonObject p = el.getAsJsonObject();
				if (p.has("name") && p.get("name").getAsString().equalsIgnoreCase(username)) {
					JsonObject out = new JsonObject();
					out.addProperty("found", true);
					out.addProperty("name", p.get("name").getAsString());
					out.addProperty("region", p.has("region") ? p.get("region").getAsString() : "");
					out.add("tiers", p.has("tiers") ? p.get("tiers") : new JsonObject());
					return out.toString();
				}
			}
			return "{\"found\":false}";
		} catch (Exception e) {
			return "{\"found\":false}";
		}
	}

	private TierApi() {
	}
}
