package dev.mcpfabric.client.flight;

/**
 * The pure attitude policy of the bounded recovery that follows an in-air
 * internal failure ({@code no_viable_trajectory}, ab-17 audit item 3).
 *
 * <p>The controller keeps the heading, releases at ground/water/glide-stop or
 * the window end, and otherwise writes the pitch and the optional arresting
 * rocket returned here. This class holds only the decision so it can be tested
 * without a game client.
 *
 * <p>Policy, in order:
 *
 * <ol>
 * <li>Terrain ahead rises into the path (clearance below the lookahead profile
 * is less than {@link #TERRAIN_CLEARANCE}): pitch up. A strong sink close to
 * that rise with a usable rocket is arrested with one ignition — the ab-20
 * full arm kept its heading, flew into the rising river bank and took 9.97
 * damage because nothing traded speed for height.</li>
 * <li>Low over its own surface: flare ({@link #FLARE_PITCH}) so the touch-down
 * is not nose-first (ab-18/19/20 landings).</li>
 * <li>Clear and high: shallow descent ({@link #DESCENT_PITCH}).</li>
 * </ol>
 *
 * <p>Never fires while a boost is already active: the single-countdown boost
 * model cannot express two rockets (ab-17 audit item 4), so the no-stacking
 * control constraint applies to the recovery too.
 */
public final class FlightRecovery {
	private FlightRecovery() {}

	/** How far ahead the caller scans the terrain profile, blocks. */
	public static final double LOOKAHEAD = 24.0;
	/** Clearance under the lookahead profile that counts as "rising". */
	public static final double TERRAIN_CLEARANCE = 8.0;
	/** Height above the surface below which the descent turns into a flare. */
	public static final double FLARE_HEIGHT = 10.0;
	/** Nose-up pitch over rising terrain (MC pitch: negative is nose up). */
	public static final float CLIMB_PITCH = -20f;
	/** Touch-down flare pitch. */
	public static final float FLARE_PITCH = -18f;
	/** Shallow descent pitch. */
	public static final float DESCENT_PITCH = 8f;
	/** Sink rate that counts as needing an arrest, blocks/tick. */
	public static final double ARREST_SINK = -0.3;
	/** Arrest only when this close to the surface below, blocks. */
	public static final double ARREST_HEIGHT = 16.0;
	/** Rocket ignition is only worth it when the rise is this close, blocks. */
	public static final double ARREST_CLEARANCE = 12.0;

	/** One measured tick of recovery state; all heights in blocks. */
	public static final class Inputs {
		/** Height above the surface directly below the player. */
		public double heightAbove;
		/**
		 * Height above the highest surface within {@link #LOOKAHEAD} along the
		 * current heading. Negative when a wall rises above the player.
		 */
		public double clearanceAhead;
		/** Vertical speed, blocks/tick (negative = sinking). */
		public double vy;
		/** A firework rocket is in a hand. */
		public boolean rocketAvailable;
		/** A rocket's boost is already attached (no stacking). */
		public boolean boostActive;
	}

	/** The chosen attitude and the optional arrest ignition. */
	public static final class Action {
		public final float pitch;
		public final boolean fire;

		Action(float pitch, boolean fire) {
			this.pitch = pitch;
			this.fire = fire;
		}
	}

	/** The pitch and fire decision for one recovery tick. */
	public static Action decide(Inputs in) {
		boolean rising = in.clearanceAhead < TERRAIN_CLEARANCE
				&& in.clearanceAhead < in.heightAbove - 1.0;
		if (rising) {
			boolean arrest = in.rocketAvailable && !in.boostActive
					&& in.vy < ARREST_SINK
					&& in.heightAbove < ARREST_HEIGHT
					&& in.clearanceAhead < ARREST_CLEARANCE;
			return new Action(CLIMB_PITCH, arrest);
		}
		if (in.heightAbove <= FLARE_HEIGHT)
			return new Action(FLARE_PITCH, false);
		return new Action(DESCENT_PITCH, false);
	}
}
