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
		/** True when the rest position still stands on the same support block. */
		public boolean staysOnSupport;
		/** Block id under the rest position, or empty when unknown. */
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

		float yaw = p.getYRot();
		double sin = Math.sin(Math.toRadians(yaw));
		double cos = Math.cos(Math.toRadians(yaw));
		// Minecraft yaw frame: forward = (-sin, cos), strafe-right = (-cos, -sin).
		double inputX = inputForward * -sin + inputStrafe * -cos;
		double inputZ = inputForward * cos + inputStrafe * -sin;
		double preInputX = preForward * -sin + preStrafe * -cos;
		double preInputZ = preForward * cos + preStrafe * -sin;
		double length = Math.hypot(inputX, inputZ);
		if (length > 1e-6) {
			inputX /= length;
			inputZ /= length;
		}
		double preLength = Math.hypot(preInputX, preInputZ);
		if (preLength > 1e-6) {
			preInputX /= preLength;
			preInputZ /= preLength;
		}

		for (int tick = 1; tick <= maxTicks + settleTicks; tick++) {
			boolean settling = result.landTick >= 0;
			boolean pre = tick <= preTicks;
			boolean air = !onGround;
			double acceleration = air ? AIR_ACCELERATION : GROUND_ACCELERATION;
			double retention = air ? AIR_RETENTION : GROUND_RETENTION;
			double forward = settling ? 0 : pre ? preForward : inputForward;
			double strafe = settling ? 0 : pre ? preStrafe : inputStrafe;

			if (jump && !pre && tick == preTicks + 1 && onGround && result.landTick < 0) {
				motion = new Vec3(motion.x, JUMP_VELOCITY, motion.z);
				onGround = false;
				air = true;
				acceleration = AIR_ACCELERATION;
				retention = AIR_RETENTION;
			}

			// The input vector used this tick follows the pre-takeoff phase too,
			// so a repositioning candidate is simulated with its own heading.
			double directionX = pre ? preInputX : inputX;
			double directionZ = pre ? preInputZ : inputZ;
			double mx = settling ? motion.x : motion.x + acceleration * directionX;
			double mz = settling ? motion.z : motion.z + acceleration * directionZ;
			// Vanilla order: this tick moves with the current vertical velocity;
			// gravity and drag shape the *next* tick. Applying them here made the
			// first jump tick rise 0.333 instead of 0.42 and the predicted arc
			// lag the real one (live alignment run).
			double my = motion.y;
			Vec3 requested = new Vec3(mx, my, mz);
			// The entity-aware helper clips a movement against the world's block
			// shapes for the given box; the real player is never moved.
			Vec3 clipped = Entity.collideBoundingBox(p, requested, box, level, List.of());
			position = position.add(clipped);
			box = box.move(clipped);
			boolean landedThisTick = false;
			if (my < 0 && clipped.y != my) {
				// The vertical move was clipped: a floor this tick.
				onGround = true;
				landedThisTick = result.landTick < 0;
				motion = new Vec3(clipped.x, 0, clipped.z);
			}
			else {
				onGround = false;
				// Next tick's vertical velocity: gravity, then drag.
				double nextVy = (clipped.y - GRAVITY) * VERTICAL_DRAG;
				motion = new Vec3(clipped.x * retention, nextVy, clipped.z * retention);
			}

			Tick state = new Tick();
			state.x = position.x;
			state.y = position.y;
			state.z = position.z;
			state.vx = motion.x;
			state.vy = motion.y;
			state.vz = motion.z;
			state.onGround = onGround;
			result.trail.add(state);

			if (landedThisTick) {
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
			net.minecraft.core.BlockPos support = net.minecraft.core.BlockPos.containing(result.landX, result.landY - 0.1, result.landZ);
			net.minecraft.world.level.block.state.BlockState below = level.getBlockState(support);
			result.restSupport = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(below.getBlock()).toString();
			// The rest position must stand on something at the same level: a
			// flight that touches the step and then slides off the far edge is
			// not a safe option. Comparing the exact block would flag a flat
			// walk that simply coasts onto the next pad cell.
			net.minecraft.core.BlockPos restSupport = net.minecraft.core.BlockPos.containing(result.restX, result.restY - 0.1, result.restZ);
			net.minecraft.world.level.block.state.BlockState restBelow = level.getBlockState(restSupport);
			result.staysOnSupport = !restBelow.isAir()
					&& Math.abs(result.restY - restSupport.getY() - 1) <= 0.6;
			result.restToTarget = Math.hypot(result.restX - targetX, result.restZ - targetZ);
		}
		return result;
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
			trail.add(entry);
		}
		object.add("trail", trail);
		return object;
	}
}
