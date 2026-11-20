package io.hotmail.com.jacob_vejvoda.infernal_mobs;

import java.util.List;
import java.util.UUID;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Compile-only stand-in for InfernalMobs 5.8 (no longer published in any Maven repository): only the method
 * MoneyFromMobs calls. Never packaged; the real plugin provides the class at runtime.
 */
public class infernal_mobs extends JavaPlugin {
    public List<String> findMobAbilities(UUID id) {
        throw new UnsupportedOperationException();
    }
}
