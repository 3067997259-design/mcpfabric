package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Vehicle observation for the CD-V1 travel session.
 *
 * <p>These reads expose the concrete state the server-side travel session cannot
 * infer from the player box: horse taming/saddle/owner, boat water state, and
 * minecart speed plus the real rail shape/power below the cart. A read that
 * cannot find the entity reports an explicit absence rather than a zeroed
 * vehicle. {@code vehicle.boardUuid} mounts one exact UUID, so a nearby object
 * of another type is never taken by mistake.
 */
public final class VehicleHandlers {
	private VehicleHandlers() {}

	public static void register(RpcRouter router) {
		router.register("vehicle.observe", ctx -> ClientMc.call(() -> {
			UUID uuid = parseUuid(ctx.getString("uuid"));
			Entity entity = findEntity(uuid);
			JsonObject o = new JsonObject();
			if (entity == null) {
				o.addProperty("uuid", uuid.toString());
				o.addProperty("absence", "out-of-range");
				return o;
			}
			o.addProperty("uuid", uuid.toString());
			o.add("entity", describe(entity));
			return o;
		}));

		router.register("vehicle.query", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			double radius = ctx.optDouble("radius", 8.0);
			String kind = ctx.optString("kind", null);
			JsonArray vehicles = new JsonArray();
			int total = 0;
			for (Entity entity : p.level().getEntities(p, p.getBoundingBox().inflate(radius))) {
				if (!isRideable(entity))
					continue;
				if (kind != null && !kindMatches(entity, kind))
					continue;
				total++;
				vehicles.add(describe(entity));
			}
			JsonObject o = new JsonObject();
			o.add("vehicles", vehicles);
			o.addProperty("total", total);
			o.addProperty("returned", vehicles.size());
			return o;
		}));

		router.register("vehicle.boardUuid", ctx -> ClientMc.call(() -> {
			requireControl();
			UUID uuid = parseUuid(ctx.getString("uuid"));
			Entity entity = findEntity(uuid);
			JsonObject o = new JsonObject();
			if (entity == null || !isRideable(entity)) {
				o.addProperty("boarded", false);
				return o;
			}
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			gm.interact(p, entity, InteractionHand.MAIN_HAND);
			p.swing(InteractionHand.MAIN_HAND);
			o.addProperty("boarded", true);
			o.addProperty("uuid", uuid.toString());
			o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			return o;
		}));
	}

	/** True for the rideables the travel session can steer. */
	private static boolean isRideable(Entity entity) {
		return entity instanceof Boat || entity instanceof AbstractMinecart || entity instanceof AbstractHorse;
	}

	/**
	 * Concrete-kind filter for the candidate query.
	 *
	 * A horse family request matches horse, donkey and mule but not camel, so a
	 * caller never receives a camel for the horse flow.
	 */
	private static boolean kindMatches(Entity entity, String kind) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		if (kind.equals("horse-family")) {
			return entity instanceof AbstractHorse && !type.endsWith(":camel");
		}
		if (kind.equals("horse"))
			return type.equals("minecraft:horse") || type.equals("minecraft:donkey") || type.equals("minecraft:mule");
		if (kind.equals("boat"))
			return entity instanceof Boat;
		if (kind.equals("minecart"))
			return entity instanceof AbstractMinecart;
		return type.equals(kind);
	}

	/** Full observation of one entity, including its type-specific state. */
	private static JsonObject describe(Entity entity) {
		JsonObject o = new JsonObject();
		o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		o.addProperty("uuid", entity.getUUID().toString());
		o.addProperty("x", entity.getX());
		o.addProperty("y", entity.getY());
		o.addProperty("z", entity.getZ());
		o.addProperty("yaw", entity.getYRot());
		o.addProperty("width", entity.getBbWidth());
		o.addProperty("height", entity.getBbHeight());
		Vec3 motion = entity.getDeltaMovement();
		JsonObject motionJson = new JsonObject();
		motionJson.addProperty("x", motion.x);
		motionJson.addProperty("y", motion.y);
		motionJson.addProperty("z", motion.z);
		o.add("motion", motionJson);
		List<Entity> passengers = entity.getPassengers();
		JsonArray passengerUuids = new JsonArray();
		for (Entity passenger : passengers)
			passengerUuids.add(passenger.getUUID().toString());
		o.add("passengers", passengerUuids);
		o.addProperty("free", passengers.isEmpty());
		Entity controlling = entity.getControllingPassenger();
		if (controlling != null)
			o.addProperty("controller", controlling.getUUID().toString());

		if (entity instanceof AbstractHorse horse)
			describeHorse(horse, o);
		else if (entity instanceof Boat boat)
			describeBoat(boat, o);
		else if (entity instanceof AbstractMinecart cart)
			describeMinecart(cart, o);
		return o;
	}

	private static void describeHorse(AbstractHorse horse, JsonObject o) {
		o.addProperty("tamed", horse.isTamed());
		o.addProperty("saddled", horse.isSaddled());
		UUID owner = horse.getOwnerUUID();
		if (owner != null)
			o.addProperty("owner", owner.toString());
		o.addProperty("health", horse.getHealth());
		try {
			o.addProperty("jumpStrength", horse.getAttributeValue(Attributes.JUMP_STRENGTH));
		} catch (RuntimeException ignored) {
			// The attribute read is optional; a failure must not fake a value.
		}
		boolean controlled = horse.getControllingPassenger() != null;
		o.addProperty("controlledByPassenger", controlled);
	}

	private static void describeBoat(Boat boat, JsonObject o) {
		o.addProperty("inWater", boat.isInWater());
		boolean left = boat.getPaddleState(0);
		boolean right = boat.getPaddleState(1);
		o.addProperty("paddleSide", left && !right ? "left" : right && !left ? "right" : "none");
	}

	private static void describeMinecart(AbstractMinecart cart, JsonObject o) {
		Vec3 motion = cart.getDeltaMovement();
		o.addProperty("speed", Math.sqrt(motion.x * motion.x + motion.z * motion.z));
		// NOTICE: a cart riding a rail has its position inside the rail's own
		// cell (rail block y + 0.0625 on flat track). The previous read used
		// `y - 0.5`, which landed in the block under the rail, so a powered rail
		// reported onRail:false and powered:false and every cart trip failed
		// with rail_not_powered (live F-15, 2026-09-17: a verified
		// powered_rail[powered=true] under the cart still read as unpowered).
		// The cart's own block cell is the rail cell on flat and sloped track.
		BlockPos cell = cart.blockPosition();
		BlockState state = cart.level().getBlockState(cell);
		boolean rail = state.getBlock() instanceof BaseRailBlock;
		o.addProperty("onRail", rail);
		boolean powered = state.getBlock() instanceof PoweredRailBlock && state.getValue(PoweredRailBlock.POWERED);
		o.addProperty("powered", powered);
		if (rail) {
			RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
			o.addProperty("railShape", shape.name().toLowerCase(Locale.ROOT));
		}
	}

	private static Entity findEntity(UUID uuid) throws RpcException {
		for (Entity entity : ClientMc.level().entitiesForRendering()) {
			if (entity.getUUID().equals(uuid))
				return entity;
		}
		return null;
	}

	private static UUID parseUuid(String value) throws RpcException {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + value);
		}
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
