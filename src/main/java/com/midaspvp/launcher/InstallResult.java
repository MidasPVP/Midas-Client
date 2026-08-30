package com.midaspvp.launcher;

import com.google.gson.JsonArray;

import java.nio.file.Path;
import java.util.List;

/** Everything needed to build a launch command, after downloading/resolving is done. */
public final class InstallResult {
	public final String mainClass;
	public final List<Path> classpath;
	public final JsonArray gameArgs; // raw, unsubstituted — mix of strings and {rules,value} objects
	public final JsonArray jvmArgs;  // raw, unsubstituted — mix of strings and {rules,value} objects
	public final Path assetsDir;
	public final String assetIndexId;

	public InstallResult(String mainClass, List<Path> classpath, JsonArray gameArgs, JsonArray jvmArgs, Path assetsDir, String assetIndexId) {
		this.mainClass = mainClass;
		this.classpath = classpath;
		this.gameArgs = gameArgs;
		this.jvmArgs = jvmArgs;
		this.assetsDir = assetsDir;
		this.assetIndexId = assetIndexId;
	}
}
