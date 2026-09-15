package dev.mcpfabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Versioned projectile profiles for Minecraft 1.21.1 (projectile-aiming CD-B1).
 *
 * <p>Every constant below was read from the mapped 1.21.1 classes with
 * {@code javap -p -c}; the {@code Class#method} that produced it is named in the
 * constant's comment. A projectile that has no profile here is never simulated
 * with a guessed speed: the caller must report {@code unsupported_projectile_profile}.
 *
 * <p>This class only publishes the profile table. The per-tick simulation and
 * the intercept solver live in the main process (AIRI) for this batch; the
 * client keeps its existing aim task. The profile ids and the version are the
 * contract both sides share.
 */
public final class Ballistics {
	/** Minecraft version every constant was extracted from. */
	public static final String PROFILE_VERSION = "1.21.1";

	private Ballistics() {}

	/**
	 * Builds the JSON payload for {@code combat.ballistics}.
	 *
	 * <p>The shape mirrors {@code ProjectileProfile} in the AIRI game host:
	 * {@code { version, profiles: [{ id, projectileType, launcherItem, ammo,
	 * gravity, airInertia, waterInertia, speed, minChargeTicks, yawOffsetDeg,
	 * pitchOffsetDeg, inheritShooterVelocity, impactRadius, impactKind,
	 * source }] }}.
	 */
	public static JsonObject profilesJson() {
		JsonObject root = new JsonObject();
		root.addProperty("version", PROFILE_VERSION);
		JsonArray profiles = new JsonArray();
		profiles.add(arrow("bow-arrow", "minecraft:bow", "minecraft:arrow", 3.0F, 0, true,
				"BowItem#releaseUsing/#getPowerForTime, Projectile#getMovementToShoot, AbstractArrow#tick"));
		profiles.add(arrow("crossbow-arrow", "minecraft:crossbow", "minecraft:arrow", 3.15F, 0, false,
				"CrossbowItem#getShootingPower/#shootProjectile, Projectile#shoot, AbstractArrow#tick"));
		profiles.add(arrow("spectral-arrow", "minecraft:bow", "minecraft:spectral_arrow", 3.0F, 0, true,
				"SpectralArrow extends AbstractArrow, BowItem#releaseUsing"));
		profiles.add(arrow("tipped-arrow", "minecraft:bow", "minecraft:tipped_arrow", 3.0F, 0, true,
				"Arrow with PotionContents, BowItem#releaseUsing"));
		profiles.add(area("firework-rocket", "minecraft:crossbow", "minecraft:firework_rocket", 1.6F, 0, false, 3.0D,
				"CrossbowItem#getShootingPower (firework 1.6f), FireworkRocketEntity#tick"));
		profiles.add(trident());
		profiles.add(throwable("snowball", "minecraft:snowball", 1.5F, 0.03D, 0.8F, 0.0F, 0.0F,
				"SnowballItem#use, ThrowableProjectile#getDefaultGravity/#tick"));
		profiles.add(throwable("splash-potion", "minecraft:splash_potion", 0.5F, 0.05D, 0.8F, 4.0D, -20.0F,
				"ThrowablePotionItem#use, ThrownPotion#getDefaultGravity/#applySplash"));
		profiles.add(throwable("lingering-potion", "minecraft:lingering_potion", 0.5F, 0.05D, 0.8F, 3.0D, -20.0F,
				"ThrowablePotionItem#use, ThrownPotion#getDefaultGravity/#makeAreaOfEffectCloud"));
		root.add("profiles", profiles);
		return root;
	}

	private static JsonObject base(String id, String projectileType, String launcherItem, String ammo,
			double gravity, double airInertia, double waterInertia, float speed, int minChargeTicks,
			boolean inheritShooterVelocity, String source) {
		JsonObject profile = new JsonObject();
		profile.addProperty("id", id);
		profile.addProperty("version", PROFILE_VERSION);
		profile.addProperty("projectileType", projectileType);
		profile.addProperty("launcherItem", launcherItem);
		profile.addProperty("ammo", ammo);
		profile.addProperty("gravity", gravity);
		profile.addProperty("airInertia", airInertia);
		profile.addProperty("waterInertia", waterInertia);
		profile.addProperty("speed", speed);
		profile.addProperty("minChargeTicks", minChargeTicks);
		profile.addProperty("yawOffsetDeg", 0.0F);
		profile.addProperty("pitchOffsetDeg", 0.0F);
		profile.addProperty("inheritShooterVelocity", inheritShooterVelocity);
		profile.addProperty("source", source);
		return profile;
	}

	private static JsonObject arrow(String id, String launcherItem, String ammo, float speed, int minChargeTicks,
			boolean inheritShooterVelocity, String source) {
		// AbstractArrow#getDefaultGravity 0.05d, #tick air inertia 0.99f,
		// #getWaterInertia 0.6f, spawn at eyeY - 0.1.
		JsonObject profile = base(id, "minecraft:arrow", launcherItem, ammo, 0.05D, 0.99D, 0.6D, speed,
				minChargeTicks, inheritShooterVelocity, source);
		JsonObject impact = new JsonObject();
		impact.addProperty("kind", "single");
		impact.addProperty("radius", 0.0D);
		profile.add("impact", impact);
		return profile;
	}

	private static JsonObject area(String id, String launcherItem, String ammo, float speed, int minChargeTicks,
			boolean inheritShooterVelocity, double radius, String source) {
		JsonObject profile = base(id, "minecraft:firework_rocket", launcherItem, ammo, 0.05D, 0.99D, 0.99D, speed,
				minChargeTicks, inheritShooterVelocity, source);
		JsonObject impact = new JsonObject();
		impact.addProperty("kind", "area");
		impact.addProperty("radius", radius);
		profile.add("impact", impact);
		return profile;
	}

	private static JsonObject trident() {
		// TridentItem#releaseUsing speed 2.5f and minimum hold 10;
		// ThrownTrident#getWaterInertia 0.99f; AbstractArrow#getDefaultGravity 0.05d.
		JsonObject profile = base("trident", "minecraft:trident", "minecraft:trident", "minecraft:trident",
				0.05D, 0.99D, 0.99D, 2.5F, 10, true,
				"TridentItem#releaseUsing, ThrownTrident#getWaterInertia, AbstractArrow#tick");
		JsonObject impact = new JsonObject();
		impact.addProperty("kind", "single");
		impact.addProperty("radius", 0.0D);
		profile.add("impact", impact);
		return profile;
	}

	private static JsonObject throwable(String id, String item, float speed, double gravity, double waterInertia,
			double impactRadius, float pitchOffsetDeg, String source) {
		// ThrowableProjectile#tick air inertia 0.99f and water inertia 0.8f;
		// #getDefaultGravity 0.03d for a snowball, ThrownPotion overrides 0.05d.
		JsonObject profile = base(id, item, item, item, gravity, 0.99D, waterInertia, speed, 0, true, source);
		profile.addProperty("yawOffsetDeg", 0.0F);
		profile.addProperty("pitchOffsetDeg", pitchOffsetDeg);
		JsonObject impact = new JsonObject();
		impact.addProperty("kind", impactRadius > 0.0D ? "area" : "single");
		impact.addProperty("radius", impactRadius);
		profile.add("impact", impact);
		return profile;
	}
}
