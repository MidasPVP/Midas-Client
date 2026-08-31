package com.midaspvp.launcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal general-purpose NBT reader/writer (uncompressed — servers.dat is stored raw, unlike the
 * gzipped level.dat) used only to add MidasPVP/PowerSMP to the player's Multiplayer server list
 * without ever touching or losing servers the player added themselves.
 *
 * Values round-trip as: Byte, Short, Integer, Long, Float, Double, byte[], String, NbtList,
 * LinkedHashMap<String,Object> (compound), int[], long[].
 */
final class ServerListNbt {
	private static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4,
			TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9,
			TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

	/** A typed NBT list — every element must be the same tag type, per the NBT spec. */
	static final class NbtList {
		final int elementType;
		final List<Object> items;

		NbtList(int elementType, List<Object> items) {
			this.elementType = elementType;
			this.items = items;
		}
	}

	/** Adds `name`/`ip` to servers.dat's "servers" list if no existing entry already has that ip
	 *  (case-insensitive). Leaves every other entry — and every other tag in the file — untouched. */
	static void upsertServer(Path serversDatFile, String name, String ip) throws IOException {
		Map<String, Object> root;
		if (Files.isRegularFile(serversDatFile)) {
			try (DataInputStream in = new DataInputStream(Files.newInputStream(serversDatFile))) {
				root = readRootCompound(in);
			} catch (Exception e) {
				// A corrupt/foreign servers.dat isn't worth crashing the launch over — start fresh
				// rather than touch a file we can't safely parse.
				root = new LinkedHashMap<>();
			}
		} else {
			root = new LinkedHashMap<>();
		}

		@SuppressWarnings("unchecked")
		NbtList servers = (NbtList) root.get("servers");
		if (servers == null || servers.elementType != TAG_COMPOUND) {
			servers = new NbtList(TAG_COMPOUND, new java.util.ArrayList<>());
			root.put("servers", servers);
		}

		for (Object entry : servers.items) {
			if (!(entry instanceof Map)) continue;
			Object existingIp = ((Map<?, ?>) entry).get("ip");
			if (existingIp instanceof String && ((String) existingIp).equalsIgnoreCase(ip)) return;
		}

		Map<String, Object> newEntry = new LinkedHashMap<>();
		newEntry.put("name", name);
		newEntry.put("ip", ip);
		servers.items.add(0, newEntry);

		Files.createDirectories(serversDatFile.getParent());
		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(serversDatFile))) {
			writeRootCompound(out, root);
		}
	}

	private static Map<String, Object> readRootCompound(DataInputStream in) throws IOException {
		int rootType = in.readUnsignedByte();
		if (rootType != TAG_COMPOUND) throw new IOException("servers.dat root is not a compound (tag " + rootType + ")");
		in.readUTF(); // root name, always "" in practice — discarded
		return readCompoundPayload(in);
	}

	private static Map<String, Object> readCompoundPayload(DataInputStream in) throws IOException {
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == TAG_END) return map;
			String name = readNbtString(in);
			map.put(name, readPayload(in, type));
		}
	}

	private static Object readPayload(DataInputStream in, int type) throws IOException {
		switch (type) {
			case TAG_BYTE: return in.readByte();
			case TAG_SHORT: return in.readShort();
			case TAG_INT: return in.readInt();
			case TAG_LONG: return in.readLong();
			case TAG_FLOAT: return in.readFloat();
			case TAG_DOUBLE: return in.readDouble();
			case TAG_BYTE_ARRAY: {
				int len = in.readInt();
				byte[] arr = new byte[len];
				in.readFully(arr);
				return arr;
			}
			case TAG_STRING: return readNbtString(in);
			case TAG_LIST: {
				int elementType = in.readUnsignedByte();
				int count = in.readInt();
				List<Object> items = new java.util.ArrayList<>(Math.max(0, count));
				for (int i = 0; i < count; i++) items.add(readPayload(in, elementType));
				return new NbtList(elementType, items);
			}
			case TAG_COMPOUND: return readCompoundPayload(in);
			case TAG_INT_ARRAY: {
				int len = in.readInt();
				int[] arr = new int[len];
				for (int i = 0; i < len; i++) arr[i] = in.readInt();
				return arr;
			}
			case TAG_LONG_ARRAY: {
				int len = in.readInt();
				long[] arr = new long[len];
				for (int i = 0; i < len; i++) arr[i] = in.readLong();
				return arr;
			}
			default: throw new IOException("Unsupported NBT tag type " + type);
		}
	}

	private static String readNbtString(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		byte[] bytes = new byte[len];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeRootCompound(DataOutputStream out, Map<String, Object> root) throws IOException {
		out.writeByte(TAG_COMPOUND);
		writeNbtString(out, "");
		writeCompoundPayload(out, root);
	}

	private static void writeCompoundPayload(DataOutputStream out, Map<String, Object> map) throws IOException {
		for (var entry : map.entrySet()) {
			int type = tagTypeOf(entry.getValue());
			out.writeByte(type);
			writeNbtString(out, entry.getKey());
			writePayload(out, type, entry.getValue());
		}
		out.writeByte(TAG_END);
	}

	private static void writePayload(DataOutputStream out, int type, Object value) throws IOException {
		switch (type) {
			case TAG_BYTE -> out.writeByte((Byte) value);
			case TAG_SHORT -> out.writeShort((Short) value);
			case TAG_INT -> out.writeInt((Integer) value);
			case TAG_LONG -> out.writeLong((Long) value);
			case TAG_FLOAT -> out.writeFloat((Float) value);
			case TAG_DOUBLE -> out.writeDouble((Double) value);
			case TAG_BYTE_ARRAY -> {
				byte[] arr = (byte[]) value;
				out.writeInt(arr.length);
				out.write(arr);
			}
			case TAG_STRING -> writeNbtString(out, (String) value);
			case TAG_LIST -> {
				NbtList list = (NbtList) value;
				out.writeByte(list.items.isEmpty() ? TAG_END : list.elementType);
				out.writeInt(list.items.size());
				for (Object item : list.items) writePayload(out, list.elementType, item);
			}
			case TAG_COMPOUND -> {
				@SuppressWarnings("unchecked")
				Map<String, Object> compound = (Map<String, Object>) value;
				writeCompoundPayload(out, compound);
			}
			case TAG_INT_ARRAY -> {
				int[] arr = (int[]) value;
				out.writeInt(arr.length);
				for (int v : arr) out.writeInt(v);
			}
			case TAG_LONG_ARRAY -> {
				long[] arr = (long[]) value;
				out.writeInt(arr.length);
				for (long v : arr) out.writeLong(v);
			}
			default -> throw new IOException("Unsupported NBT tag type " + type);
		}
	}

	private static void writeNbtString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeShort(bytes.length);
		out.write(bytes);
	}

	private static int tagTypeOf(Object value) throws IOException {
		if (value instanceof Byte) return TAG_BYTE;
		if (value instanceof Short) return TAG_SHORT;
		if (value instanceof Integer) return TAG_INT;
		if (value instanceof Long) return TAG_LONG;
		if (value instanceof Float) return TAG_FLOAT;
		if (value instanceof Double) return TAG_DOUBLE;
		if (value instanceof byte[]) return TAG_BYTE_ARRAY;
		if (value instanceof String) return TAG_STRING;
		if (value instanceof NbtList) return TAG_LIST;
		if (value instanceof Map) return TAG_COMPOUND;
		if (value instanceof int[]) return TAG_INT_ARRAY;
		if (value instanceof long[]) return TAG_LONG_ARRAY;
		throw new IOException("Don't know how to write NBT value of type " + value.getClass());
	}

	private ServerListNbt() {
	}
}
