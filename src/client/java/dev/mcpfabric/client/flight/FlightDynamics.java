package dev.mcpfabric.client.flight;

/**
 * Per-tick elytra physics, ported tick-for-tick from the host's
 * {@code flight/simulation.ts} (R2b cross-replay contract: TS and Java must
 * agree on the same input trace to within float noise).
 *
 * <p>Update order per tick, calibrated against the live telemetry boundary
 * (ab-17 audit 2026-09-21): the recorded sample is taken after the controller
 * writes this tick's input, and the NEXT sample's position matches a glide
 * step with the PRE-thrust velocity; the rocket's thrust reaches the velocity
 * after that displacement. The old order (thrust first) had a single-step
 * position error P95 of 0.54 blocks on 139 boosted ticks — larger than the
 * client's 0.15 inflation and the planner's 0.2 tracking margin — while this
 * order fits to ~2e-5.
 *
 * <ol>
 * <li>look vector from yaw/pitch;</li>
 * <li>gravity term with the cos²-pitch lift factor (uses the pre-thrust
 * velocity);</li>
 * <li>dive term while descending;</li>
 * <li>climb term while looking up (uses the pre-thrust horizontal speed);</li>
 * <li>steady glide pull toward look × horizontal speed;</li>
 * <li>drag (0.99 / 0.98 / 0.99);</li>
 * <li>position += velocity (the displacement of this tick);</li>
 * <li>a requested or ongoing rocket applies its pull to the velocity AFTER
 * the displacement, then its remaining ticks decrement.</li>
 * </ol>
 *
 * <p>Constants come from the same mapped 1.21.1 sources the host profile
 * records. Ignition duration comes from the selected recipe; its random lifetime remains an estimate.
 */
public final class FlightDynamics {
	private FlightDynamics() {}

	/** Default model for a level-one rocket; live ignition supplies its item duration. */
	public static final int ROCKET_BOOST_TICKS = FlightRocket.nominalTicks(1);

	public static final double GRAVITY = 0.08;
	public static final double LIFT_FACTOR = 0.75;
	public static final double DIVE_FACTOR = -0.1;
	public static final double CLIMB_FACTOR = 0.04;
	public static final double CLIMB_THRUST = 3.2;
	public static final double STEADY_PULL = 0.1;
	public static final double HORIZONTAL_DRAG = 0.99;
	public static final double VERTICAL_DRAG = 0.98;
	public static final double LOOK_LENGTH_CAP = 0.4;
	public static final double ROCKET_TARGET_SPEED = 1.5;
	public static final double ROCKET_BASE_ACCEL = 0.1;
	public static final double ROCKET_PULL = 0.5;

	/** One immutable flight state the dynamics step. */
	public static final class State {
		public final double x, y, z;
		public final double vx, vy, vz;
		public final int rocketTicksRemaining;

		public State(double x, double y, double z, double vx, double vy, double vz, int rocketTicksRemaining) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.rocketTicksRemaining = rocketTicksRemaining;
		}
	}

	/** One tick of control. */
	public static final class Input {
		public final float yaw;
		public final float pitch;
		public final boolean useRocket;
		public final int rocketDurationTicks;

		public Input(float yaw, float pitch, boolean useRocket) {
			this(yaw, pitch, useRocket, ROCKET_BOOST_TICKS);
		}

		public Input(float yaw, float pitch, boolean useRocket, int rocketDurationTicks) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.useRocket = useRocket;
			this.rocketDurationTicks = Math.max(1, rocketDurationTicks);
		}
	}

	public static final class Look {
		public final double x, y, z;
		public final double horizontal;
		public final double length;

		Look(double x, double y, double z) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.horizontal = Math.hypot(x, z);
			this.length = Math.sqrt(x * x + y * y + z * z);
		}
	}

	/** {@code getLookAngle} for degrees, host parity with {@code lookVector}. */
	public static Look lookVector(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new Look(
				-Math.sin(yaw) * Math.cos(pitch),
				-Math.sin(pitch),
				Math.cos(yaw) * Math.cos(pitch));
	}

	/** One side-effect-free flight tick; the update order mirrors the host. */
	public static State step(State state, Input input) {
		Look look = lookVector(input.yaw, input.pitch);
		double pitchRad = Math.toRadians(input.pitch);
		double lift = Math.cos(pitchRad) * Math.cos(pitchRad) * Math.min(1, look.length / LOOK_LENGTH_CAP);

		double vx = state.vx;
		double vy = state.vy;
		double vz = state.vz;

		// The glide runs on the PRE-thrust velocity: the live boundary shows the
		// rocket's pull reaching the velocity only after this tick's
		// displacement (see the class comment).
		double horizontalSpeed = Math.hypot(vx, vz);

		vy = vy + GRAVITY * (-1 + lift * LIFT_FACTOR);

		if (vy < 0 && look.horizontal > 0) {
			double dive = vy * DIVE_FACTOR * lift;
			vx = vx + look.x * dive / look.horizontal;
			vy = vy + dive;
			vz = vz + look.z * dive / look.horizontal;
		}

		if (pitchRad < 0 && look.horizontal > 0) {
			double climb = horizontalSpeed * (-Math.sin(pitchRad)) * CLIMB_FACTOR;
			vx = vx - look.x * climb / look.horizontal;
			vy = vy + climb * CLIMB_THRUST;
			vz = vz - look.z * climb / look.horizontal;
		}

		if (look.horizontal > 0) {
			vx = vx + (look.x / look.horizontal * horizontalSpeed - vx) * STEADY_PULL;
			vz = vz + (look.z / look.horizontal * horizontalSpeed - vz) * STEADY_PULL;
		}

		vx *= HORIZONTAL_DRAG;
		vy *= VERTICAL_DRAG;
		vz *= HORIZONTAL_DRAG;

		// The displacement of this tick uses the glide result.
		double nextX = state.x + vx;
		double nextY = state.y + vy;
		double nextZ = state.z + vz;

		// The rocket's pull lands on the velocity AFTER the displacement, so it
		// steers the NEXT tick's motion.
		int rocketTicks = state.rocketTicksRemaining;
		if (input.useRocket)
			rocketTicks = input.rocketDurationTicks;
		if (rocketTicks > 0) {
			vx = vx + look.x * ROCKET_BASE_ACCEL + (look.x * ROCKET_TARGET_SPEED - vx) * ROCKET_PULL;
			vy = vy + look.y * ROCKET_BASE_ACCEL + (look.y * ROCKET_TARGET_SPEED - vy) * ROCKET_PULL;
			vz = vz + look.z * ROCKET_BASE_ACCEL + (look.z * ROCKET_TARGET_SPEED - vz) * ROCKET_PULL;
			rocketTicks -= 1;
		}

		return new State(nextX, nextY, nextZ, vx, vy, vz, rocketTicks);
	}
}
