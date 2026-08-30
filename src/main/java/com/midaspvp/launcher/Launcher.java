package com.midaspvp.launcher;

/**
 * Real jar entry point. Must NOT extend javafx.application.Application itself — the JVM refuses to
 * start a classpath (non-modular) JavaFX app whose declared Main-Class does, with a misleading
 * "JavaFX runtime components are missing" error even though the jars are present. This indirection
 * avoids that check.
 */
public final class Launcher {
	public static void main(String[] args) {
		Main.main(args);
	}
}
