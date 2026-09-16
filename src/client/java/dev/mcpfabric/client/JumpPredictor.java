package dev.mcpfabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Short-range jump prediction (Step 3 batch 4).
 *
 * <p>Simulates the player's own physics a tick at a time on copied numbers and
 * boxes: input acceleration, the ground and air speed retention, gravity with
 * the vanilla jump impulse, and real collision clipping against the world's
 * block shapes. The predicted trail is recorded next to the real one so the two
 * can be aligned before the prediction is allowed to choose a takeoff or an
 * in-air correction.
 *
 * <p>This never moves the real player: it only reads its box and state.
 */
public final class JumpPredictor {
	private JumpPredictor() {}

	/** Vanilla constants, matching the acceptance review's measured values. */
	public static final double JUMP_VELOCITY = 0.42;
	public static final double GRAVITY = 0.08;
	public static final double VERTICAL_DRAG = 0.98;
	public static final double GROUND_ACCELERATION = 0.098;
	public static final double AIR_ACCELERATION = 0.0196;
	public static final double GROUND_RETENTION = 0.546;
	public static final double AIR_RETENTION = 0.91;

	/** One simulated tick. */
	public static final class Tick {
		public double x, y, z;
		public double vx, vy, vz;
		public boolean onGround;
	}

	/** One simulated option: where it takes off, lands, and comes to rest. */
	public static final class Result {
		public final List<Tick> trail = new ArrayList<>();
		/** Tick of the first touchdown after the takeoff, -1 when it never lands. */
		public int landTick = -1;
		public double landX, landY, landZ;
		/** Where the bot coasts to rest after the touchdown. */
		public double restX, restY, restZ;
		/**
		 * True when the rest tick still touches a floor, so the option does not
		 * slide off the far edge of its landing. This comes from the simulated
		 * contact itself, not from a block lookup under the centre: the audit's
		 * successful chain landing had its centre 0.08 outside the target block
		 * while the feet box already covered the support.
		 */
		public boolean staysOnSupport;
		/** Block id under the rest footprint, or empty when unknown. */
		public String restSupport = "";
		/** Horizontal distance from the rest position to the handoff target. */
		public double restToTarget = Double.NaN;
	}

	/**
	 * Simulates one option from the player's current state.
	 *
	 * @param inputForward forward key in [-1, 1]
	 * @param inputStrafe strafe-right key in [-1, 1]
	 * @param jump true to press jump on the first tick (the takeoff option)
	 * @param targetX handoff target for the rest-distance report
	 * @param maxTicks hard bound on the flight simulation
	 * @param settleTicks ticks of no-input coasting simulated after touchdown
	 */
	public static Result simulate(
			LocalPlayer p,
			double inputForward,
			double inputStrafe,
			boolean jump,
			double targetX,
			double targetZ,
			int maxTicks,
			int settleTicks) {
		return simulate(p, inputForward, inputStrafe, jump, targetX, targetZ, maxTicks, settleTicks, 0, 0, 0);
	}

	/**
	 * Simulates one takeoff candidate.
	 *
	 * @param preTicks ticks of repositioning input applied before the jump, so a
	 * candidate can shift the takeoff position or heading (for example a strafe
	 * that lines the flight up beside a pillar the direct diagonal clips)
	 * @param preForward forward input during those ticks
	 * @param preStrafe strafe-right input during those ticks
	 */
	public static Result simulate(
			LocalPlayer p,
			double inputForward,
			double inputStrafe,
			boolean jump,
			double targetX,
			double targetZ,
			int maxTicks,
			int settleTicks,
			int preTicks,
			double preForward,
			double preStrafe) {
		Result result = new Result();
		Level level = p.level();
		Vec3 position = p.position();
		Vec3 motion = p.getDeltaMovement();
		AABB box = p.getBoundingBox();
		boolean onGround = p.onGround();
		boolean jumpIssued = false;

		for (int tick = 1; tick <= maxTicks + settleTicks; tick++) {
			boolean settling = result.landTick >= 0;
			boolean pre = tick <= preTicks;
			// The executor re-aims on the target every tick, so the simulated
			// input direction is recomputed from the simulated position: the
			// successful chain hop turned from -47.8 to -4.5 degrees while it
			// slid around the pillar corner, and a single fixed heading cannot
			// represent that control.
			double aimX = targetX - position.x;
			double aimZ = targetZ - position.z;
			if (aimX * aimX + aimZ * aimZ < 1e-8) {
				aimX = 0;
				aimZ = 1;
			}
			double yawRad = Math.atan2(-aimX, aimZ);
			double sin = Math.sin(yawRad);
			double cos = Math.cos(yawRad);
			double forward = settling ? 0 : pre ? preForward : inputForward;
			double strafe = settling ? 0 : pre ? preStrafe : inputStrafe;
			// Minecraft yaw frame: forward = (-sin, cos), strafe-right = (-cos, -sin).
			double directionX = forward * -sin + strafe * -cos;
			double directionZ = forward * cos + strafe * -sin;
			double length = Math.hypot(directionX, directionZ);
			if (length > 1e-6) {
				directionX /= length;
				directionZ /= length;
			}

			boolean jumping = jump && !pre && !jumpIssued && onGround && result.landTick < 0;
			if (jumping) {
				motion = new Vec3(motion.x, JUMP_VELOCITY, motion.z);
				jumpIssued = true;
			}
			// Vanilla order (audited against the 1.21.1 bytecode): the takeoff
			// tick itself still moves with the ground coefficients because
			// jumpFromGround sets the vertical speed without clearing the
			// grounded state; only later ticks use the air coefficients. Using
			// the air branch here dropped the first-tick step from 0.098 to
			// 0.0196 and made the whole hop land 0.288 short.
			boolean air = !onGround;
			double acceleration = air ? AIR_ACCELERATION : GROUND_ACCELERATION;
			double retention = air ? AIR_RETENTION : GROUND_RETENTION;
			double mx = settling ? motion.x : motion.x + acceleration * directionX;
			double mz = settling ? motion.z : motion.z + acceleration * directionZ;
			// This tick moves with the current vertical velocity; gravity and
			// drag shape the *next* tick (live alignment run).
			double my = motion.y;
			Vec3 requested = new Vec3(mx, my, mz);
			// The entity-aware helper clips a movement against the world's block
			// shapes for the given box; the real player is never moved. It does
			// not model the 0.6 auto-step, so walked takeoffs onto a low step can
			// be predicted short: conservative, never optimistic.
			Vec3 clipped = Entity.collideBoundingBox(p, requested, box, level, List.of());
			position = position.add(clipped);
			box = box.move(clipped);
			// A clipped downward move is floor contact. It only counts as a
			// landing while airborne: on the ground the standing player's own
			// -0.0784 vertical speed is clipped every tick, and treating that as
			// a touchdown made every pre-move candidate think it had landed and
			// then refuse to issue its jump.
			boolean floorContact = my < 0 && clipped.y != my;
			onGround = floorContact;
			motion = new Vec3(
					clipped.x * retention,
					(clipped.y - GRAVITY) * VERTICAL_DRAG,
					clipped.z * retention);

			Tick state = new Tick();
			state.x = position.x;
			state.y = position.y;
			state.z = position.z;
			state.vx = motion.x;
			state.vy = motion.y;
			state.vz = motion.z;
			state.onGround = onGround;
			result.trail.add(state);

			if (floorContact && air && jumpIssued && result.landTick < 0) {
				result.landTick = tick;
				result.landX = position.x;
				result.landY = position.y;
				result.landZ = position.z;
			}
			if (result.landTick >= 0 && tick >= result.landTick + settleTicks) {
				result.restX = position.x;
				result.restY = position.y;
				result.restZ = position.z;
				break;
			}
			if (result.landTick >= 0 && tick > result.landTick && onGround
					&& Math.hypot(motion.x, motion.z) < 0.001) {
				result.restX = position.x;
				result.restY = position.y;
				result.restZ = position.z;
				break;
			}
		}

		if (result.landTick >= 0) {
			Tick last = result.trail.get(result.trail.size() - 1);
			// The rest is supported when the last simulated tick still touched a
			// floor; a flight that touches the step and then slides off its far
			// edge ends airborne and is refused by the caller.
			result.staysOnSupport = last.onGround;
			result.restSupport = supportName(level, result.restX, result.restY, result.restZ);
			result.restToTarget = Math.hypot(result.restX - targetX, result.restZ - targetZ);
		}
		return result;
	}

	/**
	 * Names the first non-passable block under the feet box.
	 *
	 * @example
	 * supportName(level, 91.542, 81.0, -12.203)
	 * // => 'minecraft:dirt' (the target step, not the block under the centre)
	 */
	private static String supportName(Level level, double x, double y, double z) {
		double half = 0.3;
		int minX = net.minecraft.util.Mth.floor(x - half);
		int maxX = net.minecraft.util.Mth.floor(x + half);
		int minZ = net.minecraft.util.Mth.floor(z - half);
		int maxZ = net.minecraft.util.Mth.floor(z + half);
		int below = net.minecraft.util.Mth.floor(y - 0.1);
		for (int bx = minX; bx <= maxX; bx++) {
			for (int bz = minZ; bz <= maxZ; bz++) {
				net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(bx, below, bz);
				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
				if (state.isAir())
					continue;
				if (state.getCollisionShape(level, pos).isEmpty())
					continue;
				return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			}
		}
		return "";
	}

	/** Compact JSON summary of one prediction for the status trace. */
	public static JsonObject toJson(Result result, int trailLimit) {
		JsonObject object = new JsonObject();
		object.addProperty("landTick", result.landTick);
		if (result.landTick >= 0) {
			object.addProperty("landX", result.landX);
			object.addProperty("landY", result.landY);
			object.addProperty("landZ", result.landZ);
			object.addProperty("restX", result.restX);
			object.addProperty("restY", result.restY);
			object.addProperty("restZ", result.restZ);
			object.addProperty("staysOnSupport", result.staysOnSupport);
			object.addProperty("restSupport", result.restSupport);
			object.addProperty("restToTarget", result.restToTarget);
		}
		JsonArray trail = new JsonArray();
		int start = Math.max(0, result.trail.size() - trailLimit);
		for (int index = start; index < result.trail.size(); index++) {
			Tick tick = result.trail.get(index);
			JsonObject entry = new JsonObject();
			entry.addProperty("x", tick.x);
			entry.addProperty("y", tick.y);
			entry.addProperty("z", tick.z);
			entry.addProperty("vx", tick.vx);
			entry.addProperty("vz", tick.vz);
			entry.addProperty("onGround", tick.onGround);
			trail.add(entry);
		}
		object.add("trail", trail);
		return object;
	}
}
