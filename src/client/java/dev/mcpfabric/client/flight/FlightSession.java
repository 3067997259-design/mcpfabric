package dev.mcpfabric.client.flight;

import java.util.List;

/**
 * The per-tick flight driver (R2b, execution plan §7). A PURE decision module:
 * {@link #tick} takes the measured state and returns the chosen input — it
 * never touches the player, the options, or the game mode. The controller
 * applies the decision through the same accessors the launch macro uses.
 *
 * <p>Each tick re-selects from a small candidate set — steer left/straight/
 * right around the bearing, hold/dive/climb, fire or not — predicts every
 * candidate with {@link FlightDynamics} over a short horizon, rejects each
 * whose swept pose box hits a block or an unread cell, and scores the rest by
 * distance to the active entry. Only the first tick of the winner is meant to
 * be applied; the caller must re-select from the measured state on the next
 * tick.
 *
 * <p>Path following walks the ordered entry list. There is no straight-line
 * shortcut past an entry: the sweep covers every candidate trajectory, so a
 * shortcut through unverified space has no safe candidate and the walk simply
 * continues to the reachable entry.
 */
public final class FlightSession {

	/** Ordered waypoint from the host's verified route (R1 output). */
	public record Waypoint(double x, double y, double z) {}

	/** Tunable session parameters; first-round engineering values. */
	public static final class Params {
		/** Prediction horizon in ticks (design: 20–40; R2b starts short). */
		public int horizonTicks = 20;
		/** Yaw candidates around the bearing, degrees. */
		public float[] yawOffsets = { 0, -15, 15, -30, 30 };
		/**
		 * Pitch candidates around the aim line, degrees (negative = nose up).
		 * The aim line is the bearing to the active entry, not the current
		 * attitude: a nose-up handover must not lock the span.
		 */
		public float[] pitchOffsets = { 0, -12, 12, -28 };
		/** Normal policy cap; terminal bridging may check up to five extra glide prefixes. */
		public int maxCandidates = 40;
		/** Simulation wall-clock cap, ms. */
		public long simBudgetMs = 12;
		/** Pose box (vanilla standing) plus safety inflation, blocks. */
		public double poseHalfWidth = 0.3;
		public double poseHeight = 1.8;
		public double inflate = 0.15;
		/** 3D distance to the active entry that advances the index. */
		public double entryReach = 6.0;
		/** Final arrival radius; zero uses entryReach. Intermediate guidance keeps entryReach. */
		public double terminalReach = 0.0;
	/**
	 * True when the submitted leg's end is a mid-route handover point: the
	 * bounded wait after the path completes keeps the altitude instead of
	 * descending (ab-30 plan §2). Absent or `stop` keeps the old behaviour.
	 */
	public boolean throughWaypoint = false;
		/** Explicit final stop: finish the route and enter controlled recovery without a handover hold. */
		public boolean stopAtEnd = false;
		/** Capture a local snapshot and plan terminal controls on a worker. */
		public boolean terminalPlanning = false;
		/** Fire only under this horizontal speed, blocks/tick (save rockets at cruise). */
		public double thrustSpeedFloor = 0.45;
		/** Tick gap between rocket fires. */
		public int fireCooldownTicks = 10;
		/** Pull-up delta applied to the aim angle for {@code AIM_PULL} (B2). */
		public float aimPullDegrees = -12f;
		/** Absolute pitch of the {@code PULL_UP} policy (B2). */
		public float pullUpDegrees = -12f;
		/** Most pitch the command may change per tick, degrees (ab-29 review). */
		public float pitchRateLimitDegrees = 8f;
		/**
		 * Most yaw the command may change per tick, degrees. Prediction and
		 * execution share it (B2): without it a cursor advance at a sharp turn
		 * snapped the view and let the aircraft fly itself into the outer wall.
		 * Zero disables the limit.
		 */
		public float yawRateLimitDegrees = 25f;
		/** Most pitch may change per tick; the swept candidate uses this same input. */
	}

	/**
	 * The pitch policies the bounded candidate set evaluates (ab-23 repair
	 * plan, B2). They are absolute alternatives as well as aim-relative ones:
	 * a candidate set built only around a steep aim can contain nothing but
	 * dives, which is how the ab-23 level-water state had no viable candidate.
	 */
	public enum PitchPolicy {
		/** The current reference aim angle. */
		AIM,
		/** The aim angle with the configured pull-up delta. */
		AIM_PULL,
		/** Absolute level flight. */
		LEVEL,
		/** Absolute pull-up. */
		PULL_UP
	}

	/** Why the session stopped driving. */
	public enum EndReason {
		CHANNEL_COMPLETE, DEADLINE, NO_VIABLE_TRAJECTORY, HANDOVER_TIMEOUT
	}

	/** The chosen input for one tick; the controller applies it. */
	public static final class Decision {
		/**
		 * False when the decision carries no control input: a terminal state
		 * (done, deadline, channel complete, no viable trajectory) or a
		 * budget-limited tick. The controller must not write yaw/pitch/fire
		 * from a non-applicable decision, or the default zeros would steer the
		 * glider (R2b fix).
		 */
		public boolean applicable = true;
		public float yaw;
		public float pitch;
		public boolean fireRocket;
		public int entryIndex;
		public double entryDistance;
		public double score;
		public String note = "";

		// --- Per-tick telemetry (ab-17 audit 2026-09-21, section 6) ---------
		/** The waypoint this tick steered to (the hold target while holding). */
		public double targetX;
		public double targetY;
		public double targetZ;
		/** Global route cursor at decision time (entryIndex). */
		public int cursor;
		/** What the winning candidate's prediction did at its end. */
		public int predictedEndTicks;
		public int predictedEndCursor;
		public double predictedEndX;
		public double predictedEndY;
		public double predictedEndZ;
		/** {@code arrived_lookahead}, {@code horizon} or {@code none}. */
		public String predictedEndReason = "none";
		/**
		 * Predicted positions after 1, 3 and 5 ticks of the chosen input,
		 * flattened xyz. The audit asked for the first preview points so a
		 * live run can compare the intent with the measured trajectory.
		 */
		public double[] preview = new double[9];
		/** {@code hold}, {@code land} or {@code none}; see verifyHold. */
		public String terminalAction = "none";
		/** First rejection of this tick, with its predicted context. */
		public String rejectKind = "";
		public int rejectTick = -1;
		public double rejectX;
		public double rejectY;
		public double rejectZ;
		public String rejectBlock = "";
		/** Collision shape tag of the failing cell (full/partial/empty). */
		public String rejectShape = "";
		public int rejectCollisions;
		public int rejectFloor;
		public int rejectSpeed;
		public int rejectTerminal;
		// --- Evaluation accounting (ab-23 repair plan, A4/A5) ----------------
		/** Candidates attempted this tick. */
		public int evaluated;
		/** Candidates that survived every check. */
		public int feasible;
		/** Rejected candidates: evaluated - feasible. */
		public int rejected;
		/** Candidates the bounded search never attempted. */
		public int unevaluated;
		/** True when the candidate cap or the sim budget stopped the search. */
		public boolean budgetExhausted;
		/** Wall time by control stage, in microseconds; zero means not entered. */
		public long preparationUs, orderingUs, candidatesUs, selectionUs, reservationUs;
		// Cumulative totals since the session started; the per-tick counters
		// above reset every tick (an unreset terminal counter read 139 in ab-23
		// as if it were one tick's rejections).
		public long cumulativeRejects;
		public long cumulativeCollisions;
		public long cumulativeFloor;
		public long cumulativeSpeed;
		public long cumulativeTerminal;
		// --- Route identity, progress and instrumentation (B3/B5) -----------
		/** The host's route id for this channel. */
		public String routeId = "";
		/** The host's route revision for this channel. */
		public long routeRevision;
		/** Global arc-length progress at the measured state (monotonic). */
		public double progress;
		/** Global arc-length progress at the winning prediction's end. */
		public double predictedEndProgress;
		/** Physics steps simulated this tick. */
		public long physicsSteps;
		/** Block queries issued this tick. */
		public long blockQueries;
		/** Column-cache hits this tick. */
		public long cacheHits;
		/** Wall-clock milliseconds the candidate search took. */
		public long simMs;
		/** The chosen candidate's stable policy id (D1). */
		public String chosenPolicy = "";
		public String planningState = "none";
		/** True when the bounded search ran to its natural end (D1/ab-24 audit 4). */
		public boolean searchCompleted;
		/** Why the search stopped early: 	ime / candidate_cap / empty (D1). */
		public String budgetReason = "";
	}

	@FunctionalInterface
	public interface BlockQuery {
		/**
		 * Block-id suffix at the position; {@code null} = unknown/unloaded.
		 * Unknown is never air (CD-0).
		 */
		String idAt(double x, double y, double z);

		/** Loaded support friction; unknown physics cannot certify a landing. */
		default double frictionAt(double x, double y, double z) {
			return Double.NaN;
		}

		/**
		 * Short tag of the cell's collision shape at the position
		 * ({@code full}, {@code partial}, {@code empty} or {@code unknown}).
		 * Only used for rejection telemetry: the ab-17 audit asked for the
		 * first rejected block's shape, not only its id.
		 */
		default String shapeAt(double x, double y, double z) {
			return "";
		}
	}

	private final Params params;
	private final List<Waypoint> path;
	private final BlockQuery blocks;
	private final long deadlineMs;
	private FlightSequence terminalSequence;
	private FlightPlanning planning;

	private int entryIndex;
	private boolean done;
	private EndReason endReason;
	private long lastFireDecisionTick = Integer.MIN_VALUE;
	/**
	 * True when every waypoint was reached. The session does NOT stop here: a
	 * handover-capable flight keeps a bounded hold so the host can hand the
	 * next leg over without an unowned glide (R4 review items 3-4).
	 */
	private boolean pathComplete;
	private boolean holding;
	private long holdUntilMs;
	private double holdX;
	private double holdY;
	private double holdZ;
	/** Post-path hold grace; 0 disables the hold (completion ends the session). */
	private final long holdGraceMs;
	/** How far down the hold scans for a surface it must not dive into. */
	private static final double HOLD_SCAN_DEPTH = 40.0;
	/** Height the hold keeps above the surface it found, blocks. */
	private static final double HOLD_CLEARANCE = 6.0;
	/** Most height a hold gives up over its whole window, blocks. */
	private static final double HOLD_MAX_DESCENT = 4.0;
	/** Below this clearance the hold keeps the current altitude instead. */
	private static final double HOLD_MIN_CLEARANCE = 8.0;
	/** How far past an entry's plane counts as passed, blocks. */
	private static final double PASSED_PLANE_MARGIN = 1.0;
	/** Only entries within this range may be skipped as passed, blocks. */
	private static final double PASSED_PLANE_RANGE = 24.0;
	/** How far below the remaining route a prediction may sink, blocks. */
	private static final double CHANNEL_LOWER_SLACK = 6.0;
	/** A prediction must keep this much clearance above its own end surface. */
	private static final double CONTINUE_CLEARANCE = 4.0;
	/** A prediction must end with at least this much steering energy. */
	private static final double CONTINUE_SPEED = 0.25;
	/** Tolerance under the sink limit; an already-low glider may still climb. */
	private static final double CONTINUE_FLOOR_TOLERANCE = 0.5;
	/**
	 * Ticks the sweep continues past the terminal entry. The prediction must
	 * not shrink to one tick just because the terminal was reached: an
	 * obstacle right after it is still part of this candidate's trajectory
	 * (ab-17 audit item 2: the leaves hit at t1 were never swept).
	 */
	private static final int TERMINAL_LOOKAHEAD_TICKS = 6;
	/**
	 * How long the terminal-action verification simulates the hold. The hold
	 * window itself is longer (the handover grace); this only proves the
	 * arrival state can fly the maneuver, and the driver re-decides every tick.
	 */
	private static final int TERMINAL_ACTION_TICKS = 20;
	/**
	 * Near-term window of the hold simulation that must be obstacle-free. A
	 * failure inside it is a terminal refusal; a terrain contact after it is a
	 * landing, not a collision (ab-21: rejecting far-end ground contact
	 * refused almost every arrival near rising terrain and drove the flight
	 * into low water refusals — `ta` reached 131 per tick).
	 */
	private static final int TERMINAL_ACTION_MIN_TICKS = 8;
	/** Touch-down sink rate that still counts as a landing, blocks/tick. */
	private static final double TERMINAL_LANDING_SINK = 0.6;
	/** Horizontal impact speed a landing may have, blocks/tick (B4/C4). */
	private static final double TERMINAL_LANDING_HORIZONTAL = 0.5;
	/** Surface clearance an ARRIVED prediction must keep (entries may be low). */
	private static final double ARRIVAL_SURFACE_CLEARANCE = 0.5;
	/** Per-tick surface-scan cache shared by the candidate evaluations. */
	private final java.util.Map<ColumnKey, Column> columnCache = new java.util.HashMap<>();
	/** Per-tick block-cell cache: the sweep probes repeat cells across candidates. */
	private final java.util.Map<CellKey, String> cellCache = new java.util.HashMap<>();
	/** Support shape/friction shares the geometry epoch, never a later native tick. */
	private final java.util.Map<CellKey, Double> frictionCache = new java.util.HashMap<>();
	/**
	 * Rejection attribution for the last tick (R4 client review 2): the old
	 * `no_viable` note could not tell a collision from a height-floor or a
	 * speed-floor rejection. Counters and the first rejection's predicted
	 * tick/point/block make the live failure attributable.
	 */
	private int rejectCollisions;
	private int rejectFloor;
	private int rejectSpeed;
	/** Candidates whose arrival state could not fly the terminal hold. */
	private int rejectTerminal;
	/** Candidates that survived every check this tick. */
	private int feasibleCount;
	/** Terminal verifications actually run this tick (bounded workload). */
	private int terminalChecked;
	/** True when the terminal-verify budget was spent without a winner. */
	private boolean terminalVerifyExhausted;
	/** Cumulative rejection totals; the per-tick counters reset each tick. */
	private long cumulativeRejects;
	private long cumulativeCollisions;
	private long cumulativeFloor;
	private long cumulativeSpeed;
	private long cumulativeTerminal;
	private String firstRejectKind = "";
	private int firstRejectTick = -1;
	private double firstRejectX;
	private double firstRejectY;
	private double firstRejectZ;
	private String firstRejectBlock = "";
	private String firstRejectShape = "";
	/** Pose-box probe that failed inside the sweep: cell and sample point. */
	private double probeX;
	private double probeY;
	private double probeZ;
	private String probeId = "";
	/** Shape tag of the cell the pose-box probe failed on (telemetry). */
	private String probeShape = "";
	private double sweepFailX;
	private double sweepFailY;
	private double sweepFailZ;
	/** Last decision, kept for the controller's per-tick telemetry. */
	private Decision lastDecision;
	/** Cumulative arc length at every waypoint; monotonic route progress (B3). */
	private final double[] cumulative;
	/** Route identity the host sent with this channel (B3). */
	private String routeId = "";
	private long routeRevision;
	/** The previous tick's winning policy: the first baseline re-checked (B2). */
	private float lastWinnerYawOffset;
	private PitchPolicy lastWinnerPolicy;
	private boolean lastWinnerFire;
	// --- Per-tick instrumentation (ab-23 repair plan, B5) --------------------
	/** Physics steps simulated this tick (candidates x ticks). */
	private long physicsSteps;
	/** Block queries issued this tick (hot paths only). */
	private long blockQueries;
	/** Column-cache hits this tick. */
	private long cacheHits;

	public FlightSession(List<Waypoint> path, BlockQuery blocks, long deadlineMs, Params params) {
		this(path, blocks, deadlineMs, params, 0L);
	}

	/**
	 * @param holdGraceMs how long the session holds after its path completes,
	 *        waiting for the host's next leg; 0 ends at completion
	 */
	public FlightSession(List<Waypoint> path, BlockQuery blocks, long deadlineMs, Params params, long holdGraceMs) {
		if (path == null || path.size() < 2)
			throw new IllegalArgumentException("channel path needs at least two waypoints");
		this.path = path;
		this.blocks = blocks;
		this.deadlineMs = deadlineMs;
		this.params = params == null ? new Params() : params;
		this.holdGraceMs = Math.max(0L, holdGraceMs);
		this.cumulative = new double[path.size()];
		if (this.params.stopAtEnd && this.params.terminalPlanning)
			this.planning = new FlightPlanning(this, blocks, path);
		for (int index = 1; index < path.size(); index++) {
			Waypoint previous = path.get(index - 1);
			Waypoint current = path.get(index);
			cumulative[index] = cumulative[index - 1]
					+ dist3(current.x() - previous.x(), current.y() - previous.y(), current.z() - previous.z());
		}
		warmUpOnce();
	}

	/** One-time JIT warm-up flag: the cold first tick must not lose its budget. */
	private static boolean warmedUp;

	private int rocketDurationTicks = FlightRocket.nominalTicks(1);
	private int rocketMaximumTicks = FlightRocket.maximumTicks(1);

	/** The controller supplies the item selected by the actual ignition hand. */
	public void setRocketFlightDuration(int duration) {
		rocketDurationTicks = FlightRocket.nominalTicks(duration);
		rocketMaximumTicks = FlightRocket.maximumTicks(duration);
	}

	/** Prepares the complete decision path before launch, without live world IO or input. */
	private static synchronized void warmUpOnce() {
		if (warmedUp)
			return;
		warmedUp = true;
		try {
			var warmParams = new Params();
			warmParams.stopAtEnd = true; warmParams.terminalPlanning = true;
			// Preparation is outside flight control. The real session keeps its
			// 12 ms cap and independent state, caches, counters and world query.
			warmParams.simBudgetMs = 100;
			var pilot = new FlightSession(List.of(new Waypoint(0,70,0),new Waypoint(0,70,150)),
					(x,y,z) -> y < 60 ? "stone" : "air",System.currentTimeMillis()+2000,warmParams,0);
			try {
				var state = new FlightDynamics.State(0,70,0,0,0,1,26);
				float yaw = 0, pitch = 0;
				for (int index = 0; index < 8; index++) {
					var decision = pilot.tick(state.x,state.y,state.z,state.vx,state.vy,state.vz,
							yaw,pitch,64,state.rocketTicksRemaining,index,false);
					if (!decision.applicable) break;
					yaw = decision.yaw; pitch = decision.pitch;
					state = FlightDynamics.step(state,new FlightDynamics.Input(yaw,pitch,false));
				}
			} finally { pilot.planning.close(); }
		}
		catch (RuntimeException ignored) {
			// A warm-up failure must never prevent a session from existing.
		}
	}

	/**
	 * Verifies one fixed input over its whole predicted trajectory with the
	 * same physics and body sweep the route candidates use (ab-24 audit
	 * section 2): a downward or ceiling pre-scan is not a substitute for the
	 * full 3D check. Used by the recovery, whose final yaw/pitch/fire must be
	 * validated after the heading is decided.
	 */
	public boolean verifyFrame(double x, double y, double z, double vx, double vy, double vz,
			int rocketTicks, float yaw, float pitch, boolean fire, int ticks) {
		FlightDynamics.State state = new FlightDynamics.State(x, y, z, vx, vy, vz, Math.max(0, rocketTicks));
		for (int tick = 0; tick < ticks; tick++) {
			physicsSteps += 1;
			FlightDynamics.State next = FlightDynamics.step(state,
					new FlightDynamics.Input(yaw, pitch, fire && tick == 0, rocketDurationTicks));
			if (!sweepSegmentClear(state, next))
				return false;
			state = next;
		}
		return true;
	}

	/**
	 * One bounded, symmetric, rate-limited safe action (ab-29 review).
	 *
	 * <p>Used when a tick is UNDECIDED (the candidate search or the terminal
	 * verification ran out of budget without a verified winner) and by the
	 * recovery. It tries the zero offset first, then symmetric left/right
	 * turns, across level / pull-up / climb / aim policies, applies the same
	 * steering and pitch rate limits the execution uses, sweeps the whole
	 * prediction with the same physics, and requires the PREDICTED END STATE to
	 * still have room (clearance ahead or a support landing) — a short
	 * collision-free prefix is not enough.
	 *
	 * <p>When nothing verifies it returns the best-effort emergency action
	 * (largest time before the predicted contact, then the mildest impact) with
	 * {@code verified = 0}: the caller must report that safety cannot be
	 * guaranteed instead of treating the action as verified.
	 */
	public static final class SafeFrame {
		public final float yaw;
		public final float pitch;
		public final boolean fire;
		/** How many candidates passed the full check. */
		public final int verified;
		/** How many candidates were evaluated. */
		public final int candidates;
		/** Predicted ticks before the first contact of the chosen action (best effort). */
		public final int contactTicks;
		/** True when the chosen action came from the emergency ranking only. */
		public final boolean emergency;
		/** The turn magnitude from the current heading, degrees (tie-break). */
		public final double offsetDegrees;
		/** Verified slow contact with a solid support patch, never water or foliage. */
		public boolean landingContact;

		SafeFrame(float yaw, float pitch, boolean fire, int verified, int candidates, int contactTicks, boolean emergency, double offsetDegrees) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.fire = fire;
			this.verified = verified;
			this.candidates = candidates;
			this.contactTicks = contactTicks;
			this.emergency = emergency;
			this.offsetDegrees = offsetDegrees;
		}
	}

	/** The bounded safe-search offsets and policies (symmetric, zero included). */
	private static final float[] SAFE_YAW_OFFSETS = { 0f, -12f, 12f, -25f, 25f };

	/** The recovery intent and the measured pose have separate meanings. */
	public SafeFrame recoveryFrame(double x, double y, double z, double vx, double vy, double vz,
			int rocketTicks, int rockets, float currentYaw, float currentPitch,
			float desiredYaw, float desiredPitch, int ticks) {
		return safeFrame(x, y, z, vx, vy, vz, rocketTicks, rockets, currentYaw, currentPitch,
				desiredYaw, desiredPitch, ticks, true);
	}

	/**
	 * Runs the bounded safe search; see {@link SafeFrame}.
	 *
	 * @param ticks how far the action is predicted and checked (the recovery
	 *        needs enough to turn or flare, not just 0.3 s)
	 */
	public SafeFrame safeFrame(double x, double y, double z, double vx, double vy, double vz,
			int rocketTicks, int rockets, float currentYaw, float currentPitch, int ticks) {
		return safeFrame(x, y, z, vx, vy, vz, rocketTicks, rockets, currentYaw, currentPitch,
				currentYaw, currentPitch, ticks, false);
	}

	private SafeFrame safeFrame(double x, double y, double z, double vx, double vy, double vz,
			int rocketTicks, int rockets, float currentYaw, float currentPitch,
			float desiredYaw, float desiredPitch, int ticks, boolean allowLanding) {
		// Recovery reuses its probe across ticks, without calling tick(). Old
		// cached cells must not hide newly loaded chunks or terrain changes.
		cellCache.clear();
		columnCache.clear();
		frictionCache.clear();
		int candidates = 0;
		int verified = 0;
		SafeFrame best = null;
		// Emergency ranking over the rejected actions: the latest contact wins,
		// then the mildest sink at that contact.
		int bestContact = -1;
		double bestImpact = Double.MAX_VALUE;
		SafeFrame emergency = null;
		java.util.List<Float> headings = new java.util.ArrayList<>();
		for (float offset : SAFE_YAW_OFFSETS)
			headings.add(normalizeBearing(currentYaw + offset));
		float preferred = limitYaw(currentYaw, desiredYaw, params.yawRateLimitDegrees);
		if (allowLanding && headings.stream().noneMatch(value -> Math.abs(normalizeBearing(value - preferred)) < 0.01f))
			headings.add(preferred);
		for (float rawYaw : headings) {
			for (PitchPolicy policy : new PitchPolicy[] {
					PitchPolicy.LEVEL, PitchPolicy.PULL_UP, PitchPolicy.AIM, PitchPolicy.AIM_PULL }) {
				for (int fire = 0; fire < 2; fire++) {
					boolean useFire = fire == 1;
					if (useFire && (rockets <= 0 || rocketTicks > 0))
						continue;
					candidates += 1;
					float yaw = limitYaw(currentYaw, rawYaw, params.yawRateLimitDegrees);
					float pitch = limitPitch(currentPitch, pitchFor(policy, desiredPitch), params.pitchRateLimitDegrees);
					// The candidate IS the action: a single rate-limited yaw/pitch
					// held for the whole prediction (execution applies its first
					// step). No per-step re-aiming here.
					FlightDynamics.State state = new FlightDynamics.State(x, y, z, vx, vy, vz, Math.max(0, rocketTicks));
					int contactTick = -1;
					boolean landingContact = false;
					double impact = 0;
					int verifiedTicks = Math.max(ticks, useFire ? rocketMaximumTicks : Math.max(0, rocketTicks));
					for (int tick = 0; tick < verifiedTicks; tick++) {
						physicsSteps += 1;
						FlightDynamics.State next = FlightDynamics.step(state,
								new FlightDynamics.Input(yaw, pitch, useFire && tick == 0, rocketDurationTicks));
						if (!sweepSegmentClear(state, next)) {
							if (allowLanding && safeTouchdown(state, next, yaw, pitch, verifiedTicks - tick)) {
								landingContact = true;
								state = next;
								break;
								}
							contactTick = tick;
							impact = Math.abs(state.vy);
							break;
						}
						state = next;
					}
					if (contactTick >= 0) {
						// An unverified fallback may steer, but must not commit an
						// irreversible boost merely because it delays the collision.
						if (!useFire && (contactTick > bestContact || (contactTick == bestContact && impact < bestImpact))) {
							bestContact = contactTick;
							bestImpact = impact;
							emergency = new SafeFrame(yaw, pitch, useFire, 0, candidates, contactTick, true,
									Math.abs(normalizeBearing(yaw) - normalizeBearing(currentYaw)));
						}
						continue;
					}
					// The predicted END STATE must still have room: clearance
					// ahead, or a verified support landing below.
					double surface = surfaceBelow(state.x, state.z, state.y);
					double heightAbove = state.y - surface;
					double ahead = clearanceAheadFrom(state, yaw, surface);
					if (!landingContact && heightAbove < ARRIVAL_SURFACE_CLEARANCE && !isSupportLanding(state.x, state.y, state.z))
						continue;
					if (!landingContact && ahead < FLARE_MARGIN && heightAbove > FLARE_MARGIN)
						continue;
					verified += 1;
					SafeFrame frame = new SafeFrame(yaw, pitch, useFire, verified, candidates, verifiedTicks, false,
							Math.abs(normalizeBearing(yaw - currentYaw)));
					frame.landingContact = landingContact;
					if (best == null || betterSafeFrame(frame, best, desiredYaw, desiredPitch, allowLanding))
						best = frame;
				}
			}
		}
		if (best != null) {
			SafeFrame result = new SafeFrame(best.yaw, best.pitch, best.fire, verified, candidates, best.contactTicks, false, best.offsetDegrees);
			result.landingContact = best.landingContact;
			return result;
		}
		if (emergency != null)
			return new SafeFrame(emergency.yaw, emergency.pitch, emergency.fire, 0, candidates,
					emergency.contactTicks, true, emergency.offsetDegrees);
		return new SafeFrame(currentYaw, currentPitch, false, 0, candidates, -1, true, 0);
	}

	/** How much clearance the safe search wants in front of the end state. */
	private static final double FLARE_MARGIN = 6.0;

	private static boolean betterSafeFrame(SafeFrame candidate, SafeFrame best,
			float desiredYaw, float desiredPitch, boolean landing) {
		if (landing) {
			if (candidate.landingContact != best.landingContact)
				return candidate.landingContact;
			double candidateError = Math.abs(normalizeBearing(candidate.yaw - desiredYaw)) + Math.abs(candidate.pitch - desiredPitch);
			double bestError = Math.abs(normalizeBearing(best.yaw - desiredYaw)) + Math.abs(best.pitch - desiredPitch);
			if (Math.abs(candidateError - bestError) > 1.0E-6)
				return candidateError < bestError;
		}
		if (candidate.fire != best.fire)
			return !candidate.fire;
		// The turn magnitude from the CURRENT heading, not the absolute yaw.
		return candidate.offsetDegrees < best.offsetDegrees - 1.0E-6;
	}

	/** Allows only a slow floor contact; the standing body and runout stay clear. */
	private boolean safeTouchdown(FlightDynamics.State from, FlightDynamics.State to,
			float yaw, float pitch, int remainingTicks) {
		return safeTouchdown(from, to, yaw, pitch, remainingTicks, null);
	}

	private boolean safeTouchdown(FlightDynamics.State from, FlightDynamics.State to,
			float yaw, float pitch, int remainingTicks, Waypoint requiredGoal) {
		if (to.rocketTicksRemaining > 0 || to.vy >= 0 || to.vy < -0.45 || Math.hypot(to.vx, to.vz) > 1.6)
			return false;
		double surface = surfaceBelow(to.x, to.z, from.y + 0.5);
		// The air model returns an unclipped endpoint. Native floor collision
		// stops the feet at the surface, so endpoint penetration is not impact
		// speed. A separate 0.3 depth cap rejected the last tick of a descent
		// already certified by this same loop. The 0.45 sink limit, raised body
		// sweep, full support and ground runout remain required at each contact.
		if (from.y < surface || from.y > surface + 0.6)
			return false;
		// Floor inflation may touch before the feet do. Check both poses just
		// above that plane to distinguish support contact from a wall/ceiling.
		double raisedY = surface + params.inflate + 1.0E-5;
		for (int step = 0; step < remainingTicks; step++) {
			if (to.vy >= 0 || to.vy < -0.45 || !landingSweepClear(from.x, Math.max(from.y, raisedY), from.z,
					to.x, Math.max(to.y, raisedY), to.z) || !Double.isFinite(landingFriction(to.x, to.z, surface)))
				return false;
			if (to.y <= surface) {
				// Vanilla moves before applying support friction. Reserve another
				// undamped tick for the server to clear the fall-flying flag.
				double px = to.x, pz = to.z, vx = to.vx, vz = to.vz;
				for (int groundTick = 0; groundTick < 24; groundTick++) {
					double friction = landingFriction(px, pz, surface);
					if (!Double.isFinite(friction))
						return false;
					// Substeps also check support: an airborne gap is not a runway.
					int steps = Math.max(1, (int)Math.ceil(Math.hypot(vx, vz) / 0.2));
					for (int part = 1; part <= steps; part++) {
						double nx = px + vx * part / steps, nz = pz + vz * part / steps;
						if (!Double.isFinite(landingFriction(nx, nz, surface))
								|| !landingSweepClear(px, raisedY, pz, nx, raisedY, nz))
							return false;
					}
					px += vx;
					pz += vz;
					if (groundTick > 0) {
						vx *= friction * 0.91;
						vz *= friction * 0.91;
					}
				}
				// Low speed is not rest. Keep the whole bounded runout before
				// testing the goal sphere; discarded drift can cross its edge.
				return Math.hypot(vx, vz) <= 0.05 && (requiredGoal == null
						|| dist3(px - requiredGoal.x(), surface - requiredGoal.y(), pz - requiredGoal.z()) <= terminalReach());
			}
			from = to;
			physicsSteps += 1;
			to = FlightDynamics.step(from, new FlightDynamics.Input(yaw, pitch, false));
		}
		return false;
	}

	private boolean landingSweepClear(double x, double y, double z, double nx, double ny, double nz) {
		return sweepSegmentClear(new FlightDynamics.State(x, y, z, 0, 0, 0, 0),
				new FlightDynamics.State(nx, ny, nz, 0, 0, 0, 0));
	}

	/** Require support under the entire inflated body; use the slowest braking material. */
	private double landingFriction(double x, double z, double surface) {
		double half = params.poseHalfWidth + params.inflate;
		double friction = 0;
		for (int bx = (int)Math.floor(x - half); bx < Math.ceil(x + half); bx++) {
			for (int bz = (int)Math.floor(z - half); bz < Math.ceil(z + half); bz++) {
				if (FlightGeometry.isUnsafeSupport(cellAt(bx, surface - 1, bz)))
					return Double.NaN;
				double value = supportFrictionAt(bx, (int)Math.floor(surface - 1), bz);
				if (!Double.isFinite(value) || value <= 0 || value >= 1)
					return Double.NaN;
				friction = Math.max(friction, value);
			}
		}
		return friction;
	}

	private double supportFrictionAt(int x, int y, int z) {
		var key = new CellKey(x, y, z);
		Double cached = frictionCache.get(key);
		if (cached != null) {
			cacheHits++;
			return cached;
		}
		blockQueries++;
		double value = blocks.frictionAt(x, y, z);
		// Cache unknown physics too, but only within this geometry epoch.
		frictionCache.put(key, value);
		return value;
	}

	/** The clearance ahead of a predicted end state along a heading. */
	private double clearanceAheadFrom(FlightDynamics.State state, float heading, double ownSurface) {
		double rad = Math.toRadians(heading);
		double dirX = -Math.sin(rad);
		double dirZ = Math.cos(rad);
		double worst = state.y - ownSurface;
		for (double distance = 4.0; distance <= 24.0; distance += 4.0) {
			double surface = surfaceBelow(state.x + dirX * distance, state.z + dirZ * distance, state.y);
			worst = Math.min(worst, state.y - surface);
		}
		return worst;
	}

	/** Limits a pitch command to the shared pitch rate. */
	public static float limitPitch(float previousPitch, float desiredPitch, float limitDegrees) {
		if (limitDegrees <= 0f)
			return clampPitch(desiredPitch);
		float delta = clampPitch(desiredPitch) - clampPitch(previousPitch);
		float clamped = Math.max(-limitDegrees, Math.min(limitDegrees, delta));
		return clampPitch(clampPitch(previousPitch) + clamped);
	}

	public boolean isDone() {
		return done;
	}

	/**
	 * The route identity of this channel, for the per-tick progress telemetry:
	 * a leg-local cursor is not comparable with another arm, but the route id,
	 * revision and global arc-length progress are (B3).
	 */
	public void setRouteIdentity(String routeId, long revision) {
		this.routeId = routeId == null ? "" : routeId;
		this.routeRevision = revision;
	}

	/**
	 * Queues a planner result on the control thread without replacing the route.
	 * Adoption occurs only at its exact session tick and matching measured state.
	 * This API does not run the planner or read its snapshot on a worker thread.
	 */
	public boolean offerTerminalSequence(FlightSequence.Plan plan) {
		if (done || !params.stopAtEnd) return false;
		if (terminalSequence == null) terminalSequence = new FlightSequence(this, params);
		return terminalSequence.offer(plan, routeId, routeRevision);
	}

	/** Adoption and validation state; rejection does not itself end the route. */
	public String terminalSequenceStatus() {
		return terminalSequence == null ? "none" : terminalSequence.status();
	}

	/** Cancels pending work when the controller replaces or terminates this session. */
	public void closePlanning() { if (planning != null) planning.close(); }

	private int planningPrefixBranches;
	private int planningPrefixRejectedExpiry = -1;

	String planningPrefixEvidence() {
		return " branches=" + planningPrefixBranches + " rejectedExpiry=" + planningPrefixRejectedExpiry;
	}

	boolean planningPrefixClear(FlightSequence.Origin origin, List<FlightDynamics.Input> controls, long deadline) {
		refreshSequenceGeometry();
		planningPrefixBranches = 0;
		planningPrefixRejectedExpiry = -1;
		// An attached rocket can expire at any remaining tick, including now.
		// Test each discrete expiry and the branch powered through this horizon.
		// Neither a recipe mean nor just the two endpoints bounds the geometry.
		int lastExpiry = origin.state().rocketTicksRemaining > 0 ? controls.size() : 0;
		for (int expiry = 0; expiry <= lastExpiry; expiry++) {
			planningPrefixBranches++;
			var s = origin.state();
			var branch = new FlightSequence.Origin(new FlightDynamics.State(s.x, s.y, s.z, s.vx, s.vy, s.vz, expiry),
					origin.yaw(), origin.pitch());
			if (!planningPrefixStateClear(branch, controls, deadline)) {
				planningPrefixRejectedExpiry = expiry;
				return false;
			}
		}
		return System.nanoTime() < deadline;
	}

	private boolean planningPrefixStateClear(FlightSequence.Origin origin, List<FlightDynamics.Input> controls, long deadline) {
		var frame = origin;
		double minY = origin.state().y;
		for (var desired : controls) {
			if (System.nanoTime() >= deadline || desired.useRocket) return false;
			var input = new FlightDynamics.Input(limitYaw(frame.yaw(), desired.yaw, params.yawRateLimitDegrees),
					limitPitch(frame.pitch(), desired.pitch, params.pitchRateLimitDegrees), false);
			var next = FlightDynamics.step(frame.state(), input);
			if (!sequenceSweepClear(frame.state(), next)) return false;
			minY = Math.min(minY, next.y);
			frame = new FlightSequence.Origin(next, input.yaw, input.pitch);
		}
		var end = frame.state();
		double clearance = permitsStopDescent(end, false) ? ARRIVAL_SURFACE_CLEARANCE : CONTINUE_CLEARANCE;
		return minY >= Math.min(origin.state().y, surfaceBelow(end.x, end.z, end.y) + clearance) - CONTINUE_FLOOR_TOLERANCE
				&& Math.hypot(end.vx, end.vz) >= CONTINUE_SPEED && System.nanoTime() < deadline;
	}

	void refreshSequenceGeometry() {
		cellCache.clear();
		columnCache.clear();
		frictionCache.clear();
	}

	boolean sequenceSweepClear(FlightDynamics.State from, FlightDynamics.State to) {
		physicsSteps++;
		return sweepSegmentClear(from, to);
	}

	FlightDynamics.Input sequenceLanding(FlightSequence.Origin origin, long deadline) {
		FlightDynamics.State s = origin.state();
		Waypoint goal = path.get(path.size() - 1);
		if (System.nanoTime() >= deadline || Math.sqrt(Math.pow(s.x - goal.x(), 2)
				+ Math.pow(s.y - goal.y(), 2) + Math.pow(s.z - goal.z(), 2)) > terminalReach()) return null;
		// The sequence needs a concrete touchdown, not an open-air recovery
		// horizon. Check distinct rate-limited inputs once, then keep the exact
		// accepted input as its tail. Each candidate includes ground runout.
		java.util.Set<String> checked = new java.util.HashSet<>();
		for (float offset : SAFE_YAW_OFFSETS) {
			for (float desiredPitch : new float[] { 8, 0, -12, -4 }) {
				if (System.nanoTime() >= deadline) return null;
				float yaw = limitYaw(origin.yaw(), origin.yaw() + offset, params.yawRateLimitDegrees);
				float pitch = limitPitch(origin.pitch(), desiredPitch, params.pitchRateLimitDegrees);
				if (!checked.add(yaw + ":" + pitch)) continue;
				FlightDynamics.Input input = new FlightDynamics.Input(yaw, pitch, false);
				if (sequenceLandingClear(s, input, deadline)) return input;
			}
		}
		return null;
	}

	boolean sequenceLandingClear(FlightDynamics.State state, FlightDynamics.Input input, long deadline) {
		for (int tick = 0; tick < 14; tick++) {
			if (System.nanoTime() >= deadline) return false;
			FlightDynamics.State next = FlightDynamics.step(state, input);
			if (!sequenceSweepClear(state, next)) {
				boolean clear = safeTouchdown(state, next, input.yaw, input.pitch, 14 - tick, path.get(path.size() - 1));
				return clear && System.nanoTime() < deadline;
			}
			state = next;
		}
		return false;
	}

	public EndReason endReason() {
		return endReason;
	}

	public int entryIndex() {
		return entryIndex;
	}

	/** True when every waypoint is reached, with or without a hold running. */
	public boolean isPathComplete() {
		return pathComplete;
	}

	/** True while the bounded post-path hold flies, waiting for a handover. */
	public boolean isHolding() {
		return holding;
	}

	/**
	 * Flies a bounded hold after the path completes: steer along the current
	 * heading and keep a safe altitude until a handover route arrives or the
	 * grace ends. Holding is not "keeping the old attitude": it is an explicit,
	 * time-bounded descent the host can replace at any tick.
	 *
	 * ROOT CAUSE (R4 cave-diag-16): the hold target used to be "48 ahead, 16
	 * below" unconditionally. Entering the hold at y=65.3 over a river at y=62
	 * put the target at y=49.3, and the glider followed it into the water. The
	 * target now descends at most a few blocks and never comes closer than a
	 * fixed clearance to the surface found below the hold point.
	 *
	 * @param graceMs how long the hold may run before the session ends with
	 *        {@link EndReason#HANDOVER_TIMEOUT}
	 */
	public void beginHold(double x, double y, double z, double vx, double vz, long graceMs) {
		beginHold(x, y, z, vx, vz, graceMs, false);
	}

	/**
	 * @param level true when the hold waits for a mid-route handover instead of
	 *        ending the trip (the host marked the leg `through`, ab-30 plan §2):
	 *        the target keeps the current altitude, so a handover point inside a
	 *        narrow opening cannot descend into its ceiling or floor.
	 */
	public void beginHold(double x, double y, double z, double vx, double vz, long graceMs, boolean level) {
		double speed = Math.hypot(vx, vz);
		double dirX = speed > 1.0E-4 ? vx / speed : 0.0;
		double dirZ = speed > 1.0E-4 ? vz / speed : 0.0;
		// Find the surface below the hold point; unknown cells count as solid
		// so the hold stays high rather than guessing a floor that is not there.
		double surface = y - HOLD_SCAN_DEPTH;
		for (double probe = y - 1; probe > y - HOLD_SCAN_DEPTH; probe -= 1.0) {
			String id = blocks.idAt(x, probe, z);
			if (id == null || !isAirLike(id)) {
				surface = probe + 1.0;
				break;
			}
		}
		// NOTICE: the target altitude must be written to the FIELD `holdY`.
		// A local `double holdY` here shadowed the field and left it at 0, so
		// every hold steered at y=0 and the glider descended into the river
		// (R4 cave-diag-16 hold analysis, 2026-09-21).
		double targetY = Math.max(surface + HOLD_CLEARANCE, y - HOLD_MAX_DESCENT);
		// Too little clearance to trade height for time: keep the altitude and
		// let the fire candidates hold it up instead of diving into the floor.
		if (y - surface < HOLD_MIN_CLEARANCE)
			targetY = y;
		// A through-leg handover must not descend at all: the wait is bounded by
		// the grace, and the next revision supplies the real continuation.
		if (level)
			targetY = y;
		holdX = x + dirX * 48.0;
		holdY = Math.max(targetY, surface + HOLD_CLEARANCE);
		holdZ = z + dirZ * 48.0;
		holdUntilMs = System.currentTimeMillis() + graceMs;
		holding = true;
	}

	/** The hold target, exposed so a probe can verify the field is written. */
	public Waypoint holdTarget() {
		return new Waypoint(holdX, holdY, holdZ);
	}

	/** The configured advance radius, echoed so callers can verify adoption. */
	public double entryReach() {
		return params.entryReach;
	}

	/** Final arrival radius after applying the channel's optional tighter bound. */
	public double terminalReach() {
		return params.terminalReach > 0 ? Math.min(params.terminalReach, params.entryReach) : params.entryReach;
	}

	private double arrivalReach(int cursor) {
		return cursor == path.size() - 1 ? terminalReach() : params.entryReach;
	}

	/**
	 * The waypoint the session steers to. {@code entryIndex} points at the
	 * first waypoint not yet reached, so the first entry is never skipped
	 * (R2b fix).
	 */
	public Waypoint currentTarget() {
		if (holding)
			return new Waypoint(holdX, holdY, holdZ);
		return path.get(Math.min(entryIndex, path.size() - 1));
	}

	/**
	 * The first useful horizontal departure target. A route often starts with
	 * an anchor beside the player's feet; aiming the launch boost there can
	 * point opposite to the first flight segment (ab-27/28: -45 vs -173 yaw).
	 * Returns null when the route supplies no horizontal departure direction.
	 */
	public Waypoint launchTarget(double x, double z) {
		Waypoint furthest = null;
		double furthestDistance = 1.0E-6;
		for (int index = entryIndex; index < path.size(); index++) {
			Waypoint point = path.get(index);
			double distance = Math.hypot(point.x() - x, point.z() - z);
			if (distance > params.entryReach)
				return point;
			if (distance > furthestDistance) {
				furthest = point;
				furthestDistance = distance;
			}
		}
		return furthest;
	}

	/**
	 * Selects one tick of input from the measured state. Applies nothing.
	 *
	 * @param rocketTicksRemaining measured rocket boost still pushing at the
	 *        start of this tick; the launch macro hands its own boost state
	 *        over here instead of restarting every prediction at zero (R2b fix)
	 * @param fireCooldownActive true while a previous fire is still inside its
	 *        cooldown window, so candidates that fire are skipped
	 */
	public Decision tick(double x, double y, double z, double vx, double vy, double vz,
			float yaw, float pitch, int rockets, int rocketTicksRemaining, long sessionTick, boolean fireCooldownActive) {
		Decision decision = new Decision();
		// Every return path carries this tick's telemetry (see Decision).
		lastDecision = decision;
		if (done) {
			decision.note = "done";
			decision.applicable = false;
			return decision;
		}
		if (System.currentTimeMillis() > deadlineMs) {
			done = true;
			endReason = EndReason.DEADLINE;
			decision.note = "deadline";
			decision.applicable = false;
			return decision;
		}

		long sequenceStartedAt = terminalSequence == null && planning == null ? 0 : System.nanoTime();
		if (sequenceStartedAt != 0) {
			physicsSteps = 0; blockQueries = 0; cacheHits = 0;
		}
		var measured = new FlightSequence.Origin(new FlightDynamics.State(x, y, z, vx, vy, vz, rocketTicksRemaining), yaw, pitch);
		if (planning != null) {
			var reserved = planning.beforeTick(measured, sessionTick,
					sequenceStartedAt + Math.max(1L, params.simBudgetMs) * 1_000_000L);
			decision.planningState = planning.status();
			decision.preparationUs = (System.nanoTime() - sequenceStartedAt) / 1_000L;
			if (reserved != null) {
				advanceEntries(x, y, z);
				decision.yaw = reserved.yaw; decision.pitch = reserved.pitch;
				decision.chosenPolicy = "planning_prefix";
				decision.note = planning.status();
				decision.routeId = routeId; decision.routeRevision = routeRevision;
				decision.cursor = entryIndex; decision.entryIndex = entryIndex;
				var target = currentTarget();
				decision.targetX = target.x(); decision.targetY = target.y(); decision.targetZ = target.z();
				decision.progress = progressOf(entryIndex, measured.state());
				var next = FlightDynamics.step(measured.state(), reserved);
				decision.predictedEndTicks = 1;
				decision.predictedEndX = next.x; decision.predictedEndY = next.y; decision.predictedEndZ = next.z;
				decision.predictedEndReason = "reserved_first_step";
				decision.feasible = 1;
				decision.physicsSteps = physicsSteps; decision.blockQueries = blockQueries; decision.cacheHits = cacheHits;
				decision.simMs = (System.nanoTime() - sequenceStartedAt) / 1_000_000L;
				if (System.nanoTime() - sequenceStartedAt >= Math.max(1L, params.simBudgetMs) * 1_000_000L) {
					planning.rejectReservation();
					decision.applicable = false; decision.budgetExhausted = true;
					decision.budgetReason = "planning_prefix_time"; decision.chosenPolicy = "";
					decision.planningState = planning.status();
				}
				return decision;
			}
		}
		if (terminalSequence != null) {
			physicsSteps = 0;
			blockQueries = 0;
			cacheHits = 0;
			FlightSequence.Step step = terminalSequence.tick(new FlightSequence.Origin(
					new FlightDynamics.State(x, y, z, vx, vy, vz, rocketTicksRemaining), yaw, pitch),
					sessionTick, routeId, routeRevision, sequenceStartedAt + Math.max(1L, params.simBudgetMs) * 1_000_000L);
			if (step != null) {
				// Reaching the positional radius does not finish this sequence.
				// Its verified tail keeps control until the controller sees touchdown.
				advanceEntries(x, y, z);
				decision.yaw = step.input().yaw;
				decision.pitch = step.input().pitch;
				decision.note = "sequence:" + terminalSequence.status();
				decision.chosenPolicy = decision.note;
				decision.note += " verifiedTicks=" + step.checkedTicks();
				decision.routeId = routeId;
				decision.routeRevision = routeRevision;
				decision.cursor = entryIndex;
				decision.entryIndex = entryIndex;
				Waypoint goal = path.get(path.size() - 1);
				decision.targetX = goal.x(); decision.targetY = goal.y(); decision.targetZ = goal.z();
				decision.entryDistance = Math.hypot(x - goal.x(), z - goal.z());
				decision.progress = progressOf(entryIndex, new FlightDynamics.State(x, y, z, vx, vy, vz, rocketTicksRemaining));
				decision.predictedEndTicks = 1;
				decision.predictedEndX = step.next().x;
				decision.predictedEndY = step.next().y;
				decision.predictedEndZ = step.next().z;
				decision.predictedEndReason = "sequence_first_step";
				decision.preview = step.preview();
				decision.terminalAction = "verified_landing_tail";
				decision.feasible = 1;
				decision.physicsSteps = physicsSteps;
				decision.blockQueries = blockQueries;
				decision.cacheHits = cacheHits;
				decision.simMs = (System.nanoTime() - sequenceStartedAt) / 1_000_000L;
				if (System.nanoTime() - sequenceStartedAt >= Math.max(1L, params.simBudgetMs) * 1_000_000L) {
					terminalSequence.budgetExpired();
					decision.applicable = false;
					decision.budgetExhausted = true;
					decision.budgetReason = "sequence_time";
					decision.chosenPolicy = "";
					decision.note = "sequence:rejected:budget";
				}
				return decision;
			}
			if ("rejected:budget".equals(terminalSequence.status())
					&& System.nanoTime() - sequenceStartedAt >= Math.max(1L, params.simBudgetMs) * 1_000_000L) {
				// Do not turn an unverified stop inside the arrival sphere into
				// CHANNEL_COMPLETE. The controller's undecided branch retains
				// control and checks a safe action in this same native tick.
				decision.applicable = false;
				decision.budgetExhausted = true;
				decision.budgetReason = "sequence_time";
				decision.note = "sequence:rejected:budget";
				decision.simMs = (System.nanoTime() - sequenceStartedAt) / 1_000_000L;
				return decision;
			}
		}

		advanceEntries(x, y, z);
		if (pathComplete && !holding) {
			if (params.stopAtEnd && (planning != null || (terminalSequence != null
					&& terminalSequence.status().startsWith("rejected:")))) {
				// A failed landing proof cannot become success just because its
				// current pose is inside the arrival sphere. Native contact is
				// handled by the controller before another airborne decision.
				done = true; endReason = EndReason.NO_VIABLE_TRAJECTORY;
				decision.applicable = false;
				decision.note = "landing_unconfirmed sequence=" + terminalSequenceStatus();
				return decision;
			}
			if (holdGraceMs > 0 && !params.stopAtEnd) {
				// The route is flown; hold instead of releasing input so the
				// host can hand the next leg over (R4 review item 4). A leg the
				// host marked `through` is a mid-route handover, so the wait
				// keeps the altitude instead of descending (ab-30 plan §2).
				beginHold(x, y, z, vx, vz, holdGraceMs, params.throughWaypoint);
			}
			else {
				done = true;
				endReason = EndReason.CHANNEL_COMPLETE;
				decision.note = "channel_complete";
				decision.applicable = false;
				return decision;
			}
		}
		if (pathComplete && holding && System.currentTimeMillis() > holdUntilMs) {
			// The hold is bounded: no handover arrived in the grace window, so
			// the session ends and the host owns the landing that follows.
			done = true;
			endReason = EndReason.HANDOVER_TIMEOUT;
			decision.note = "handover_timeout";
			decision.applicable = false;
			return decision;
		}
		if (holding) {
			// Heading hold, not a point hold: the waiting target stays ~48
			// blocks ahead of the CURRENT position along the current velocity.
			// A target fixed at hold start made the pilot fly to it and then
			// orbit it for the rest of the grace, firing to stay up (R4
			// lane-pert-timeout: straight for 20 ticks, then a full spin).
			double holdSpeed = Math.hypot(vx, vz);
			if (holdSpeed > 1.0E-4) {
				holdX = x + (vx / holdSpeed) * 48.0;
				holdZ = z + (vz / holdSpeed) * 48.0;
			}
			else {
				// No measurable heading: keep the last target (the glider is
				// stalling; the fire candidates own the recovery).
			}
		}

		Waypoint target = currentTarget();
		decision.entryIndex = entryIndex;
		decision.entryDistance = Math.hypot(target.x() - x, target.z() - z);

		double bearing = bearingTo(x, z, target);

		// Pitch candidates are relative to the AIM line, not to the current
		// attitude: the launch macro hands over nose-up (-35), and a candidate
		// set built around the current pitch could only pitch further up — the
		// driver then zoom-climbed to y~296 on rockets (R4 route-ab-05). The
		// geometry decides the span; the measured attitude no longer locks it.
		double horizontal = Math.max(1.0, Math.hypot(target.x() - x, target.z() - z));
		float aimPitch = (float) (-Math.toDegrees(Math.atan2(target.y() - y, horizontal)));

		// The channel's lower bound: the lowest altitude the remaining route
		// needs. A candidate that would sink below it (or below the surface
		// under its own predicted end) cannot continue flying the channel.
		double routeLowerBound = y;
		// The floor comes from the LOCAL reference window plus the terrain, not
		// from the whole remaining route: the far end of a leg would otherwise
		// set a different floor for the same measured state (ab-23 repair plan,
		// B3).
		for (int index = entryIndex; index < Math.min(path.size(), entryIndex + 3); index++)
			routeLowerBound = Math.min(routeLowerBound, path.get(index).y());
		columnCache.clear();
		frictionCache.clear();
		cellCache.clear();
		// Per-tick counters and first-reject detail start at zero every tick;
		// the cumulative totals live in separate fields (ab-23 repair plan,
		// A4/A5: an unreset terminal counter read 139 as if it were one tick's
		// rejections).
		rejectCollisions = 0;
		rejectFloor = 0;
		rejectSpeed = 0;
		rejectTerminal = 0;
		feasibleCount = 0;
		terminalChecked = 0;
		terminalVerifyExhausted = false;
		firstRejectKind = "";
		firstRejectTick = -1;
		firstRejectX = 0;
		firstRejectY = 0;
		firstRejectZ = 0;
		firstRejectBlock = "";
		firstRejectShape = "";

		long startedAt = sequenceStartedAt == 0 ? System.nanoTime() : sequenceStartedAt;
		long budgetNanos = Math.max(1L, params.simBudgetMs) * 1_000_000L;
		java.util.List<Candidate> feasible = new java.util.ArrayList<>();
		int evaluated = 0;
		boolean budgetExhausted = false;
		String budgetReason = "";
		if (sequenceStartedAt == 0) {
			physicsSteps = 0;
			blockQueries = 0;
			cacheHits = 0;
		}
		// Bounded candidate policies in one GLOBAL priority order (ab-24 audit
		// section 4: the previous winner must be re-checked first even when its
		// yaw group sits later in the offsets), then the absolute policies that
		// close the "no level option" gap, then the aim-relative ones, and the
		// ignition variants last so a budget cut never eats the safety actions.
		long orderingStartedAt = System.nanoTime();
		java.util.List<CandidateSpec> order = candidateOrder();
		decision.orderingUs = (System.nanoTime() - orderingStartedAt) / 1_000L;
		long candidatesStartedAt = System.nanoTime();
		search:
		for (CandidateSpec spec : order) {
			if (evaluated >= params.maxCandidates) {
				budgetExhausted = true;
				budgetReason = "candidate_cap";
				break search;
			}
			if (System.nanoTime() - startedAt > budgetNanos) {
				budgetExhausted = true;
				budgetReason = "time";
				break search;
			}
			if (spec.fire && (rockets <= 0 || fireCooldownActive))
				continue;
			// No stacking: while a rocket's boost is still active, a new
			// ignition would add a second thrust the single-countdown model
			// cannot express. The ab-17 audit measured speeds consistent with
			// two overlapping rockets after a mid-boost ignition (audit item 4);
			// until the model tracks entities explicitly, the control constraint
			// keeps one at a time.
			if (spec.fire && rocketTicksRemaining > 0)
				continue;
			Candidate candidate = evaluate(spec.yawOffset, spec.policy, spec.fire,
					x, y, z, vx, vy, vz, rocketTicksRemaining, rockets, sessionTick, routeLowerBound,
					yaw, pitch);
			evaluated++;
			if (candidate == null)
				continue;
			feasibleCount += 1;
			feasible.add(candidate);
		}
		decision.candidatesUs = (System.nanoTime() - candidatesStartedAt) / 1_000L;
		long selectionStartedAt = System.nanoTime();
		// A boost cannot be cancelled after ignition. Within one boost plus
		// one screening horizon of a final stop, preserve a viable glide
		// before buying more progress. This is a preference, not proof of a
		// landing: every candidate still passes the same terminal checks, and
		// thrust remains available when all glides fail them.
		double boostedSpeed = FlightDynamics.ROCKET_TARGET_SPEED
				+ FlightDynamics.ROCKET_BASE_ACCEL / FlightDynamics.ROCKET_PULL;
		double stopWindow = Math.max(Math.hypot(vx, vz), boostedSpeed)
				* (rocketMaximumTicks + params.horizonTicks);
		double remaining = cumulative[path.size() - 1] - progressOf(entryIndex,
				new FlightDynamics.State(x, y, z, vx, vy, vz, rocketTicksRemaining));
		Candidate best = selectVerifiedBest(feasible, params.stopAtEnd && remaining <= stopWindow);
		// A full feedback policy can hit the next bend after the worker's
		// handoff. Before buying irreversible thrust, try a short climbing
		// bridge. It is executable only after the planner reserves it, with
		// live prefix verification and the same absolute tick deadline.
		if (!budgetExhausted && (best == null || best.fire) && planning != null
				&& planning.canReserveShort(measured, sessionTick)) {
			java.util.List<Candidate> bridges = new java.util.ArrayList<>();
			for (int index = 0; index < Math.min(5, params.yawOffsets.length); index++) {
				if (System.nanoTime() >= startedAt + budgetNanos) break;
				float offset = params.yawOffsets[index];
				Candidate bridge = evaluate(offset, PitchPolicy.PULL_UP, false,
						x, y, z, vx, vy, vz, 0, rockets, sessionTick, routeLowerBound, yaw, pitch, 12);
				evaluated++;
				if (bridge != null) { feasibleCount++; bridges.add(bridge); }
			}
			for (Candidate bridge : rankByProgressAndQuality(bridges)) {
				if (planning.reserveShort(measured, bridge.controls, sessionTick, routeId, routeRevision, startedAt + budgetNanos)) {
					best = bridge;
					break;
				}
			}
		}
		decision.selectionUs = (System.nanoTime() - selectionStartedAt) / 1_000L;
		if (System.nanoTime() >= startedAt + budgetNanos) {
			budgetExhausted = true;
			budgetReason = "selection_time";
		}
		if (best != null) {
			lastWinnerYawOffset = best.yawOffset;
			lastWinnerPolicy = best.policy;
			lastWinnerFire = best.fire;
		}
		decision.score = best == null ? Double.POSITIVE_INFINITY : best.progress;
		int rejected = evaluated - feasibleCount;
		int totalCandidates = Math.max(order.size(), evaluated);
		int unevaluated = Math.max(0, totalCandidates - evaluated);
		// Cumulative totals carry their own names; the per-tick counters above
		// were reset at the start of this tick (A4/A5).
		cumulativeRejects += rejected;
		cumulativeCollisions += rejectCollisions;
		cumulativeFloor += rejectFloor;
		cumulativeSpeed += rejectSpeed;
		cumulativeTerminal += rejectTerminal;
		decision.note = "eval=" + evaluated + " ok=" + feasibleCount + " sim=" + ((System.nanoTime() - startedAt) / 1_000_000L) + "ms"
				+ " reject[c=" + rejectCollisions + ",f=" + rejectFloor + ",s=" + rejectSpeed
				+ ",ta=" + rejectTerminal + "]"
				+ (firstRejectKind.isEmpty() ? "" : String.format(
					" first=%s@t%d(%.1f,%.1f,%.1f)block=%s shape=%s",
					firstRejectKind, firstRejectTick, firstRejectX, firstRejectY, firstRejectZ,
					firstRejectBlock, firstRejectShape));
		if (terminalSequence != null) decision.note += " sequence=" + terminalSequence.status();
		// Per-tick telemetry: the rejection summary and the route cursor are
		// copied onto every decision, including the non-applicable ones, so the
		// live JSONL can explain each tick (ab-17 audit section 6).
		decision.cursor = entryIndex;
		decision.targetX = target.x();
		decision.targetY = target.y();
		decision.targetZ = target.z();
		decision.rejectKind = firstRejectKind;
		decision.rejectTick = firstRejectTick;
		decision.rejectX = firstRejectX;
		decision.rejectY = firstRejectY;
		decision.rejectZ = firstRejectZ;
		decision.rejectBlock = firstRejectBlock;
		decision.rejectShape = firstRejectShape;
		decision.rejectCollisions = rejectCollisions;
		decision.rejectFloor = rejectFloor;
		decision.rejectSpeed = rejectSpeed;
		decision.rejectTerminal = rejectTerminal;
		decision.evaluated = evaluated;
		decision.feasible = feasibleCount;
		decision.rejected = rejected;
		decision.unevaluated = unevaluated;
		decision.budgetExhausted = budgetExhausted;
		decision.budgetReason = budgetReason;
		decision.searchCompleted = !budgetExhausted;
		decision.cumulativeRejects = cumulativeRejects;
		decision.cumulativeCollisions = cumulativeCollisions;
		decision.cumulativeFloor = cumulativeFloor;
		decision.cumulativeSpeed = cumulativeSpeed;
		decision.cumulativeTerminal = cumulativeTerminal;
		decision.routeId = routeId;
		decision.routeRevision = routeRevision;
		decision.progress = progressOf(entryIndex, new FlightDynamics.State(x, y, z, vx, vy, vz, 0));
		decision.physicsSteps = physicsSteps;
		decision.blockQueries = blockQueries;
		decision.cacheHits = cacheHits;
		decision.simMs = (System.nanoTime() - startedAt) / 1_000_000L;

		if (best == null) {
			if (budgetExhausted) {
				// The search was interrupted before any candidate was fully
				// vetted: that is NOT proof that no route exists (ab-24 audit
				// section 4). Keep the session alive and re-select next tick.
				decision.note += " search_incomplete reason=" + budgetReason
						+ " evaluated=" + evaluated + "/" + totalCandidates;
				decision.applicable = false;
				return decision;
			}
			if (terminalVerifyExhausted) {
				// The verifications that ran all failed and candidates below
				// them were never checked: unverified, not impossible.
				decision.note += " terminal_unverified checked=" + terminalChecked
						+ "/" + feasibleCount;
				decision.applicable = false;
				return decision;
			}
			done = true;
			endReason = EndReason.NO_VIABLE_TRAJECTORY;
			// Keep the evaluation note and add the failing context: whether any
			// candidate was vetted separates a geometry refusal from a budget
			// one, and the target/entry names the leg that failed (R4 telemetry).
			decision.note = String.format(
					"no_viable_trajectory %s entry=%d/%d target=%.1f,%.1f,%.1f at=%.1f,%.1f,%.1f pitch=%.1f",
					decision.note, entryIndex, path.size(),
					target.x(), target.y(), target.z(),
					x, y, z, pitch);
			decision.applicable = false;
			return decision;
		}

		// Execution applies exactly the first step the prediction used.
		decision.yaw = best.firstYaw;
		decision.pitch = best.firstPitch;
		decision.fireRocket = best.fire;
		decision.predictedEndTicks = best.endTicks;
		decision.predictedEndCursor = best.endCursor;
		decision.predictedEndX = best.endX;
		decision.predictedEndY = best.endY;
		decision.predictedEndZ = best.endZ;
		decision.predictedEndReason = best.endReason;
		decision.predictedEndProgress = best.progress;
		decision.chosenPolicy = best.policyId;
		decision.preview = best.preview;
		decision.terminalAction = best.terminalAction;
		if (best.fire)
			lastFireDecisionTick = (int) sessionTick;
		if (planning != null && !best.fire) {
			long reservationStartedAt = System.nanoTime();
			planning.consider(measured, best.controls, sessionTick, routeId, routeRevision, startedAt + budgetNanos);
			decision.reservationUs = (System.nanoTime() - reservationStartedAt) / 1_000L;
			decision.planningState = planning.status();
			decision.physicsSteps = physicsSteps;
			decision.blockQueries = blockQueries;
			decision.cacheHits = cacheHits;
			decision.simMs = (System.nanoTime() - startedAt) / 1_000_000L;
			if (System.nanoTime() >= startedAt + budgetNanos) {
				decision.budgetExhausted = true;
				decision.budgetReason = "planning_initial_prefix_time";
			}
		}
		lastDecision = decision;
		return decision;
	}

	/** The last decision this session produced; per-tick telemetry only. */
	public Decision lastDecision() {
		return lastDecision;
	}

	/** True while a fired rocket's cooldown still covers a new fire decision. */
	public boolean fireCooldownActive(long sessionTick) {
		return sessionTick - lastFireDecisionTick < params.fireCooldownTicks;
	}

	/** Ticks of fire cooldown still left, for a session handover to inherit. */
	public int fireCooldownRemaining(long sessionTick) {
		if (lastFireDecisionTick == Integer.MIN_VALUE)
			return 0;
		int remaining = (int) (params.fireCooldownTicks - (sessionTick - lastFireDecisionTick));
		return Math.max(0, remaining);
	}

	/** The configured fire cooldown, in ticks. */
	public int fireCooldownTicks() {
		return params.fireCooldownTicks;
	}

	/**
	 * Inherits an unfinished fire cooldown from the session this one replaces.
	 *
	 * A handover must not clear a cooldown that a `fire` decision just
	 * started: the new session would be allowed to fire again immediately
	 * (R4 client review 2026-09-21, group 1). Boost needs no inheritance —
	 * the controller measures it every tick and passes it in.
	 */
	public void inheritFireCooldown(int ticksRemaining) {
		if (ticksRemaining <= 0) {
			lastFireDecisionTick = Integer.MIN_VALUE;
			return;
		}
		// sessionTick starts at 0 for the new session. Placing the decision
		// `cooldown - remaining` ticks in the past leaves exactly
		// `ticksRemaining` ticks of cooldown.
		int clamped = Math.min(ticksRemaining, params.fireCooldownTicks);
		lastFireDecisionTick = -(params.fireCooldownTicks - clamped);
	}

	private Candidate evaluate(float yawOffset, PitchPolicy pitchPolicy, boolean fire,
			double x, double y, double z, double vx, double vy, double vz, int rocketTicksRemaining,
			int rockets, long sessionTick, double routeLowerBound, float currentYaw, float currentPitch) {
		return evaluate(yawOffset, pitchPolicy, fire, x, y, z, vx, vy, vz, rocketTicksRemaining,
				rockets, sessionTick, routeLowerBound, currentYaw, currentPitch, params.horizonTicks);
	}

	private Candidate evaluate(float yawOffset, PitchPolicy pitchPolicy, boolean fire,
			double x, double y, double z, double vx, double vy, double vz, int rocketTicksRemaining,
			int rockets, long sessionTick, double routeLowerBound, float currentYaw, float currentPitch, int horizonTicks) {
		FlightDynamics.State state = new FlightDynamics.State(
				x, y, z, vx, vy, vz, Math.max(0, rocketTicksRemaining));
		boolean rocketPlanned = false;
		double minY = y;
		boolean arrived = false;
		double arrivedMinY = y;
		double arrivedX = x;
		double arrivedY = y;
		double arrivedZ = z;
		double arrivedVy = vy;
		double arrivedVx = vx;
		double arrivedVz = vz;
		int arrivedRocketTicks = Math.max(0, rocketTicksRemaining);
		int arrivedTick = -1;
		int cursor = entryIndex;
		// First 1/3/5 tick prediction points of this candidate (audit section 6).
		double[] preview = new double[9];
		int ticks = 0;
		float firstYaw = 0f;
		float firstPitch = 0f;
		float previousStepYaw = currentYaw;
		float previousStepPitch = currentPitch;
		float arrivedYaw = currentYaw;
		float arrivedPitch = currentPitch;
		List<FlightDynamics.Input> controls = params.terminalPlanning ? new java.util.ArrayList<>() : List.of();
		// A candidate is a bounded FEEDBACK POLICY, not a frozen attitude: the
		// reference and the aim are recomputed every simulated tick, so passing
		// a waypoint changes the direction instead of continuing the dive
		// (ab-23 repair plan, B1/B3). The loop may run past the horizon once
		// arrived so the terminal check is never cut short by the tail (B4).
		// The horizon is conditional: a normal candidate runs `horizonTicks`;
		// only an ARRIVED one extends by the terminal lookahead so the terminal
		// check is never cut short (ab-24 audit section 4: an unconditional
		// horizon+6 wasted a fifth of every candidate's physics).
		int maxTicks = horizonTicks + TERMINAL_LOOKAHEAD_TICKS;
		int screeningTicks = horizonTicks;
		double remaining = cumulative[path.size() - 1] - progressOf(entryIndex, state);
		double boostedSpeed = FlightDynamics.ROCKET_TARGET_SPEED
				+ FlightDynamics.ROCKET_BASE_ACCEL / FlightDynamics.ROCKET_PULL;
		if (params.stopAtEnd && remaining <= Math.max(Math.hypot(vx, vz), boostedSpeed)
				* (rocketMaximumTicks + params.horizonTicks)) {
			// Thrust cannot be cancelled. A stop approach must screen the
			// complete powered interval, not hide its tail beyond 20 ticks.
			// The same 12 ms budget still applies; incomplete work is undecided.
			screeningTicks = Math.max(screeningTicks, fire ? rocketMaximumTicks : rocketTicksRemaining);
			maxTicks = screeningTicks + TERMINAL_LOOKAHEAD_TICKS;
		}
		for (int tick = 0; tick < maxTicks; tick++) {
			if (tick >= screeningTicks && !arrived)
				break;
			// Missing a terminal arrival volume is not completion. Do not turn
			// back across the route to chase it; let the controller recover.
			if (cursor == path.size() - 1 && beyondHorizontalPlane(cursor, state.x, state.y, state.z)) {
				Waypoint terminal = path.get(cursor);
				if (dist3(terminal.x() - state.x, terminal.y() - state.y, terminal.z() - state.z) > terminalReach()) {
					rejectTerminal += 1;
					recordFirstReject("terminal_missed", tick, state.x, state.y, state.z, "");
					return null;
				}
			}
			Waypoint reference = referenceFor(cursor, state);
			double horizontal = Math.max(1.0, Math.hypot(reference.x() - state.x, reference.z() - state.z));
			float aimPitch = (float) (-Math.toDegrees(Math.atan2(reference.y() - state.y, horizontal)));
			float rawYaw = normalizeBearing(bearingTo(state.x, state.z, reference) + yawOffset);
			// The steering rate is limited identically in prediction and
			// execution (B2): a hard turn happens over several ticks instead of
			// snapping the view and overflying the outer wall.
			float stepYaw = limitYaw(tick == 0 ? currentYaw : previousStepYaw, rawYaw, params.yawRateLimitDegrees);
			previousStepYaw = stepYaw;
			float stepPitch = limitPitch(previousStepPitch, pitchFor(pitchPolicy, aimPitch), params.pitchRateLimitDegrees);
			previousStepPitch = stepPitch;
			if (tick == 0) {
				firstYaw = stepYaw;
				firstPitch = stepPitch;
			}
			boolean fireThisTick = fire && tick == 0;
			if (params.terminalPlanning) controls.add(new FlightDynamics.Input(stepYaw, stepPitch, fireThisTick, rocketDurationTicks));
			physicsSteps += 1;
			FlightDynamics.State next = FlightDynamics.step(state,
					new FlightDynamics.Input(stepYaw, stepPitch, fireThisTick, rocketDurationTicks));
			if (fireThisTick)
				rocketPlanned = true;
			// Segment sweep, not a point sample: a one-block-per-tick step can
			// cut a corner diagonally or pass a thin wall between two sampled
			// cells (R4: run 09/12 damage during flight). The segment between
			// the two tick states is sampled at a bounded step.
			if (!sweepSegmentClear(state, next)) {
				rejectCollisions += 1;
				recordFirstReject("collision", tick, sweepFailX, sweepFailY, sweepFailZ, probeId);
				return null;
			}
			minY = Math.min(minY, next.y);
			state = next;
			ticks = tick + 1;
			if (tick == 0) {
				preview[0] = state.x;
				preview[1] = state.y;
				preview[2] = state.z;
			}
			else if (tick == 2) {
				preview[3] = state.x;
				preview[4] = state.y;
				preview[5] = state.z;
			}
			else if (tick == 4) {
				preview[6] = state.x;
				preview[7] = state.y;
				preview[8] = state.z;
			}
			// The virtual cursor advances over every entry the prediction
			// reaches, with the same arrival semantics the real driver uses.
			// Reaching the TERMINAL does not end the prediction: the trajectory
			// is still swept for a bounded lookahead window so an obstacle just
			// past the terminal is seen (ab-17 audit item 2: the old break
			// turned a 20-tick prediction into 1 tick and missed the leaves).
			if (!arrived) {
				// The same cursor semantics the real driver uses: the arrival
				// sphere AND the passed-by-plane skip, with the terminal never
				// plane-completed (ab-24 audit section 5).
				while (cursor < path.size()) {
					Waypoint entry = path.get(cursor);
					if (dist3(entry.x() - state.x, entry.y() - state.y, entry.z() - state.z) <= arrivalReach(cursor)) {
						cursor += 1;
						continue;
					}
					if (passedPlane(cursor, state.x, state.y, state.z)) {
						cursor += 1;
						continue;
					}
					break;
				}
				if (cursor >= path.size()) {
					arrived = true;
					arrivedTick = tick;
					arrivedMinY = minY;
					arrivedX = state.x;
					arrivedY = state.y;
					arrivedZ = state.z;
					arrivedVy = state.vy;
					arrivedVx = state.vx;
					arrivedVz = state.vz;
					arrivedRocketTicks = Math.max(0, state.rocketTicksRemaining);
					arrivedYaw = stepYaw;
					arrivedPitch = stepPitch;
					// Explicit stops switch ownership to recovery at arrival.
					// validateTerminal checks that recovery from this exact state;
					// extending the route policy here predicts an action the
					// controller never executes and can falsely hit a wall.
					if (params.stopAtEnd)
						break;
				}
			}
			else if (tick - arrivedTick >= TERMINAL_LOOKAHEAD_TICKS) {
				break;
			}
		}
		// Continuation feasibility (R4 client review 2 and 2026-09-21 follow-up):
		// can this trajectory keep flying? Below the surface under its own end
		// means it cannot; below the speed floor means it has no energy left to
		// steer with. An ARRIVED candidate is checked at its ARRIVAL state, not
		// at the end of the post-terminal lookahead (the terminal action — the
		// hold — may fire and climb; the lookahead only screens collisions).
		double checkedMinY = arrived ? arrivedMinY : minY;
		double clearance = arrived ? ARRIVAL_SURFACE_CLEARANCE : CONTINUE_CLEARANCE;
		// A slow, unpowered final approach may descend below the cruise
		// energy reserve over solid support. This never allows body contact:
		// the full trajectory has already passed both collision sweeps.
		if (!arrived && permitsStopDescent(state, rocketPlanned))
			clearance = ARRIVAL_SURFACE_CLEARANCE;
		double floorY = Math.max(
			arrived ? Double.NEGATIVE_INFINITY : routeLowerBound - CHANNEL_LOWER_SLACK,
			surfaceBelow(state.x, state.z, state.y) + clearance);
		double sinkLimit = Math.min(y, floorY) - CONTINUE_FLOOR_TOLERANCE;
		if (checkedMinY < sinkLimit) {
			rejectFloor += 1;
			recordFirstReject(arrived ? "arrival_floor" : "floor", screeningTicks, state.x, checkedMinY, state.z, "");
			return null;
		}
		if (Math.hypot(arrived ? arrivedVx : state.vx, arrived ? arrivedVz : state.vz) < CONTINUE_SPEED) {
			rejectSpeed += 1;
			recordFirstReject(arrived ? "arrival_speed" : "speed", screeningTicks, state.x, state.y, state.z, "");
			return null;
		}
		String terminalAction = arrived ? (holdGraceMs > 0 ? "unverified" : "land") : "none";
		// The expensive terminal verification runs in a SECOND phase, on the
		// best-progress candidates first (ab-24 audit section 4: sort, then
		// verify; never treat unchecked candidates as unsafe, and never trim
		// the check itself). The complete arrival snapshot is kept here.
		Waypoint endReference = referenceFor(cursor, state);
		Candidate candidate = new Candidate();
		candidate.controls = controls;
		candidate.yawOffset = yawOffset;
		candidate.policy = pitchPolicy;
		candidate.policyId = String.format(java.util.Locale.ROOT, "%+.0f:%s:%s", yawOffset, pitchPolicy.name(), fire ? "fire" : "glide");
		candidate.firstYaw = firstYaw;
		candidate.firstPitch = firstPitch;
		candidate.fire = rocketPlanned;
		candidate.arrived = arrived;
		candidate.arrivalX = arrivedX;
		candidate.arrivalY = arrivedY;
		candidate.arrivalZ = arrivedZ;
		candidate.arrivalVx = arrivedVx;
		candidate.arrivalVy = arrivedVy;
		candidate.arrivalVz = arrivedVz;
		candidate.arrivalRocketTicks = arrivedRocketTicks;
		candidate.arrivalYaw = arrivedYaw;
		candidate.arrivalPitch = arrivedPitch;
		candidate.rocketsAtStart = rockets;
		candidate.endTicks = ticks;
		candidate.endCursor = cursor;
		candidate.endX = state.x;
		candidate.endY = state.y;
		candidate.endZ = state.z;
		candidate.endReason = arrived ? "arrived_lookahead" : "horizon";
		candidate.preview = preview;
		candidate.terminalAction = terminalAction;
		// Route progress and tracking quality are separate from firework cost;
		// the selection order lives in {@link #selectBest} (B3: no pairwise
		// "within one block counts as equal" comparator — that relation is not
		// transitive and made the enumeration order decide the winner).
		candidate.progress = progressOf(cursor, state);
		candidate.lateral = Math.hypot(state.x - endReference.x(), state.z - endReference.z());
		candidate.heightError = Math.abs(state.y - endReference.y());
		candidate.trackingErrorSquared = cursor < path.size()
				? routeTrackingErrorSquared(cursor, state) : candidate.lateral * candidate.lateral;
		candidate.controlChange = Math.abs(stepPitch(currentPitch, firstPitch)) + Math.abs(bearingDelta(firstYaw, currentYaw));
		return candidate;
	}

	/** Distance to the nearby route polyline, without rewarding forward travel twice. */
	private double routeTrackingErrorSquared(int cursor, FlightDynamics.State state) {
		double best = Double.POSITIVE_INFINITY;
		int end = Math.max(1, Math.min(cursor, path.size() - 1));
		// Entry spheres advance the cursor ahead of the body. Include the
		// preceding local segments, bounded by the same range as passedPlane.
		// A distant return bend must not count as the current flight corridor.
		for (int i = end; i > 0; i--) {
			Waypoint a = path.get(i - 1), b = path.get(i);
			double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
			double lengthSquared = dx * dx + dy * dy + dz * dz;
			double fraction = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
					((state.x - a.x()) * dx + (state.y - a.y()) * dy + (state.z - a.z()) * dz) / lengthSquared));
			double ex = state.x - a.x() - fraction * dx;
			double ey = state.y - a.y() - fraction * dy;
			double ez = state.z - a.z() - fraction * dz;
			best = Math.min(best, ex * ex + ey * ey + ez * ez);
			if (cumulative[end] - cumulative[i - 1] >= PASSED_PLANE_RANGE) break;
		}
		return best;
	}

	/** A local descent envelope, distinct from actual touchdown authorization. */
	private boolean permitsStopDescent(FlightDynamics.State end, boolean fired) {
		if (!params.stopAtEnd || fired || end.rocketTicksRemaining > 0
				|| Math.hypot(end.vx, end.vz) > 1.0 || end.vy > 0 || end.vy < -0.45)
			return false;
		Waypoint goal = path.get(path.size() - 1);
		// At most two screening horizons at the approach speed ceiling.
		if (Math.hypot(end.x - goal.x(), end.z - goal.z()) > params.horizonTicks * 2.0)
			return false;
		return supportedApproachFootprint(end.x, end.y, end.z, CONTINUE_CLEARANCE)
				&& supportedApproachFootprint(goal.x(), goal.y(), goal.z(), 2.0)
				&& boxClear(goal.x(), goal.y(), goal.z())
				// Nearby support does not mean the remaining bend is a landing
				// approach. Keep cruise reserve until the body has a clear descent
				// corridor to the goal. Actual touchdown still needs its own check.
				&& sweepLinearClear(end, new FlightDynamics.State(goal.x(), goal.y(), goal.z(), 0, 0, 0, 0));
	}

	/** Every footprint column needs known safe support within the descent envelope. */
	private boolean supportedApproachFootprint(double x, double y, double z, double maxHeight) {
		double half = params.poseHalfWidth + params.inflate;
		for (int bx = (int)Math.floor(x - half); bx < Math.ceil(x + half); bx++) {
			for (int bz = (int)Math.floor(z - half); bz < Math.ceil(z + half); bz++) {
				double surface = surfaceBelow(bx + .5, bz + .5, y);
				if (y < surface + params.inflate || y - surface > maxHeight
						|| FlightGeometry.isUnsafeSupport(cellAt(bx, surface - 1, bz)))
					return false;
			}
		}
		return true;
	}

	/** The local reference of the simulated progress: entry, or the hold target. */
	private Waypoint referenceFor(int cursor, FlightDynamics.State state) {
		if (cursor < path.size())
			return path.get(cursor);
		// After arrival the prediction follows the same hold reference the real
		// driver uses: 48 blocks along the current heading at the beginHold
		// altitude (B4).
		double speed = Math.hypot(state.vx, state.vz);
		double dirX;
		double dirZ;
		if (speed > 1.0E-4) {
			dirX = state.vx / speed;
			dirZ = state.vz / speed;
		}
		else {
			// No measurable heading: a target AT the state would make the
			// bearing atan2(0, 0) and steer the prediction east. The real hold
			// keeps its last target; the prediction keeps the LAST ROUTE
			// SEGMENT's direction, which is the flight direction it arrived on
			// (never a degenerate bearing, never a reversed one).
			Waypoint last = path.get(path.size() - 1);
			Waypoint previous = path.get(path.size() - 2);
			double dx = last.x() - previous.x();
			double dz = last.z() - previous.z();
			double length = Math.hypot(dx, dz);
			if (length <= 1.0E-6) {
				dirX = 0.0;
				dirZ = 0.0;
			}
			else {
				dirX = dx / length;
				dirZ = dz / length;
			}
		}
		double surface = surfaceBelow(state.x, state.z, state.y);
		double targetY = Math.max(surface + HOLD_CLEARANCE, state.y - HOLD_MAX_DESCENT);
		if (state.y - surface < HOLD_MIN_CLEARANCE)
			targetY = state.y;
		targetY = Math.max(targetY, surface + HOLD_CLEARANCE);
		return new Waypoint(state.x + dirX * 48.0, targetY, state.z + dirZ * 48.0);
	}

	/** Monotonic route progress at the simulated state (B3). */
	private double progressOf(int cursor, FlightDynamics.State state) {
		if (cursor >= path.size())
			return cumulative[path.size() - 1];
		Waypoint reference = path.get(cursor);
		return cumulative[cursor] - dist3(reference.x() - state.x, reference.y() - state.y, reference.z() - state.z);
	}

	/** The pitch of one policy for the current aim angle. */
	private float pitchFor(PitchPolicy policy, float aimPitch) {
		switch (policy) {
			case AIM:
				return clampPitch(aimPitch);
			case AIM_PULL:
				return clampPitch(aimPitch + params.aimPullDegrees);
			case LEVEL:
				return 0f;
			case PULL_UP:
				return clampPitch(params.pullUpDegrees);
			default:
				return clampPitch(aimPitch);
		}
	}

	private static double stepPitch(float from, float to) {
		return Math.abs(to - from);
	}

	private static double bearingDelta(float from, float to) {
		double delta = to - from;
		while (delta > 180.0) delta -= 360.0;
		while (delta < -180.0) delta += 360.0;
		return delta;
	}
	/** Quality difference within which two candidates count as equivalent. */
	private static final double QUALITY_TIE_BLOCKS = 1.0;

	/**
	 * The policy order inside one (yaw, fire) pair (B2): the previous tick's
	 * winner first, then the absolute policies (LEVEL, PULL_UP), then the
	 * aim-relative ones. A budget cut therefore keeps the safety actions.
	 */
	private PitchPolicy[] orderedPolicies(float yawOffset, boolean fire) {
		java.util.List<PitchPolicy> order = new java.util.ArrayList<>();
		if (!fire && lastWinnerPolicy != null
				&& Math.abs(yawOffset - lastWinnerYawOffset) < 0.01f && lastWinnerFire == fire)
			order.add(lastWinnerPolicy);
		order.add(PitchPolicy.LEVEL);
		order.add(PitchPolicy.PULL_UP);
		for (PitchPolicy policy : new PitchPolicy[] { PitchPolicy.AIM, PitchPolicy.AIM_PULL }) {
			if (!order.contains(policy))
				order.add(policy);
		}
		return order.toArray(new PitchPolicy[0]);
	}

	/** One (yaw, policy, fire) candidate in the global priority order. */
	private record CandidateSpec(float yawOffset, PitchPolicy policy, boolean fire) {}

	/**
	 * The bounded candidate list in one global priority order (ab-24 audit
	 * section 4): the previous tick's winner first (re-checked with the current
	 * state), then LEVEL/PULL_UP for every yaw, then AIM/AIM_PULL for every
	 * yaw, then all fire variants. A budget cut keeps the baseline and the
	 * absolute safety policies.
	 */
	private java.util.List<CandidateSpec> candidateOrder() {
		java.util.List<CandidateSpec> order = new java.util.ArrayList<>();
		java.util.Set<CandidateSpec> seen = new java.util.HashSet<>();
		java.util.function.Consumer<CandidateSpec> add = spec -> {
			if (seen.add(spec))
				order.add(spec);
		};
		if (lastWinnerPolicy != null)
			add.accept(new CandidateSpec(lastWinnerYawOffset, lastWinnerPolicy, lastWinnerFire));
		for (float yawOffset : params.yawOffsets) {
			add.accept(new CandidateSpec(yawOffset, PitchPolicy.LEVEL, false));
			add.accept(new CandidateSpec(yawOffset, PitchPolicy.PULL_UP, false));
		}
		for (float yawOffset : params.yawOffsets) {
			add.accept(new CandidateSpec(yawOffset, PitchPolicy.AIM, false));
			add.accept(new CandidateSpec(yawOffset, PitchPolicy.AIM_PULL, false));
		}
		for (float yawOffset : params.yawOffsets) {
			for (PitchPolicy policy : PitchPolicy.values())
				add.accept(new CandidateSpec(yawOffset, policy, true));
		}
		return order;
	}

	/**
	 * Two-phase selection (ab-24 audit section 4): the cheap phase already
	 * rejected collisions/floors/speeds; this phase sorts the survivors by
	 * route progress (best first, quality as the tie-break) and runs the
	 * expensive terminal verification only until the first candidate passes.
	 * Candidates below it are NOT treated as unsafe — they simply lose on
	 * progress and are left unverified, which the counters report.
	 */
	private Candidate selectVerifiedBest(java.util.List<Candidate> feasible, boolean preferGlide) {
		if (feasible.isEmpty())
			return null;
		// An explicit stop still needs a verified recovery. A zero handover
		// grace only disables holding; it must not bypass the ending check.
		if (holdGraceMs <= 0 && !params.stopAtEnd) {
			Candidate best = selectBest(feasible);
			if (best != null && best.arrived)
				best.terminalAction = "land";
			return best;
		}
		// The equivalence band comes FIRST (B3): candidates within one block of
		// the best progress are ordered by distance to the local 3D route,
		// height deviation and control change.
		// Sorting by raw progress made a slightly-further but hard-turning
		// candidate win, which showed up as violent yaw swings in flight. The
		// terminal verification runs inside that quality order; only when the
		// whole band fails does the out-of-band tail get its turn, by progress.
		java.util.List<Candidate> ordered = new java.util.ArrayList<>();
		if (preferGlide) {
			java.util.List<Candidate> glides = new java.util.ArrayList<>();
			java.util.List<Candidate> thrusts = new java.util.ArrayList<>();
			for (Candidate candidate : feasible) {
				if (candidate.fire) thrusts.add(candidate);
				else glides.add(candidate);
			}
			ordered.addAll(rankByProgressAndQuality(glides));
			ordered.addAll(rankByProgressAndQuality(thrusts));
		}
		else ordered.addAll(rankByProgressAndQuality(feasible));
		int checked = 0;
		for (Candidate candidate : ordered) {
			if (!candidate.arrived) {
				candidate.terminalAction = "none";
				return candidate;
			}
			if (checked >= TERMINAL_VERIFY_MAX) {
				// The check budget for this tick is spent; the remaining
				// candidates are unverified, not rejected. Nothing below the
				// checked ones can be the winner, so the tick is undecided.
				terminalVerifyExhausted = true;
				return null;
			}
			checked += 1;
			if (params.stopAtEnd) {
				SafeFrame ending = recoveryFrame(candidate.arrivalX, candidate.arrivalY, candidate.arrivalZ,
						candidate.arrivalVx, candidate.arrivalVy, candidate.arrivalVz,
						candidate.arrivalRocketTicks, candidate.rocketsAtStart - (candidate.fire ? 1 : 0),
						candidate.arrivalYaw, candidate.arrivalPitch, candidate.arrivalYaw,
						FlightRecovery.DESCENT_PITCH, 14);
				terminalChecked += 1;
				if (ending.verified > 0) {
					candidate.terminalAction = "recover";
					return candidate;
				}
				rejectTerminal += 1;
				recordFirstReject("terminal_action", ending.contactTicks, sweepFailX, sweepFailY, sweepFailZ, probeId);
				continue;
			}
			int holdFailTick = verifyHold(new FlightDynamics.State(
					candidate.arrivalX, candidate.arrivalY, candidate.arrivalZ,
					candidate.arrivalVx, candidate.arrivalVy, candidate.arrivalVz,
					candidate.arrivalRocketTicks), candidate.rocketsAtStart - (candidate.fire ? 1 : 0),
					candidate.arrivalYaw, candidate.arrivalPitch);
			terminalChecked += 1;
			if (holdFailTick == -1 || holdFailTick == -2) {
				candidate.terminalAction = holdFailTick == -2 ? "land" : "hold";
				return candidate;
			}
			rejectTerminal += 1;
			recordFirstReject("terminal_action", holdFailTick, sweepFailX, sweepFailY, sweepFailZ, probeId);
		}
		return null;
	}

	/** Preserve the cruise equivalence band within each stop-approach energy choice. */
	private static java.util.List<Candidate> rankByProgressAndQuality(java.util.List<Candidate> feasible) {
		double bestProgress = Double.NEGATIVE_INFINITY;
		for (Candidate candidate : feasible)
			bestProgress = Math.max(bestProgress, candidate.progress);
		java.util.List<Candidate> band = new java.util.ArrayList<>();
		java.util.List<Candidate> tail = new java.util.ArrayList<>();
		for (Candidate candidate : feasible) {
			if (candidate.progress >= bestProgress - QUALITY_TIE_BLOCKS)
				band.add(candidate);
			else tail.add(candidate);
		}
		band.sort(FlightSession::compareQuality);
		tail.sort((a, b) -> {
			int byProgress = Double.compare(b.progress, a.progress);
			return byProgress != 0 ? byProgress : compareQuality(a, b);
		});
		band.addAll(tail);
		return band;
	}

	/** How many terminal verifications one tick may run (bounded workload). */
	private static final int TERMINAL_VERIFY_MAX = 6;

	/**
	 * The global candidate selection (ab-23 repair plan, B3): find the maximum
	 * route progress, keep the candidates within the equivalence band, and
	 * order the band by tracking quality, then fire cost, then the stable
	 * policy id. Deliberately NOT a pairwise comparator: "within one block
	 * counts as equal" is not transitive, so a rolling comparison let the
	 * enumeration order pick the winner.
	 */
	private static Candidate selectBest(java.util.List<Candidate> feasible) {
		if (feasible.isEmpty())
			return null;
		double bestProgress = Double.NEGATIVE_INFINITY;
		for (Candidate candidate : feasible)
			bestProgress = Math.max(bestProgress, candidate.progress);
		java.util.List<Candidate> band = new java.util.ArrayList<>();
		for (Candidate candidate : feasible) {
			if (candidate.progress >= bestProgress - QUALITY_TIE_BLOCKS)
				band.add(candidate);
		}
		// An explicit TOTAL order (the unique policy id is the last key), then
		// take the first: an incremental "strictly better" scan depends on the
		// enumeration order when two candidates compare equal on every key.
		band.sort((a, b) -> compareQuality(a, b));
		return band.get(0);
	}

	/** Lexicographic quality order inside the progress band; total by id. */
	private static int compareQuality(Candidate a, Candidate b) {
		int result = Double.compare(a.trackingErrorSquared, b.trackingErrorSquared);
		if (result != 0)
			return result;
		result = Double.compare(a.heightError, b.heightError);
		if (result != 0)
			return result;
		result = Double.compare(a.controlChange, b.controlChange);
		if (result != 0)
			return result;
		result = Boolean.compare(a.fire, b.fire);
		if (result != 0)
			return result;
		// A NEUTRAL final tie-break: the smallest absolute yaw offset first
		// (the string form of the policy id sorts "+" before "-", which made
		// tied candidates systematically turn right).
		result = Float.compare(Math.abs(a.yawOffset), Math.abs(b.yawOffset));
		if (result != 0)
			return result;
		result = Float.compare(a.yawOffset, b.yawOffset);
		if (result != 0)
			return result;
		result = Integer.compare(a.policy.ordinal(), b.policy.ordinal());
		if (result != 0)
			return result;
		return a.policyId.compareTo(b.policyId);
	}

	/** Records the first rejection of this tick with its predicted context. */
	private void recordFirstReject(String kind, int tick, double px, double py, double pz, String block) {
		if (!firstRejectKind.isEmpty())
			return;
		firstRejectKind = kind;
		firstRejectTick = tick;
		firstRejectX = px;
		firstRejectY = py;
		firstRejectZ = pz;
		firstRejectBlock = block == null ? "" : block;
		// The pose-box probe already recorded the cell it failed on; carry its
		// shape tag for the first rejection of the tick (null block = the
		// failure was a floor/speed rule, not a cell).
		firstRejectShape = firstRejectBlock.isEmpty() ? "" : probeShape;
	}

	/**
	 * A VERIFIED support landing (ab-23 repair plan, B4): the contact must be
	 * the floor under the body, the floor block must be a real support (not
	 * water, leaves, lava, fire or a passable plant) and the body must have
	 * headroom above the contact cell. "Eight ticks later it touches a block
	 * with a small vy" is not enough: a side wall, a canopy or a water surface
	 * is not a landing.
	 */
	private boolean isSupportLanding(double px, double py, double pz) {
		double below = surfaceBelow(px, pz, py + 1.0);
		if (py - below > 1.5)
			return false;
		blockQueries += 1;
		String cell = blocks.idAt(px, below - 1.0, pz);
		if (FlightGeometry.isUnsafeSupport(cell))
			return false;
		// Headroom for the standing body above the contact cell.
		return boxClear(px, py + 1.0, pz);
	}

	/**
	 * Simulates the terminal HOLD from the arrival state. Returns -1 when the
	 * window is survivable (`hold`), -2 when the hold only reaches a mild
	 * touch-down after the near-term window (`land`), or the first failing
	 * step inside the near-term window (terminal refusal).
	 *
	 * <p>The hold refreshes its target along the current heading 48 blocks
	 * ahead at the altitude {@link #beginHold} would pick. At each step the
	 * same pitch-offset candidate set is tried (fire only when no gliding
	 * candidate survives and a rocket is usable), so a state that needs an
	 * offset or a rocket to survive the hold is still accepted; a state that
	 * can do neither is a terminal-refusal (ab-17 audit item 2: arrival is not
	 * proof the hold can fly — both ab-17 terminals were reached 1 tick after
	 * the last steer and the hold's first step hit leaves/dirt).
	 */
	private int verifyHold(FlightDynamics.State arrival, int rockets, float arrivalYaw, float arrivalPitch) {
		double speed = Math.hypot(arrival.vx, arrival.vz);
		double dirX = speed > 1.0E-4 ? arrival.vx / speed : 0.0;
		double dirZ = speed > 1.0E-4 ? arrival.vz / speed : 0.0;
		double surface = surfaceBelow(arrival.x, arrival.z, arrival.y);
		// The SAME hold altitude the real driver's beginHold writes, captured
		// once (ab-24 audit section 5: three different references existed).
		double holdY = Math.max(surface + HOLD_CLEARANCE, arrival.y - HOLD_MAX_DESCENT);
		if (arrival.y - surface < HOLD_MIN_CLEARANCE)
			holdY = arrival.y;
		holdY = Math.max(holdY, surface + HOLD_CLEARANCE);
		FlightDynamics.State state = arrival;
		// Simulated resources advance like the real ones: the boost countdown
		// comes from the stepped state, an ignition consumes a rocket and
		// starts the cooldown (ab-24 audit section 5).
		int rocketsSim = rockets;
		long cooldownSim = 0;
		float currentYaw = arrivalYaw;
		float currentPitch = arrivalPitch;
		for (int step = 0; step < TERMINAL_ACTION_TICKS; step++) {
			double hSpeed = Math.hypot(state.vx, state.vz);
			double hx;
			double hz;
			if (hSpeed > 1.0E-4) {
				hx = state.x + (state.vx / hSpeed) * 48.0;
				hz = state.z + (state.vz / hSpeed) * 48.0;
			}
			else {
				hx = state.x + dirX * 48.0;
				hz = state.z + dirZ * 48.0;
			}
			double horizontal = Math.max(1.0, Math.hypot(hx - state.x, hz - state.z));
			float aimPitch = (float) (-Math.toDegrees(Math.atan2(holdY - state.y, horizontal)));
			Waypoint reference = new Waypoint(hx, holdY, hz);
			// The real hold runs the bounded candidate search and scores it;
			// the verification steps through the SAME candidate set and the
			// same selection order (ab-24 audit section 5), one simulated step
			// at a time.
			java.util.List<Candidate> options = new java.util.ArrayList<>();
			for (float yawOffset : params.yawOffsets) {
				for (PitchPolicy policy : PitchPolicy.values()) {
					float yaw = limitYaw(currentYaw,
							normalizeBearing(bearingTo(state.x, state.z, reference) + yawOffset), params.yawRateLimitDegrees);
					float pitch = limitPitch(currentPitch, pitchFor(policy, aimPitch), params.pitchRateLimitDegrees);
					boolean fire = false;
					for (int attempt = 0; attempt < 2; attempt++) {
						if (attempt == 1) {
							if (rocketsSim <= 0 || cooldownSim > 0 || state.rocketTicksRemaining > 0)
								break;
							fire = true;
						}
						physicsSteps += 1;
						FlightDynamics.State next = FlightDynamics.step(state,
								new FlightDynamics.Input(yaw, pitch, fire, rocketDurationTicks));
						if (!sweepSegmentClear(state, next))
							continue;
						Candidate option = new Candidate();
						option.yawOffset = yawOffset;
						option.policy = policy;
						option.fire = fire;
						option.firstYaw = yaw;
						option.firstPitch = pitch;
						option.policyId = String.format(java.util.Locale.ROOT, "%+.1f:%s:%s", yawOffset, policy.name(), fire ? "fire" : "glide");
						option.progress = -dist3(reference.x() - next.x, reference.y() - next.y, reference.z() - next.z);
						option.lateral = Math.hypot(next.x - reference.x(), next.z - reference.z());
						option.heightError = Math.abs(next.y - reference.y());
						option.trackingErrorSquared = option.lateral * option.lateral;
						option.controlChange = Math.abs(next.vy - state.vy);
						option.endX = next.x;
						option.endY = next.y;
						option.endZ = next.z;
						option.nextVx = next.vx;
						option.nextVy = next.vy;
						option.nextVz = next.vz;
						option.nextRocketTicks = next.rocketTicksRemaining;
						option.endTicks = step + 1;
						options.add(option);
						break;
					}
				}
			}
			Candidate chosen = selectBest(options);
			if (chosen == null) {
				if (step < TERMINAL_ACTION_MIN_TICKS)
					return step;
				double sink = Math.abs(state.vy);
				double horizontalSpeed = Math.hypot(state.vx, state.vz);
				if (sink <= TERMINAL_LANDING_SINK && state.vy <= 0
						&& horizontalSpeed <= TERMINAL_LANDING_HORIZONTAL
						&& isSupportLanding(sweepFailX, sweepFailY, sweepFailZ))
					return -2;
				return step;
			}
			if (chosen.fire) {
				rocketsSim -= 1;
				cooldownSim = params.fireCooldownTicks;
			}
			// Advance exactly like the real step: the boost countdown comes
			// from the produced state, not from a local decrement.
			state = new FlightDynamics.State(chosen.endX, chosen.endY, chosen.endZ,
					chosen.nextVx, chosen.nextVy, chosen.nextVz, chosen.nextRocketTicks);
			currentYaw = chosen.firstYaw;
			currentPitch = chosen.firstPitch;
			if (cooldownSim > 0)
				cooldownSim -= 1;
			if (state.y < surfaceBelow(state.x, state.z, state.y) + ARRIVAL_SURFACE_CLEARANCE) {
				if (step < TERMINAL_ACTION_MIN_TICKS)
					return step;
				if (Math.abs(state.vy) > TERMINAL_LANDING_SINK || state.vy > 0
						|| Math.hypot(state.vx, state.vz) > TERMINAL_LANDING_HORIZONTAL)
					return step;
				if (!isSupportLanding(state.x, state.y, state.z))
					return step;
				sweepFailX = state.x;
				sweepFailY = state.y;
				sweepFailZ = state.z;
				probeId = "";
				probeShape = "";
				return -2;
			}
		}
		return -1;
	}
	/**
	 * The first non-air top below a point, scanned down from `fromY`.
	 *
	 * Cached per tick: candidates share columns, and each scan costs up to
	 * `HOLD_SCAN_DEPTH` block queries. Unknown cells count as solid so the
	 * floor is never guessed lower than the truth. Water is a surface: the
	 * scan stops at the water top, not at the riverbed.
	 */
	private double surfaceBelow(double x, double z, double fromY) {
		// One scan per column per tick, shared by every candidate; the answer
		// is the highest solid top at or below the query height, exactly what a
		// fresh scan from `fromY - 1` would find. The old cache was keyed by an
		// integer hash of (x, z) alone, so a column scanned from under a
		// ceiling and from open air returned the same surface, and the winner
		// depended on which candidate asked first (ab-23 repair plan, C1/C5).
		Column column = columnAt(x, z, fromY);
		for (double top : column.tops()) {
			if (top <= fromY)
				return top;
		}
		return fromY - HOLD_SCAN_DEPTH;
	}

	/** The solid tops of one column, scanned once from a height-derived band. */
	private Column columnAt(double x, double z, double fromY) {
		// The key is the integer block COLUMN (a real coordinate pair, not a
		// hash of it): every query in the same column shares one scan, and the
		// band check below keeps queries with a higher start exact (ab-24
		// audit section 4: world reads are shared inside one tick).
		ColumnKey key = new ColumnKey(Math.floor(x), Math.floor(z));
		double top = Math.floor(fromY) + 1.0;
		Column column = columnCache.get(key);
		if (column != null && column.topY() >= top) {
			cacheHits += 1;
			return column;
		}
		java.util.List<Double> tops = new java.util.ArrayList<>();
		for (double probe = top - 1; probe > top - HOLD_SCAN_DEPTH; probe -= 1.0) {
			blockQueries += 1;
			String id = blocks.idAt(x, probe, z);
			if (id == null || !isAirLike(id))
				tops.add(probe + 1.0);
		}
		double[] values = new double[tops.size()];
		for (int index = 0; index < values.length; index++)
			values[index] = tops.get(index);
		Column scanned = new Column(top, values);
		columnCache.put(key, scanned);
		return scanned;
	}

	/** Integer column key; the cache is cleared every tick. */
	private record ColumnKey(double x, double z) {}

	/** Integer block-cell key for the sweep's per-tick cache. */
	private record CellKey(int x, int y, int z) {}

	/** One cached cell read; every sweep probe goes through here. */
	private String cellAt(double x, double y, double z) {
		CellKey key = new CellKey((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		String cached = cellCache.get(key);
		if (cached != null || cellCache.containsKey(key)) {
			cacheHits += 1;
			return cached;
		}
		blockQueries += 1;
		String id = blocks.idAt(key.x(), key.y(), key.z());
		cellCache.put(key, id);
		return id;
	}

	/** Solid tops of one column, highest first, with the scanned band top. */
	private record Column(double topY, double[] tops) {}
	/** Exact surface-scan query; see {@link #surfaceBelow}. */
	private record SurfaceKey(double x, double z, double fromY) {}
	/**
	 * Sweeps the inflated pose box along the segment between two tick states.
	 *
	 * The old check sampled only the cell the state occupied after each step:
	 * a horizontal step of ~1 block can cross a corner or a thin wall between
	 * the endpoints, and fixed samples can miss a short corner contact. The
	 * continuous segment is intersected with every voxel expanded by the
	 * inflated body. Solid and unknown cells reject; face-only tangency does
	 * not intersect the open body volume (unknown is never air, CD-0).
	 */
	private boolean sweepSegmentClear(FlightDynamics.State from, FlightDynamics.State to) {
		if (!sweepLinearClear(from, to)) return false;
		// Vanilla 1.21.1 Entity.collideWithShapes moves Y first, then the
		// larger horizontal displacement (X first on a tie). The final
		// diagonal may be clear even when that intermediate box hits a bank.
		// Keep the geometric sweep too: both the requested corridor and the
		// engine's collision-resolution path must preserve body clearance.
		FlightDynamics.State vertical = new FlightDynamics.State(from.x, to.y, from.z, 0, 0, 0, 0);
		if (!sweepLinearClear(from, vertical)) return false;
		boolean zFirst = Math.abs(to.x - from.x) < Math.abs(to.z - from.z);
		FlightDynamics.State horizontal = new FlightDynamics.State(
				zFirst ? from.x : to.x, to.y, zFirst ? to.z : from.z, 0, 0, 0, 0);
		return sweepLinearClear(vertical, horizontal) && sweepLinearClear(horizontal, to);
	}

	private boolean sweepLinearClear(FlightDynamics.State from, FlightDynamics.State to) {
		double half = params.poseHalfWidth + params.inflate;
		int minX = (int)Math.floor(Math.min(from.x, to.x) - half);
		int maxX = (int)Math.ceil(Math.max(from.x, to.x) + half) - 1;
		int minY = (int)Math.floor(Math.min(from.y, to.y) - params.inflate);
		int maxY = (int)Math.ceil(Math.max(from.y, to.y) + params.poseHeight + params.inflate) - 1;
		int minZ = (int)Math.floor(Math.min(from.z, to.z) - half);
		int maxZ = (int)Math.ceil(Math.max(from.z, to.z) + half) - 1;
		double first = Double.POSITIVE_INFINITY;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					// Expand the voxel by the body extents, then intersect the
					// feet segment with that volume. Fixed spatial samples can
					// miss a short diagonal corner contact (extension-02 t1372).
					double entry = sweptVoxelEntry(from, to, x, y, z, half);
					if (entry >= first) continue;
					String id = cellAt(x, y, z);
					if (id != null && isFlyableThrough(id)) continue;
					first = entry;
					probeX = x;
					probeY = y;
					probeZ = z;
					probeId = id == null ? "unknown" : id;
					probeShape = blocks.shapeAt(x, y, z);
				}
			}
		}
		if (!Double.isFinite(first)) return true;
		sweepFailX = from.x + (to.x - from.x) * first;
		sweepFailY = from.y + (to.y - from.y) * first;
		sweepFailZ = from.z + (to.z - from.z) * first;
		return false;
	}

	/** Open slab intervals allow face-only tangency, as the point box check does. */
	private double sweptVoxelEntry(FlightDynamics.State from, FlightDynamics.State to,
			int x, int y, int z, double half) {
		double enter = 0.0, leave = 1.0;
		for (int axis = 0; axis < 3; axis++) {
			double origin = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
			double end = axis == 0 ? to.x : axis == 1 ? to.y : to.z;
			double min = axis == 0 ? x - half : axis == 1 ? y - params.poseHeight - params.inflate : z - half;
			double max = axis == 0 ? x + 1 + half : axis == 1 ? y + 1 + params.inflate : z + 1 + half;
			double delta = end - origin;
			if (Math.abs(delta) < 1.0E-12) {
				if (origin <= min || origin >= max) return Double.POSITIVE_INFINITY;
				continue;
			}
			double a = (min - origin) / delta, b = (max - origin) / delta;
			enter = Math.max(enter, Math.min(a, b));
			leave = Math.min(leave, Math.max(a, b));
			if (enter >= leave) return Double.POSITIVE_INFINITY;
		}
		return enter;
	}

	/** The inflated pose box at one point; any solid or unknown cell rejects. */
	private boolean boxClear(double x, double y, double z) {
		double half = params.poseHalfWidth + params.inflate;
		// A voxel [n,n+1) intersects the inflated body only below ceil(max).
		// Inclusive ceil(top) and floating +0.5 loops probed untouched wool
		// and side walls, making narrow but clear passages appear blocked.
		int bottom = (int) Math.floor(y - params.inflate);
		int top = (int) Math.ceil(y + params.poseHeight + params.inflate) - 1;
		int minX = (int) Math.floor(x - half), maxX = (int) Math.ceil(x + half) - 1;
		int minZ = (int) Math.floor(z - half), maxZ = (int) Math.ceil(z + half) - 1;
		for (int ex = minX; ex <= maxX; ex++) {
			for (int ez = minZ; ez <= maxZ; ez++) {
				for (int ey = bottom; ey <= top; ey++) {
					String id = cellAt(ex, ey, ez);
					if (id == null || !isFlyableThrough(id)) {
						probeX = ex;
						probeY = ey;
						probeZ = ez;
						probeId = id == null ? "unknown" : id;
						probeShape = blocks.shapeAt(ex, ey, ez);
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Cells the glider may pass through in the PREDICTION: air and other
	 * empty-collision cells, but NOT water. Water is a different medium — the
	 * aerodynamic model has no water state — so a predicted path that dips the
	 * body into water cannot continue flying and must be rejected. Treating
	 * water as passable let the pilot choose a dive into the river every tick
	 * (the floor read the riverbed, not the water top) and splash with rockets
	 * still in hand (R4 cave-ab-13 full arm: one ignition, then water). The
	 * glider may still fly ABOVE water: at 63+ the pose box does not sample
	 * the surface cells. Unknown ids (including null) stay obstacles.
	 */
	private static boolean isFlyableThrough(String id) {
		return FlightGeometry.isFlyableThrough(id);
	}

	/**
	 * True when a cell counts as AIR for surface scans: water is a surface, not
	 * air, so a scan below a low glider stops at the water top instead of the
	 * riverbed (R4 client review 2026-09-21, item 1).
	 */
	private static boolean isAirLike(String id) {
		return FlightGeometry.isAirLike(id);
	}

	/**
	 * The route's passed-by-plane rule, shared by the real cursor
	 * ({@link #advanceEntries}) and the prediction's virtual cursor (ab-24
	 * audit section 5: both must advance the same way). The terminal is never
	 * plane-completed; the range guard keeps a U-shaped route from skipping a
	 * station it has not actually reached.
	 */
	private boolean passedPlane(int index, double x, double y, double z) {
		if (index >= path.size() - 1)
			return false;
		return beyondHorizontalPlane(index, x, y, z);
	}

	private boolean beyondHorizontalPlane(int index, double x, double y, double z) {
		Waypoint waypoint = path.get(index);
		// The travel direction at this entry. The first entry has no
		// predecessor in this leg, so it uses the first segment's direction:
		// without it a NEW session's first point could never be seen as passed,
		// and a leg whose start is already behind the glider made it turn
		// around and chase back (R4 ab-11: yaw 174.7 -> 10.5 right after
		// adopting revision 4).
		double dirX;
		double dirZ;
		if (index > 0) {
			Waypoint previous = path.get(index - 1);
			dirX = waypoint.x() - previous.x();
			dirZ = waypoint.z() - previous.z();
		}
		else {
			Waypoint next = path.get(1);
			dirX = next.x() - waypoint.x();
			dirZ = next.z() - waypoint.z();
		}
		// Passage controls the horizontal reference. Height error must not tilt
		// this plane backward: ab-27 tick 3867 had passed z=31.5 at flying
		// speed, but +8.19 blocks of height kept yaw chasing the point behind.
		// Keep the 3D range guard and terminal arrival rule; all motion remains
		// swept, so advancing the reference does not certify a shortcut.
		double length = Math.hypot(dirX, dirZ);
		double range = dist3(waypoint.x() - x, waypoint.y() - y, waypoint.z() - z);
		if (length <= 1.0E-6 || range > PASSED_PLANE_RANGE)
			return false;
		double beyond = ((x - waypoint.x()) * dirX
				+ (z - waypoint.z()) * dirZ) / length;
		return beyond > PASSED_PLANE_MARGIN;
	}

	private void advanceEntries(double x, double y, double z) {
		// entryIndex is the waypoint being steered to. Reaching it advances the
		// cursor; reaching the last waypoint completes the channel. The old
		// code returned path[index + 1], which skipped the first entry before
		// it was ever reached (R2b fix).
		while (entryIndex < path.size()) {
			Waypoint waypoint = path.get(entryIndex);
			if (dist3(waypoint.x() - x, waypoint.y() - y, waypoint.z() - z) <= arrivalReach(entryIndex)) {
				entryIndex += 1;
				continue;
			}
			// Passed-by-plane: a non-terminal entry the glider has already
			// flown past must not become a chase-back target. The sphere test
			// above only advances when the glider comes near the point, so a
			// fast or offset pass leaves the target behind and the driver turns
			// around (R4 ab-09 revision 4: yaw swung 179 -> 16, stalled, and
			// splashed while chasing a passed entry).
			if (passedPlane(entryIndex, x, y, z)) {
				entryIndex += 1;
				continue;
			}
			break;
		}
		if (entryIndex >= path.size()) {
			pathComplete = true;
		}
	}

	private static double dist3(double dx, double dy, double dz) {
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Internal scoring wrapper for one evaluated candidate. */
	private static final class Candidate {
		List<FlightDynamics.Input> controls = List.of();
		/** The complete arrival snapshot for the terminal verification (B4). */
		double arrivalX;
		double arrivalY;
		double arrivalZ;
		double arrivalVx;
		double arrivalVy;
		double arrivalVz;
		int arrivalRocketTicks;
		float arrivalYaw;
		float arrivalPitch;
		/** Usable rockets at the measured state, for the hold's inventory sim. */
		int rocketsAtStart;
		/** The lateral offset applied to the per-step reference bearing. */
		float yawOffset;
		/** The pitch policy of this candidate. */
		PitchPolicy policy;
		/** Stable identity for deterministic tie-breaks (B3). */
		String policyId = "";
		/** The first executed step: prediction and execution use the same one. */
		float firstYaw;
		float firstPitch;
		boolean fire;
		/** Monotonic route progress at the prediction end (B3). */
		double progress;
		/** Horizontal tracking error at the prediction end. */
		double lateral;
		/** Vertical deviation from the local reference at the prediction end. */
		double heightError;
		double trackingErrorSquared;
		/** Attitude change from the measured state to the first step. */
		double controlChange;
		/** The prediction reached the active entry inside the horizon. */
		boolean arrived;
		/** The full next state of the option's single step (hold simulation). */
		double nextVx;
		double nextVy;
		double nextVz;
		int nextRocketTicks;
		/** Telemetry of this candidate's prediction end (audit section 6). */
		int endTicks;
		int endCursor;
		double endX;
		double endY;
		double endZ;
		String endReason = "horizon";
		double[] preview = new double[9];
		String terminalAction = "none";
	}

	/**
	 * Minecraft yaw for the horizontal direction to the waypoint. The polar
	 * angle atan2(dz, dx) is not a yaw: vanilla yaw 0 faces +z and east (+x) is
	 * -90, so the conversion subtracts 90 degrees — the same conversion
	 * {@code BotController.steer} and {@code aimForClimb} use. Without it a due
	 * east target was steered 75 degrees off (R2b fix).
	 */
	private static float bearingTo(double x, double z, Waypoint target) {
		return normalizeBearing(Math.toDegrees(Math.atan2(target.z() - z, target.x() - x)) - 90.0);
	}

	/** Limits a yaw command to the shared steering rate (B2). */
	public static float limitYaw(float previousYaw, float desiredYaw, float limitDegrees) {
		if (limitDegrees <= 0f)
			return normalizeBearing(desiredYaw);
		// Normalize the DIFFERENCE: -95 -> +164 is a -101 degree turn, not
		// +259. Separately normalized endpoints made ab-28 circle backward.
		float delta = normalizeBearing((double) desiredYaw - previousYaw);
		float clamped = Math.max(-limitDegrees, Math.min(limitDegrees, delta));
		return normalizeBearing(normalizeBearing(previousYaw) + clamped);
	}

	/** Limits pitch before simulation; applying the frame must not reshape it. */

	private static float normalizeBearing(double degrees) {
		float value = (float) degrees;
		while (value > 180f)
			value -= 360f;
		while (value < -180f)
			value += 360f;
		return value;
	}

	private static float clampPitch(float pitch) {
		return Math.max(-60f, Math.min(60f, pitch));
	}
}


















