package dev.mcpfabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcpfabric.McpFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persistent configuration for the bridge, stored at {@code config/mcpfabric.config.json}.
 * An auth token is generated on first run and remains in the config file so it does not leak into
 * logs. Copy it into the MCP server's {@code MCPFABRIC_TOKEN} environment variable.
 */
public final class McpConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bind host. Keep on loopback unless you really know what you are doing. */
	public String host = "127.0.0.1";
	public int port = 25599;
	/** Shared secret required in the Authorization: Bearer header. */
	public String token = "";
	/** When false, the bridge accepts unauthenticated requests (loopback only — use with care). */
	public boolean requireAuth = true;

	/** Max time a single RPC may block the game thread before timing out. */
	public int callTimeoutMs = 8000;

	/**
	 * Clear held controls when no bridge request arrives for this long (ms).
	 * Protects the player when AIRI disappears while a movement command is
	 * active. Default 30000; set 0 to disable the watchdog.
	 */
	public int heartbeatTimeoutMs = 30000;

	// capability gates ------------------------------------------------------------------------
	public boolean enableWorldWrite = true;
	public boolean enableCommands = true;
	public boolean enablePlayerControl = true;
	public boolean enableVision = true;

	/**
	 * R9: ranged shots aim at the target's current position plus a linear lead
	 * estimated from the projectile flight time. Set false to aim straight at
	 * the current position with no lead.
	 */
	public boolean combatAimLead = true;

	/** Survival reflexes (mc-0d). All enabled by default; each can be disabled for A/B tests. */
	public ReflexConfig reflex = new ReflexConfig();

	/** Reflex tuning; see mc-0d-spec.md for the field contract. */
	public static final class ReflexConfig {
		public boolean enabled = true;
		public boolean escapeHazard = true;
		public boolean autoEat = true;
		public boolean defend = true;
		/** MC-4e: place a water bucket below when a long fall is detected. */
		public boolean waterLanding = true;
		/** Fall distance (blocks) that arms the water-bucket landing reflex. */
		public double waterLandingMinFall = 6.0;
		/** Eat when the food level is below this value (1-20). */
		public int hungerThreshold = 14;
		/** Disengage instead of countering when health is below this value. */
		public float defendHealthThreshold = 8;
		/** Counter-attack when health is above the threshold. */
		public boolean attackBack = true;
		/** Keep this distance from the attacker while disengaging. */
		public double disengageDistance = 8;
		/** Same-cause events within this window are merged (ms). */
		public int mergeWindowMs = 3000;
	}

	public transient Path source;

	public static McpConfig load() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		Path file = dir.resolve("mcpfabric.config.json");
		McpConfig cfg;
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file), McpConfig.class);
				if (cfg == null) cfg = new McpConfig();
			} catch (Exception e) {
				McpFabric.LOGGER.error("[mcpfabric] failed to read config, using defaults", e);
				cfg = new McpConfig();
			}
		} else {
			cfg = new McpConfig();
		}
		if (cfg.token == null || cfg.token.isBlank()) {
			cfg.token = UUID.randomUUID().toString().replace("-", "");
		}
		cfg.source = file;
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			if (source == null) {
				source = FabricLoader.getInstance().getConfigDir().resolve("mcpfabric.config.json");
			}
			Files.createDirectories(source.getParent());
			Files.writeString(source, GSON.toJson(this));
		} catch (IOException e) {
			McpFabric.LOGGER.error("[mcpfabric] failed to write config", e);
		}
	}
}
