package me.chocolf.moneyfrommobs.utils;

import org.bukkit.Bukkit;

import me.chocolf.moneyfrommobs.MoneyFromMobs;

public class VersionUtils {

	// Minecraft 26.1 and later use year based versions (26.3 instead of 1.21.x). The plugin has no code specific to
	// those versions, so they are treated as the last 1.x version: 1.21.
	private static final int LAST_LEGACY_VERSION_NUMBER = 21;

	private static final String bukkitVersion = Bukkit.getServer().getBukkitVersion().split("-")[0];
	private static final int versionNumber = parseVersionNumber(bukkitVersion);

	// 1.21.11 -> 21, 1.9 -> 9, 26.3 -> 21
	private static int parseVersionNumber(String version) {
		String[] parts = version.split("\\.");
		try {
			if (Integer.parseInt(parts[0]) == 1 && parts.length > 1)
				return Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException ignored) {
		}
		return LAST_LEGACY_VERSION_NUMBER;
	}


	public static String getBukkitVersion() {
		return bukkitVersion;
	}
	
	public static int getVersionNumber() {
		return versionNumber;
	}

	public static double getPluginVersion() {
		return Double.parseDouble(MoneyFromMobs.getInstance().getDescription().getVersion());
	}
	
}
