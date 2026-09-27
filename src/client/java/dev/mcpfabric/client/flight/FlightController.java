package dev.mcpfabric.client.flight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ControlOwnership;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Flight session controller (R2a contracts + R2b per-tick driving; execution
 * plan §6–§7). The module owns the channel contracts, the ignition dedup, the
 * bounded trajectory ring, and — since R2b — the ONLY yaw/pitch/firework
 * writes while a submitted channel carries a path and the player is gliding.
 *
 * <p>Contracts (revision doc §7): a channel binds session id, control
 * session, generation and revision; a repeated session id returns the first
 * response (idempotent); an older generation is rejected; "accepted" and
 * "started applying" are separate fields and the apply moment is recorded
 * with its tick. Ignition carries a unique op id; a repeated op id returns
 * the first response without firing again.
 *
 * <p>Driving (R2b): when the channel payload carries a path, the session
 * requests the launch macro while grounded and waits for its handoff fact;
 * once the macro is no longer running and the player is gliding, each tick
 * feeds the measured state to a {@link FlightSession} and applies the
 * winner's first tick through the player rotators — one fire through the game
 * mode, deduplicated. The handover inherits the macro's speed, boost
 * remainder and ignition bookkeeping.
 *
 * <p>Control lifecycle (R2b fix): an accepted channel is a finite autonomous
 * execution window. The bridge heartbeat must not cancel it (the guard skips
 * live channels), while submit/boost/revoke renew the heartbeat and
 * observe/status do not. Termination is one entry point: it cancels the
 * launch macro, drops the pending session, and blocks later input writes.
 *
 * <p>Thread model: every public entry runs on the game thread via
 * {@code ClientMc.call}; the tick entry runs from the client tick event. The
 * session re-selects every tick from the measured state, so MCP latency never
 * extends an applied prefix beyond one tick.
 */
public final class FlightController {
	private FlightController() {}

	private static final FlightController INSTANCE = new FlightController();

	public static FlightController get() {
		return INSTANCE;
	}

	/** Trajectory ring capacity in ticks (30 s at 20 tps). */
	private static final int TRAJECTORY_CAPACITY = 600;
	/** Bounded ignition-dedup memory. */
	private static final int OP_MEMORY = 64;
	/** Bounded session-id memory for idempotent submits. */
	private static final int SESSION_MEMORY = 32;
	public enum SessionState {
		ACCEPTED, RUNNING, REVOKED, TERMINATED
	}

	/** One trajectory sample; written on the game thread, read via batch RPC. */
	private static final class TickRecord {
		int tick;
		String phase = "end-client-tick";
		double x, y, z;
		double vx, vy, vz;
		float yaw, pitch;
		boolean gliding;
		boolean onGround;
		boolean boostAttached;
		int boostRemainingEstimate;
		int rocketFlightDuration;
		int plannedRocketFlightDuration;
		int firedRocketFlightDuration;
		int boostFlightDuration;
		/** Attached firework entity ids this tick; stacking evidence (ab-17). */
		int boostCount;
		String boostEntityIds = "";
		boolean rocketFiredThisTick;
		String inputOwner = "none";
		/** Health at this tick: the ring proves where damage happened (R4). */
		float health;
		/** Which session drove this tick (R4 review item 1). */
		String sessionId = "";
		long revision;
		boolean holding;
		/**
		 * Medium state: the aerodynamic model has no water mode, so a water
		 * tick must be visible without trusting `gliding` (R4 review 3.2).
		 */
		boolean inWater;
		/** Usable rockets: hands vs inventory, to attribute a missing ignition. */
		int rocketsInHands;
		int rocketsInInventory;
		// --- Per-tick decision telemetry (ab-17 audit section 6) ------------
		/** The waypoint this tick steered to (the hold target while holding). */
		double targetX, targetY, targetZ;
		/** Global route cursor at decision time. */
		int cursor;
		/** What the winning candidate's prediction did at its end. */
		int predictedEndTicks;
		int predictedEndCursor;
		double predictedEndX, predictedEndY, predictedEndZ;
		String predictedEndReason = "";
		/** Predicted positions after 1/3/5 ticks, "x,y,z;x,y,z;x,y,z". */
		String preview = "";
		/** {@code hold}/{@code land}/{@code none}. */
		String terminalAction = "none";
		/** First rejection of the driven tick, with its predicted context. */
		String rejectKind = "";
		int rejectTick = -1;
		double rejectX, rejectY, rejectZ;
		String rejectBlock = "";
		String rejectShape = "";
		int rejectCollisions;
		int rejectFloor;
		int rejectSpeed;
		int rejectTerminal;
		/** Offered-but-unadopted handover id (active/pending telemetry). */
		String pendingSessionId = "";
		/** Wall clock and monotonic clock at the sample (video alignment). */
		long wallMs;
		long nanoMs;
		// --- D1 evidence schema (ab-23 repair plan, batch D) -----------------
		/** Physical control phase and route outcome at the sample. */
		String controlPhase = "";
		String routeOutcome = "";
		/** Global arc-length progress at the sample and at the prediction end. */
		double progress;
		double predictedEndProgress;
		/** The chosen candidate's stable policy id. */
		String chosenPolicy = "";
		String planningState = "none";
		/** Candidate accounting and simulation cost of the driven tick. */
		int evaluated;
		int feasible;
		boolean budgetExhausted;
		boolean searchCompleted;
		long physicsSteps;
		long blockQueries;
		long cacheHits;
		long simMs;
		long preparationUs, orderingUs, candidatesUs, selectionUs, reservationUs;
		/** Fire cooldown active at the driven tick. */
		boolean cooldownActive;
		/** Why the phase changed: failure or release reason (typed). */
		String transitionReason = "";
		/** Safe-search telemetry of the recovery/undecided takeover (ab-29 review). */
		int safeCandidates;
		int safeVerified;
		boolean safeEmergency;
		int safeContactTicks = -1;
	}

	// --- Channel session state -------------------------------------------------
	private String sessionId;
	private String controlSessionId;
	private long generation;
	private long revision;
	private long deadlineMs;
	private String sessionDimension = "";
	/** Highest accepted generation; survives session termination. */
	private long lastGeneration;
	private SessionState state;
	private String endReason = "";
	/** Decision note at the moment the session ended; telemetry for R4. */
	private String lastEndDetail = "";
	private long acceptedAtMs;
	private long applyingSinceTick = -1;
	private boolean applyingStarted;
	/** R2b: the live driver while a channel with a path is being applied. */
	private FlightSession session;
	/**
	 * The next leg accepted while the current one still applies (R4 review
	 * item 3). The current route keeps flying; the pending one is adopted at a
	 * game tick boundary once its entry is reachable.
	 */
	/**
	 * The pending-route handover state machine (R4 client review groups 1-3):
	 * offers, staleness/ordering rules, the adoption decision and the fire
	 * cooldown transfer all live in {@link FlightHandover}; the controller
	 * only maps the outcome onto its fields.
	 */
	private final FlightHandover handover = new FlightHandover();
	/** How long the client holds after its path before it gives up on a handover. */
	private static final long HANDOVER_GRACE_MS = 5_000;
	/** A pending route's first waypoint must be this close to be adoptable. */
	private static final double HANDOVER_ENTRY_LIMIT = FlightHandover.ENTRY_LIMIT;
	private int sessionStartTick;
	private int rocketsAtHandover;
	/** Health when the driver started applying; a drop ends the channel. */
	private float healthAtApplyStart;
	private boolean launchRequested;
	/** Who wrote input this tick; the ring records it for the live script. */
	private String tickInputOwner = "none";
	/** The decision that drove this tick, for the ring's per-tick telemetry. */
	private FlightSession.Decision tickDecision;
	/** True when a rocket left either hand this tick, from any owner. */
	private boolean tickFiredThisTick;
	private int tickFiredDuration = -1;
	/** Launch-macro ignition counter seen at the previous tick (delta → record). */
	private int launchFireworksUsedSeen;
	/** Times the drive moved a firework from the inventory into a hand. */
	private int rocketResupplies;

	/** Idempotency memory: session id → the first submit response. */
	private final LinkedHashMap<String, JsonObject> handledSessions = new LinkedHashMap<>();
	/** Ignition dedup memory: op id → the first ignite response. */
	private final LinkedHashMap<String, JsonObject> usedOps = new LinkedHashMap<>();

	// --- Trajectory ring --------------------------------------------------------
	private final TickRecord[] ring = new TickRecord[TRAJECTORY_CAPACITY];
	private int ringHead;
	private int ringCount;
	private long lostRecords;
	private long lastReadTick;
	private int moduleTick;

	// --- Ignition bookkeeping ---------------------------------------------------
	private long lastIgniteAtMs;

	// --- Boost bookkeeping --------------------------------------------------------
	// The attached firework's remaining life is not readable client-side, so
	// the module tracks each entity's first-seen tick and derives the estimate
	// from it. The ids and count are telemetry: the ab-17 audit found speeds
	// consistent with two overlapping rockets that a single countdown could
	// not express, so the driver forbids stacking and the ring records the
	// entities (audit item 4).
	/** Per-entity first-seen ticks for the attached fireworks. */
	private final java.util.Map<Integer, Long> boostFirstSeen = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> boostDurations = new java.util.HashMap<>();
	/** The attached firework ids of the current tick (set by the tick loop). */
	private java.util.List<Integer> tickBoostIds = java.util.List.of();
	/**
	 * Route outcome and physical control phase (ab-23 repair plan, batch A2).
	 * One tick produces exactly one {@link InputFrame} from exactly one phase;
	 * the route outcome stays frozen after the first failure.
	 */
	private final FlightOwnership ownership = new FlightOwnership();
	/** How long the recovery phase keeps ownership before the policy deadline. */
	private static final long RECOVERY_MS = 6_000;
	/** How far down the profile scans reach for a surface, blocks. */
	private static final double RECOVERY_SCAN_DEPTH = 40.0;
	/** How far ahead the recovery looks for a landing patch, blocks (C2). */
	private static final double RECOVERY_LANDING_RANGE = 32.0;
	/** Consecutive limited-speed samples that confirm a stable end (C4). */
	private static final int RECOVERY_SETTLE_TICKS = 20;
	// --- Recovery telemetry (C3) ---------------------------------------------
	/** Consecutive limited-speed samples on the ground or in water. */
	private int recoveryStableTicks;
	/** How many times the 6 s policy window was re-armed inside the hard one. */
	private int recoveryReselects;
	/** True while the glider lost its glide without a ground/water contact. */
	private boolean recoveryUnresolved;
	/** Session parameters shared by the route, landing and probe sessions. */
	private FlightSession.Params params = new FlightSession.Params();
	/** Recovery probe session: the same physics/sweep the route candidates use. */
	private FlightSession recoveryProbe;
	/** Whether the last recovery frame passed the full 3D verification. */
	private boolean recoveryFrameVerified;
	/** How far ahead the recovery frame is verified, ticks. */
	private static final int RECOVERY_VERIFY_TICKS = 6;
	/**
	 * How far the recovery's safe search predicts (ab-29 review: 6 ticks was
	 * 0.3 s and missed the turn/flare time; the venue needs ~10+).
	 */
	private static final int RECOVERY_SEARCH_TICKS = 14;
	/** Safe-search telemetry of the last recovery/undecided tick. */
	private int safeCandidates;
	private int safeVerified;
	private boolean safeEmergency;
	private int safeContactTicks = -1;
	/** The verified landing plan currently being flown (C2). */
	private FlightSession landingSession;
	private int landingStartTick;
	private double landingContactY;
	private double landingSiteX;
	private double landingSiteY;
	private double landingSiteZ;
	private boolean landingHasPlan;
	/** The bounded landing patch the recovery steers to, if any (C2). */
	private double recoveryLandingX;
	private double recoveryLandingY;
	private double recoveryLandingZ;
	private boolean recoveryHasLandingTarget;

	/** The single control input one tick may submit, from one owner. */
	private static final class InputFrame {
		final String owner;
		/** {@code null} = leave the rotation untouched (macro owns it). */
		final Float yaw;
		final Float pitch;
		final boolean fire;
		final int rocketFlightDuration;

		InputFrame(String owner, Float yaw, Float pitch, boolean fire) {
			this(owner, yaw, pitch, fire, -1);
		}

		InputFrame(String owner, Float yaw, Float pitch, boolean fire, int rocketFlightDuration) {
			this.owner = owner;
			this.yaw = yaw;
			this.pitch = pitch;
			this.fire = fire;
			this.rocketFlightDuration = rocketFlightDuration;
		}
	}

	// --- Tick entry --------------------------------------------------------------
	/** Runs from the client tick event after BotController's own task ticks. */
	public void onClientTick(Minecraft mc, LocalPlayer p) {
		synchronized (this) {
			moduleTick++;
			tickInputOwner = "none";
			tickFiredThisTick = false;
			tickFiredDuration = -1;
			tickDecision = null;
			// These describe this tick's fallback search, not the last search
			// of a previous phase or flight. Stale emergency flags polluted
			// local-05/06 TRACK samples after a prior recovery had ended.
			safeCandidates = 0;
			safeVerified = 0;
			safeEmergency = false;
			safeContactTicks = -1;
			recoveryFrameVerified = false;
			trackLaunchIgnition();
			if (p == null) {
				terminateIfActive("disconnect");
				return;
			}
			if (state == SessionState.RUNNING || state == SessionState.ACCEPTED) {
				if (p.isDeadOrDying()) {
					terminateIfActive("death");
				}
				else {
					String dimension = dimensionOf(p);
					if (!sessionDimension.isEmpty() && !sessionDimension.equals(dimension)) {
						terminateIfActive("dimension_changed");
					}
				}
			}
			// Damage while the channel applies ends the flight at once: a hit
			// (terrain or otherwise) means the verified-space premise is gone,
			// and the host's own health guard is seconds slower (R4: run 09
			// reached the guard only after the damage had landed).
			if ((state == SessionState.RUNNING || state == SessionState.ACCEPTED) && applyingStarted
					&& p.getHealth() < healthAtApplyStart - 0.01F) {
				terminateIfActive("damage");
			}
			// Entering water ends the flight at once: the aerodynamic model has
			// no water state, and the drive would keep predicting a glide the
			// measured state cannot follow. R4 cave-diag-16: gliding stayed
			// true while the glider sank, and the model diverged from the
			// measurement from the first tick in water.
			if ((state == SessionState.RUNNING || state == SessionState.ACCEPTED) && applyingStarted
					&& p.isInWater()) {
				terminateIfActive("water");
			}
			// A channel is a finite autonomous execution window (R2b fix): the
			// deadline applies in every phase, including the grounded prepare
			// wait, not only while applying.
			if ((state == SessionState.RUNNING || state == SessionState.ACCEPTED)
					&& System.currentTimeMillis() > deadlineMs) {
				// The hard deadline is authoritative (C3). Ending it while the
				// glider is still airborne is an explicit failure, not a safe
				// ending: the typed reason survives into the receipt.
				boolean airborne = !p.onGround() && !p.isInWater();
				terminateIfActive(airborne ? "control_expired_airborne" : "deadline");
			}
			// Track the attached firework entities (ids + first-seen ticks) for
			// the boost estimate, then drive and record, so the ring samples
			// see the post-input state.
			tickBoostIds = refreshBoostEntities(mc, p);

			InputFrame frame = null;
			// R2b: drive the session when a channel with a path is live. The
			// driver owns the yaw/pitch/firework writes while it applies; the
			// grounded handover delegates the takeoff to the launch macro, and
			// the driver waits for that macro's handoff fact (R2b fix).
			if ((state == SessionState.RUNNING || state == SessionState.ACCEPTED) && session != null) {
				adoptPendingIfReady(p);
				if (session.isDone()) {
					FlightSession.EndReason reason = session.endReason();
					endSessionFromReason();
					// An internal failure in the air must not leave the last
					// dive attitude attached to a released glider: the client
					// enters a bounded recovery until ground, water or the
					// window ends (ab-17 audit item 3: both arms hit the terrain
					// after the control release, HP 8.5/12).
					if (reason == FlightSession.EndReason.NO_VIABLE_TRAJECTORY
							&& !p.onGround() && !p.isInWater() && p.isFallFlying()) {
						startRecovery(reason.name().toLowerCase(java.util.Locale.ROOT));
					}
				}
				else {
					frame = driveSession(mc, p);
				}
			}
			// One frame per tick: the session phase first, the recovery phase
			// only when the session produced none (A2: no sequential double
			// write, one owner, one recorded action).
			if (frame == null && ownership.phase() == FlightOwnership.Phase.LAND)
				frame = driveLanding(p);
			if (frame == null && ownership.recovering())
				frame = recoveryFrame(p);
			if (frame != null)
				applyFrame(mc, p, frame);
			recordTick(mc, p);
		}
	}

	/** Records the launch macro's ignition in this tick's ring sample. */
	private void trackLaunchIgnition() {
		int used = BotController.get().launchFireworksUsed();
		if (used > launchFireworksUsedSeen)
			tickFiredThisTick = true;
		launchFireworksUsedSeen = used;
	}

	/**
	 * Starts the bounded recovery that follows an in-air internal failure. It
	 * owns only the attitude and (since ab-20) one optional arresting ignition
	 * from {@link FlightRecovery}; the window ends at ground, water or
	 * {@link #RECOVERY_MS}. The route outcome is frozen as failed here and a
	 * later settled landing never rewrites it (A2).
	 */
	private void startRecovery(String reason) {
		ownership.routeFailed(reason, System.currentTimeMillis(), RECOVERY_MS, deadlineMs);
	}

	/**
	 * One tick of the bounded recovery descent as a frame. The heading is kept;
	 * the attitude comes from {@link FlightRecovery} and includes a terrain
	 * ahead profile scan and a single arresting ignition, so the release does
	 * not fly the last dive into the bank (ab-20 full arm: 9.97 damage after
	 * the release). It releases at ground, water, glide stop or the window end.
	 */
	private InputFrame recoveryFrame(LocalPlayer p) {
		long nowMs = System.currentTimeMillis();
		if (p.onGround() || p.isInWater()) {
			// A stable end needs a run of fresh samples with limited speeds,
			// not one grounded tick (C4). The route outcome stays failed.
			if (recoveryStableTick(p))
				recoveryStableTicks += 1;
			else
				recoveryStableTicks = 0;
			if (recoveryStableTicks >= RECOVERY_SETTLE_TICKS) {
				ownership.settle();
				return null;
			}
			// Not yet stable: keep the attitude owner so the landing stays
			// controlled instead of releasing on the first contact.
			return new InputFrame("flight-recovery", p.getYRot(), 0f, false);
		}
		recoveryStableTicks = 0;
		if (!p.isFallFlying()) {
			// Losing the glide is not a landing: onGround/inWater are false,
			// so the state is unresolved. Keep the claim while the clocks
			// allow it and report it instead of pretending a safe end (C3).
			recoveryUnresolved = true;
			if (nowMs > ownership.hardDeadlineMs()) {
				ownership.release("control_expired_airborne");
				return null;
			}
			return null;
		}
		recoveryUnresolved = false;
		if (nowMs > ownership.recoveryUntilMs()) {
			// The 6 s window is a POLICY deadline, not a release proof: while
			// the hard control deadline still holds, re-select and continue
			// (C3). Never just drop the owner.
			if (nowMs <= ownership.hardDeadlineMs()) {
				ownership.rearmRecovery(nowMs, RECOVERY_MS);
				recoveryReselects += 1;
			}
			else {
				ownership.release("control_expired_airborne");
				return null;
			}
		}
		float yaw = p.getYRot();
		FlightSession.BlockQuery query = flightBlockQuery(Minecraft.getInstance());
		// A bounded landing goal: the nearest support patch inside the lookahead
		// cone, used as the heading target (C2). No site is no plan — the
		// short-term avoidance still runs, and the telemetry says so.
		double[] landingTarget = findLandingTarget(query, p);
		float frameYaw = yaw;
		if (landingTarget != null) {
			frameYaw = normalizeBearing(
					(float) Math.toDegrees(Math.atan2(landingTarget[2] - p.getZ(), landingTarget[0] - p.getX())) - 90f);
			recoveryLandingX = landingTarget[0];
			recoveryLandingY = landingTarget[1];
			recoveryLandingZ = landingTarget[2];
			recoveryHasLandingTarget = true;
		}
		frameYaw = FlightSession.limitYaw(yaw, frameYaw, params.yawRateLimitDegrees);
		// The clearance is computed for the FINAL heading, not the old one:
		// scanning one direction and then steering another checked the wrong
		// trajectory (ab-24 audit section 2.2).
		double surface = FlightGeometry.surfaceBelow(query, p.getX(), p.getZ(), p.getY(), RECOVERY_SCAN_DEPTH);
		double heightAbove = p.getY() - surface;
		double clearanceAhead = clearanceAhead(query, p, frameYaw, surface);
		FlightRecovery.Inputs in = new FlightRecovery.Inputs();
		in.heightAbove = heightAbove;
		in.clearanceAhead = clearanceAhead;
		in.vy = p.getDeltaMovement().y;
		in.rocketAvailable = fireworkHand(p) != null;
		in.boostActive = !tickBoostIds.isEmpty();
		FlightRecovery.Action action = FlightRecovery.decide(in);
		float framePitch = FlightSession.limitPitch(p.getXRot(), action.pitch, params.pitchRateLimitDegrees);
		// The final input goes through the SAME physics and 3D body sweep the
		// route candidates use; the scans above are pre-filters only (ab-24
		// audit section 2). Fallbacks keep a verified action whenever one
		// exists; when none verifies the attitude is left unchanged and the
		// telemetry says so.
		if (recoveryProbe == null) {
			recoveryProbe = new FlightSession(
					java.util.List.of(
							new FlightSession.Waypoint(p.getX(), p.getY(), p.getZ()),
							new FlightSession.Waypoint(p.getX() + 8.0, p.getY(), p.getZ())),
					query, ownership.hardDeadlineMs(), params, 0L);
		}
		// The bounded symmetric safe search (ab-29 review): zero offset first,
		// then left/right turns, rate-limited, with the predicted END state
		// checked for room. Nothing verified -> the emergency action is applied
		// but reported as unverified, never as a pass.
		int boost = boostRemainingEstimateFor(tickBoostIds);
		recoveryProbe.setRocketFlightDuration(selectedRocketDuration(p));
		FlightSession.SafeFrame safe = recoveryProbe.recoveryFrame(p.getX(), p.getY(), p.getZ(),
				p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
				boost, countRocketsInHands(p), p.getYRot(), p.getXRot(), frameYaw, framePitch, RECOVERY_SEARCH_TICKS);
		recoveryFrameVerified = safe.verified > 0;
		safeCandidates = safe.candidates;
		safeVerified = safe.verified;
		safeEmergency = safe.emergency;
		safeContactTicks = safe.contactTicks;
		if (safe.verified > 0 || !p.onGround())
			return new InputFrame("flight-recovery", safe.yaw, safe.pitch, safe.fire);
		return null;
	}

	/**
	 * A host-supplied landing plan (ab-23 repair plan, C2). Only a recovering
	 * client accepts one; the site is verified from the live state with the
	 * same bounded prediction the route driver uses, and a verified plan moves
	 * the recovery into its LAND phase. A refusal names the gap so the host
	 * can re-plan instead of faking a landing.
	 */
	public synchronized JsonObject submitLandingSite(String sessionId, String controlSessionId,
			double x, double y, double z, double contactY, String dimension, long deadlineMs) {
		JsonObject o = new JsonObject();
		o.addProperty("sessionId", sessionId);
		o.addProperty("accepted", false);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			o.addProperty("reason", "no_player");
			return o;
		}
		if (!ownership.recovering()) {
			o.addProperty("reason", "not_recovering");
			o.addProperty("phase", ownership.phase().name());
			return o;
		}
		if (System.currentTimeMillis() > ownership.hardDeadlineMs()) {
			o.addProperty("reason", "control_expired");
			return o;
		}
		if (!sessionDimension.isEmpty() && dimension != null && !dimension.isEmpty()
				&& !sessionDimension.equals(dimension)) {
			o.addProperty("reason", "dimension_mismatch");
			return o;
		}
		int boostRemaining = boostRemainingEstimateFor(tickBoostIds);
		FlightLandingPlan.Result result = FlightLandingPlan.validate(
				flightBlockQuery(Minecraft.getInstance()), ownership.hardDeadlineMs(), params,
				p.getX(), p.getY(), p.getZ(),
				p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
				p.getYRot(), p.getXRot(), boostRemaining, countRocketsInHands(p),
				new FlightLandingPlan.Site(x, y, z, contactY));
		if (!result.accepted()) {
			o.addProperty("reason", result.reason());
			return o;
		}
		if (!ownership.enterLanding()) {
			o.addProperty("reason", "not_recovering");
			return o;
		}
		// The verified plan becomes a real two-point channel leg: the same
		// candidate set, sweeps and terminal rules drive the approach, so the
		// execution cannot drift from the prediction (C2).
		landingSession = new FlightSession(
				java.util.List.of(
						new FlightSession.Waypoint(p.getX(), p.getY(), p.getZ()),
						new FlightSession.Waypoint(x, y, z)),
				flightBlockQuery(Minecraft.getInstance()),
				ownership.hardDeadlineMs(),
				params,
				RECOVERY_MS);
		landingSession.setRouteIdentity(sessionId, 0);
		landingStartTick = moduleTick;
		landingContactY = contactY;
		landingSiteX = x;
		landingSiteY = y;
		landingSiteZ = z;
		landingHasPlan = true;
		recoveryStableTicks = 0;
		o.addProperty("accepted", true);
		o.addProperty("phase", ownership.phase().name());
		o.addProperty("siteX", x);
		o.addProperty("siteY", y);
		o.addProperty("siteZ", z);
		o.addProperty("contactY", contactY);
		return o;
	}

	/**
	 * One tick of the verified landing approach (LAND phase): the same session
	 * machinery as the route driver, with the release criteria of C4 and the
	 * hard deadline of C3.
	 */
	private InputFrame driveLanding(LocalPlayer p) {
		long nowMs = System.currentTimeMillis();
		if (p.onGround() || p.isInWater()) {
			if (recoveryStableTick(p))
				recoveryStableTicks += 1;
			else
				recoveryStableTicks = 0;
			if (recoveryStableTicks >= RECOVERY_SETTLE_TICKS) {
				ownership.settle();
				landingSession = null;
				return null;
			}
			return new InputFrame("flight-landing", p.getYRot(), 0f, false);
		}
		recoveryStableTicks = 0;
		if (!p.isFallFlying()) {
			recoveryUnresolved = true;
			if (nowMs > ownership.hardDeadlineMs()) {
				ownership.release("control_expired_airborne");
				landingSession = null;
			}
			return null;
		}
		recoveryUnresolved = false;
		if (landingSession == null || nowMs > ownership.hardDeadlineMs()) {
			ownership.release("landing_expired");
			landingSession = null;
			return null;
		}
		int sessionTick = moduleTick - landingStartTick;
		landingSession.setRocketFlightDuration(selectedRocketDuration(p));
		FlightSession.Decision decision = landingSession.tick(
				p.getX(), p.getY(), p.getZ(),
				p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
				p.getYRot(), p.getXRot(),
				countRocketsInHands(p), boostRemainingEstimateFor(tickBoostIds), sessionTick,
				landingSession.fireCooldownActive(sessionTick));
		tickDecision = decision;
		if (landingSession.isDone()) {
			lastEndDetail = decision.note;
			boolean airborne = !p.onGround() && !p.isInWater();
			if (airborne && ownership.returnToRecovery(nowMs, RECOVERY_MS)) {
				// A failed landing leg while still airborne keeps ownership and
				// re-selects; releasing here would drop an airborne aircraft
				// (ab-24 audit section 3).
				landingSession = null;
				landingHasPlan = false;
				return null;
			}
			// The landing leg ended: the route outcome stays failed, the
			// physical release carries the landing result (C2/C3).
			ownership.release("landing_" + landingSession.endReason().name().toLowerCase(java.util.Locale.ROOT));
			landingSession = null;
			landingHasPlan = false;
			return null;
		}
		if (!decision.applicable)
			return null;
		return new InputFrame("flight-landing", decision.yaw, decision.pitch, decision.fireRocket);
	}

	/** The stable-end sample rule (C4): limited speeds on ground or water. */
	private static boolean recoveryStableTick(LocalPlayer p) {
		double horizontal = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
		double vertical = Math.abs(p.getDeltaMovement().y);
		return p.onGround() && !p.isFallFlying() && !p.isInWater() && horizontal <= 0.1 && vertical <= 0.1;
	}

	/** Vanilla yaw normalization for a computed heading. */
	private static float normalizeBearing(float degrees) {
		float value = degrees;
		while (value > 180f)
			value -= 360f;
		while (value < -180f)
			value += 360f;
		return value;
	}

	/**
	 * The nearest support patch inside a bounded forward cone (C2): a column
	 * whose surface is a safe support with standing headroom. Returns
	 * {@code {x, y, z}} or {@code null}; this is a client-side mirror of the
	 * host's landing-site rules for the recovery's short-term aim.
	 */
	private double[] findLandingTarget(FlightSession.BlockQuery query, LocalPlayer p) {
		float heading = p.getYRot();
		double best = Double.MAX_VALUE;
		double[] target = null;
		for (float offset : new float[] { 0f, -15f, 15f, -30f, 30f }) {
			double rad = Math.toRadians(heading + offset);
			double dirX = -Math.sin(rad);
			double dirZ = Math.cos(rad);
			for (double distance = 8.0; distance <= RECOVERY_LANDING_RANGE; distance += 8.0) {
				double x = p.getX() + dirX * distance;
				double z = p.getZ() + dirZ * distance;
				double surface = FlightGeometry.surfaceBelow(query, x, z, p.getY(), RECOVERY_SCAN_DEPTH);
				if (p.getY() - surface > 24.0)
					continue; // too far below to be reachable
				String cell = query.idAt(x, surface - 1.0, z);
				if (FlightGeometry.isUnsafeSupport(cell))
					continue;
				if (!surfaceHeadroomClear(query, x, surface, z))
					continue;
				if (distance < best) {
					best = distance;
					target = new double[] { x, surface, z };
				}
			}
		}
		return target;
	}

	/** Standing headroom above a candidate support surface. */
	private static boolean surfaceHeadroomClear(FlightSession.BlockQuery query, double x, double surfaceY, double z) {
		for (double y = surfaceY; y < surfaceY + 3.0; y += 1.0) {
			String id = query.idAt(x, y, z);
			if (!FlightGeometry.isFlyableThrough(id))
				return false;
		}
		return true;
	}

	/**
	 * The surface top below a point, scanning {@link #RECOVERY_SCAN_DEPTH}
	 * down. Water counts as a surface (the glide ends there); unknown cells
	 * count as solid so the profile never reads lower than the truth.
	 */
	private static double surfaceBelow(FlightSession.BlockQuery query, double x, double z, double fromY) {
		// One geometry boundary (ab-24 audit section 2.1): the old copy skipped
		// water and diverged from the driver's surface semantics.
		return FlightGeometry.surfaceBelow(query, x, z, fromY, RECOVERY_SCAN_DEPTH);
	}

	/**
	 * The smallest clearance between the player's altitude and the highest
	 * surface in the heading's lookahead profile. A column whose surface rises
	 * above the player yields a negative clearance, which the policy reads as
	 * "terrain above the flight path".
	 */
	private static double clearanceAhead(FlightSession.BlockQuery query, LocalPlayer p, float yaw, double ownSurface) {
		double rad = Math.toRadians(yaw);
		double dirX = -Math.sin(rad);
		double dirZ = Math.cos(rad);
		double worst = p.getY() - ownSurface;
		// 4-block steps: the lookahead only has to find the rise early enough
		// for the climb/arrest, and each step costs a bounded column scan.
		for (double distance = 4.0; distance <= FlightRecovery.LOOKAHEAD; distance += 4.0) {
			double x = p.getX() + dirX * distance;
			double z = p.getZ() + dirZ * distance;
			double surface = surfaceBelow(query, x, z, p.getY());
			worst = Math.min(worst, p.getY() - surface);
		}
		return worst;
	}

	/** The measured pilot facts the handover rules read (pure, testable). */
	private static FlightHandover.PilotState pilotStateOf(LocalPlayer p) {
		return new FlightHandover.PilotState(
				p.getX(), p.getY(), p.getZ(),
				p.getDeltaMovement().x, p.getDeltaMovement().z,
				p.isFallFlying(), p.isInWater());
	}

	/**
	 * Adopts a pending handover at a game tick boundary. The decision rules
	 * live in {@link FlightHandover} so the same-state replay and the timing
	 * perturbations can drive them; this method only maps the outcome onto the
	 * controller fields. A stale route is dropped instead of being flown, and
	 * an unconnectable pending leg at path completion is dropped too: the hold
	 * or the ending owns the flight from there (R4 review items 3-4, groups 2-3).
	 */
	private void adoptPendingIfReady(LocalPlayer p) {
		if (handover.pending() == null)
			return;
		boolean currentDone = session == null || session.isPathComplete() || session.isDone();
		FlightHandover.Action action = handover.decide(
				pilotStateOf(p), session, System.currentTimeMillis(), currentDone);
		if (action != FlightHandover.Action.ADOPT)
			return;
		FlightSession previousSession = session;
		if (previousSession != null) previousSession.closePlanning();
		FlightSession next = handover.pending();
		FlightHandover.adopt(previousSession, next, moduleTick - sessionStartTick);
		session = next;
		sessionId = handover.pendingId();
		revision = handover.pendingRevision();
		sessionStartTick = moduleTick;
		applyingStarted = true;
		handover.completeAdoption();
	}

	/** Ends the session with its typed end reason. */
	private void endSessionFromReason() {
		FlightSession.EndReason reason = session == null ? null : session.endReason();
		if (reason == FlightSession.EndReason.CHANNEL_COMPLETE) {
			// The route itself was flown: the outcome is recorded separately
			// from the physical release (A2).
			ownership.routeCompleted();
			terminateIfActive("channel_complete");
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null && !player.onGround() && !player.isInWater() && player.isFallFlying())
				startRecovery("channel_complete");
		}
		else if (reason == null)
			terminateIfActive("ended");
		else
			terminateIfActive(reason.name().toLowerCase());
	}

	/**
	 * One driving tick (R2b). While grounded, the session requests the launch
	 * macro and waits for its handoff; once the macro is no longer running and
	 * the player is gliding, the measured state feeds the session and the
	 * winner's first tick is applied through the player rotators.
	 */
	private InputFrame driveSession(Minecraft mc, LocalPlayer p) {
		// The launch macro owns the view and the rocket until it reports the
		// handoff fact. Taking over as soon as `isFallFlying()` flips true
		// would let the driver overwrite the macro's climb attitude while the
		// boost is still running (R2b fix).
		if (BotController.get().isLaunchActive())
			return new InputFrame("launch-macro", null, null, false);
		boolean gliding = p.isFallFlying();
		// Native contact can precede the server clearing fall-flying by one
		// tick. Do not run an air-only tail again from feet clipped to the floor.
		// This reports contact only; stable landing is a separate observation.
		if (applyingStarted && p.onGround()) {
			terminateIfActive("touchdown");
			return null;
		}
		if (!gliding) {
			if (applyingStarted) {
				// This session already flew, so a landing ends it. Falling
				// through would equip and launch again, silently restarting a
				// finished channel (R3 live 2026-09-20: a mid-air resubmission
				// landed and the driver relaunched instead of reporting the
				// touchdown).
				terminateIfActive("touchdown");
				return null;
			}
			if (launchRequested) {
				// The macro this session owns ended without a gliding handoff,
				// or the flight landed mid-channel. Report the typed reason
				// instead of restarting the macro every tick.
				String reason = BotController.get().launchEndReason();
				if ("launched".equals(reason)) {
					// The macro handed over but the glider is not flying any
					// more: the channel ends at the touchdown.
					terminateIfActive("touchdown");
				}
				else {
					terminateIfActive("launch_" + (reason == null || reason.isEmpty() ? "ended" : reason));
				}
				return null;
			}
			// Equip the suit, then the launch macro owns the takeoff. Its
			// completion handover inherits the measured velocity — the host's
			// first observation is NOT the ignition moment.
			if (!BotController.get().equipElytraChest(mc, p))
				return null; // no elytra in the inventory: retry next tick, the ring records the wait
			// Launch toward the first unreached waypoint: the player's own
			// position carries no direction, and the macro aims the boost
			// along the goal (R2b fix).
			FlightSession.Waypoint goal = session.launchTarget(p.getX(), p.getZ());
			if (goal == null) {
				terminateIfActive("launch_direction_unavailable");
				return null;
			}
			long macroDeadline = Math.min(deadlineMs, System.currentTimeMillis() + 10_000);
			BotController.get().startLaunch(goal.x(), goal.y(), goal.z(), macroDeadline,
					hasFireworkRocket(p));
			launchRequested = true;
			return new InputFrame("launch-macro", null, null, false);
		}
		if (!applyingStarted) {
			applyingStarted = true;
			applyingSinceTick = moduleTick;
			state = SessionState.RUNNING;
			rocketsAtHandover = countRocketsInHands(p);
			healthAtApplyStart = p.getHealth();
			ownership.markTrack();
			// Inherit the launch macro's boost bookkeeping (R2b fix): a rocket
			// fired a few ticks ago still pushes, and the driver's fire
			// cooldown must not restart at zero at the handover. The session
			// owns the cooldown so a later handover can inherit it too.
			int firedAgo = tickBoostIds.stream().map(boostFirstSeen::get).filter(java.util.Objects::nonNull)
					.mapToInt(first -> (int)(moduleTick - first)).min().orElse(0);
			session.inheritFireCooldown(Math.max(0, session.fireCooldownTicks() - firedAgo));
		}
		if (session.isHolding())
			ownership.markHold();

		int sessionTick = moduleTick - sessionStartTick;
		boolean cooldownActive = session.fireCooldownActive(sessionTick);
		int boostRemaining = boostRemainingEstimateFor(tickBoostIds);
		// Keep usable rockets in a hand: the launch consumed the single offhand
		// rocket, and without this the drive has no rockets even with a full
		// inventory (R4 review item 3.3; cave-diag-16 ignited zero times while
		// 623 rockets sat in the inventory).
		int rocketsInHands = countRocketsInHands(p);
		if (rocketsInHands == 0 && BotController.get().equipRocketOffhand(mc, p)) {
			rocketsInHands = countRocketsInHands(p);
			rocketResupplies++;
		}
		session.setRocketFlightDuration(selectedRocketDuration(p));
		int[] availableRecipes = java.util.stream.IntStream.rangeClosed(0, 3)
				.filter(duration -> rocketSlot(p, duration) != -2).toArray();
		session.setAvailableRocketDurations(availableRecipes);
		FlightSession.Decision decision = session.tick(
				p.getX(), p.getY(), p.getZ(),
				p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
				p.getYRot(), p.getXRot(),
				Math.max(rocketsInHands, availableRecipes.length), boostRemaining, sessionTick, cooldownActive);
		// Keep the decision for the ring even when this tick ends the session:
		// the failure tick's rejection context must survive endSessionFromReason
		// (ab-17 audit section 6: the terminal tick's telemetry explains the end).
		tickDecision = decision;
		if (session.isDone()) {
			// Keep the terminal decision's note (candidate count, sim time,
			// refusal reason) so a live run can explain the end without the
			// debug console (R4: the river bend no_viable was unattributable).
			lastEndDetail = decision.note;
			FlightSession.EndReason reason = session.endReason();
			endSessionFromReason();
			// The session ends INSIDE driveSession (tick() marks it done), so
			// the recovery trigger must live here: an in-air internal failure
			// must not leave the last dive attitude on a released glider
			// (ab-17/18/19: both arms kept the dive and hit the terrain).
			if (reason == FlightSession.EndReason.NO_VIABLE_TRAJECTORY
					&& !p.onGround() && !p.isInWater() && p.isFallFlying()) {
				startRecovery(reason.name().toLowerCase(java.util.Locale.ROOT));
			}
			return null;
		}
		if (!decision.applicable) {
			// An UNDECIDED tick (candidate or terminal-verify budget spent) must
			// not silently inherit the current dive: a bounded verified safe
			// action takes over in the same tick, and the route stays undecided
			// (ab-29 review). A truly terminal decision still carries no input.
			if (session.isDone() || p.onGround() || !p.isFallFlying())
				return null;
			FlightSession.SafeFrame safe = session.safeFrame(
					p.getX(), p.getY(), p.getZ(),
					p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z,
					boostRemainingEstimateFor(tickBoostIds), countRocketsInHands(p),
					p.getYRot(), p.getXRot(), RECOVERY_SEARCH_TICKS);
			safeCandidates = safe.candidates;
			safeVerified = safe.verified;
			safeEmergency = safe.emergency;
			safeContactTicks = safe.contactTicks;
			recoveryFrameVerified = safe.verified > 0;
			return new InputFrame("flight-undecided", safe.yaw, safe.pitch, safe.fire);
		}
		// The winner's first tick is the frame; the caller applies it once
		// through the same accessors the launch macro uses, so the server sees
		// one consistent rotation source (A2: one submit per tick).
		return new InputFrame("flight-session", decision.yaw, decision.pitch, decision.fireRocket, decision.rocketFlightDuration);
	}

	/** Applies one frame: the single write of this tick, from one owner. */
	private void applyFrame(Minecraft mc, LocalPlayer p, InputFrame frame) {
		tickInputOwner = frame.owner;
		if (frame.yaw != null) {
			// Route, landing and recovery frames are rate-limited BEFORE their
			// collision sweep. A second actuator clamp would execute a different
			// trajectory from the one that was checked (ab-28).
			float yaw = frame.yaw;
			p.setYRot(yaw);
			p.setYHeadRot(yaw);
			p.setYBodyRot(yaw);
		}
		if (frame.pitch != null)
			p.setXRot(frame.pitch);
		if (frame.fire) {
			net.minecraft.world.InteractionHand hand;
			if (frame.rocketFlightDuration >= 0) {
				int slot = rocketSlot(p, frame.rocketFlightDuration);
				// Never substitute another recipe after prediction. Offhand is -1,
				// unavailable is -2; hotbar changes use the same server packet path.
				if (slot == -2) return;
				if (slot >= 0) {
					//? if >=1.21.5 {
					p.getInventory().setSelectedSlot(slot);
					//?} else
					p.getInventory().selected = slot;
					if (mc.getConnection() != null)
						mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
				}
				hand = slot == -1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
			} else hand = fireworkHand(p);
			if (hand != null) {
				tickFiredDuration = rocketDuration(p.getItemInHand(hand));
				mc.gameMode.useItem(p, hand);
				tickFiredThisTick = true;
			}
		}
	}

	/** Returns a directly executable recipe source, without moving inventory stacks. */
	private static int rocketSlot(LocalPlayer p, int duration) {
		if (isFireworkItem(p.getOffhandItem()) && rocketDuration(p.getOffhandItem()) == duration) return -1;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (isFireworkItem(stack) && rocketDuration(stack) == duration) return slot;
		}
		return -2;
	}
	private static int rocketDuration(ItemStack stack) {
		var fireworks = stack.get(net.minecraft.core.component.DataComponents.FIREWORKS);
		return fireworks == null ? 0 : fireworks.flightDuration();
	}

	private static int selectedRocketDuration(LocalPlayer p) {
		var hand = fireworkHand(p);
		return hand == null ? 0 : rocketDuration(p.getItemInHand(hand));
	}

	private static net.minecraft.world.InteractionHand fireworkHand(LocalPlayer p) {
		if (isFireworkItem(p.getMainHandItem()))
			return net.minecraft.world.InteractionHand.MAIN_HAND;
		if (isFireworkItem(p.getOffhandItem()))
			return net.minecraft.world.InteractionHand.OFF_HAND;
		return null;
	}

	private static boolean isFireworkItem(net.minecraft.world.item.ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& net.minecraft.world.item.Items.FIREWORK_ROCKET == stack.getItem();
	}

	private static int countRocketsInHands(LocalPlayer p) {
		int total = 0;
		for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
			net.minecraft.world.item.ItemStack stack = p.getItemInHand(hand);
			if (isFireworkItem(stack))
				total += stack.getCount();
		}
		return total;
	}

	/** Rockets in the 36 main inventory slots, without the hands. */
	private static int countRocketsInInventory(LocalPlayer p) {
		int total = 0;
		for (int slot = 0; slot <= 35; slot++) {
			net.minecraft.world.item.ItemStack stack = p.getInventory().getItem(slot);
			if (isFireworkItem(stack))
				total += stack.getCount();
		}
		return total;
	}

	/** Any firework in the hands or the 36 main inventory slots. */
	private static boolean hasFireworkRocket(LocalPlayer p) {
		if (isFireworkItem(p.getMainHandItem()) || isFireworkItem(p.getOffhandItem()))
			return true;
		for (int slot = 0; slot <= 35; slot++) {
			if (isFireworkItem(p.getInventory().getItem(slot)))
				return true;
		}
		return false;
	}

	private void recordTick(Minecraft mc, LocalPlayer p) {
		TickRecord record = new TickRecord();
		record.tick = moduleTick;
		fillObservation(mc, p, record);
		// The recovery owner keeps writing after the session terminated, and
		// the ring must show it: an invisible owner hid the recovery from the
		// ab-18 evidence (all post-failure samples read "none").
		if (state == SessionState.RUNNING || state == SessionState.ACCEPTED
				|| "flight-recovery".equals(tickInputOwner)
				|| "flight-landing".equals(tickInputOwner)
				|| "flight-undecided".equals(tickInputOwner))
			record.inputOwner = tickInputOwner;
		// Tie the sample to the session that produced it, so a record can be
		// split at a handover without guessing (R4 review item 1).
		if (sessionId != null)
			record.sessionId = sessionId;
		record.revision = revision;
		record.holding = session != null && session.isHolding();
		record.inWater = p.isInWater();
		record.rocketsInHands = countRocketsInHands(p);
		record.rocketsInInventory = countRocketsInInventory(p);
		record.rocketFiredThisTick = tickFiredThisTick;
		// Per-tick telemetry of the driving decision (ab-17 audit section 6):
		// the target/cursor, what the winning prediction did, the first
		// rejection and the terminal action. `tickDecision` is set when the
		// session was driven this tick, so a macro-owned or recovery-owned
		// tick records no stale decision.
		FlightSession.Decision decision = tickDecision;
		if (decision != null) {
			record.targetX = decision.targetX;
			record.targetY = decision.targetY;
			record.targetZ = decision.targetZ;
			record.cursor = decision.cursor;
			record.predictedEndTicks = decision.predictedEndTicks;
			record.predictedEndCursor = decision.predictedEndCursor;
			record.predictedEndX = decision.predictedEndX;
			record.predictedEndY = decision.predictedEndY;
			record.predictedEndZ = decision.predictedEndZ;
			record.predictedEndReason = decision.predictedEndReason;
			record.terminalAction = decision.terminalAction;
			record.preview = String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f;%.2f,%.2f,%.2f;%.2f,%.2f,%.2f",
					decision.preview[0], decision.preview[1], decision.preview[2],
					decision.preview[3], decision.preview[4], decision.preview[5],
					decision.preview[6], decision.preview[7], decision.preview[8]);
			record.rejectKind = decision.rejectKind;
			record.rejectTick = decision.rejectTick;
			record.rejectX = decision.rejectX;
			record.rejectY = decision.rejectY;
			record.rejectZ = decision.rejectZ;
			record.rejectBlock = decision.rejectBlock;
			record.rejectShape = decision.rejectShape;
			record.rejectCollisions = decision.rejectCollisions;
			record.rejectFloor = decision.rejectFloor;
			record.rejectSpeed = decision.rejectSpeed;
			record.rejectTerminal = decision.rejectTerminal;
			record.progress = decision.progress;
			record.predictedEndProgress = decision.predictedEndProgress;
			record.chosenPolicy = decision.chosenPolicy;
			record.planningState = decision.planningState;
			record.evaluated = decision.evaluated;
			record.feasible = decision.feasible;
			record.budgetExhausted = decision.budgetExhausted;
			record.searchCompleted = decision.searchCompleted;
			record.physicsSteps = decision.physicsSteps;
			record.blockQueries = decision.blockQueries;
			record.cacheHits = decision.cacheHits;
			record.simMs = decision.simMs;
			record.preparationUs = decision.preparationUs;
			record.orderingUs = decision.orderingUs;
			record.candidatesUs = decision.candidatesUs;
			record.selectionUs = decision.selectionUs;
			record.reservationUs = decision.reservationUs;
		}
		record.pendingSessionId = handover.pendingId() == null ? "" : handover.pendingId();
		record.controlPhase = ownership.phase().name();
		record.routeOutcome = ownership.routeOutcome().name();
		record.transitionReason = ownership.releaseReason().isEmpty() ? ownership.failureReason() : ownership.releaseReason();
		record.safeCandidates = safeCandidates;
		record.safeVerified = safeVerified;
		record.safeEmergency = safeEmergency;
		record.safeContactTicks = safeContactTicks;
		record.cooldownActive = session != null && session.fireCooldownActive(moduleTick - sessionStartTick);
		record.wallMs = System.currentTimeMillis();
		record.nanoMs = System.nanoTime();
		ring[ringHead] = record;
		ringHead = (ringHead + 1) % TRAJECTORY_CAPACITY;
		if (ringCount < TRAJECTORY_CAPACITY)
			ringCount++;
		else
			lostRecords += 1;
	}

	private void fillObservation(Minecraft mc, LocalPlayer p, TickRecord record) {
		record.x = p.getX();
		record.y = p.getY();
		record.z = p.getZ();
		record.vx = p.getDeltaMovement().x;
		record.vy = p.getDeltaMovement().y;
		record.vz = p.getDeltaMovement().z;
		record.yaw = p.getYRot();
		record.pitch = p.getXRot();
		record.gliding = p.isFallFlying();
		record.onGround = p.onGround();
		record.health = p.getHealth();
		record.boostAttached = !tickBoostIds.isEmpty();
		record.boostRemainingEstimate = boostRemainingEstimateFor(tickBoostIds);
		record.boostCount = tickBoostIds.size();
		record.rocketFlightDuration = selectedRocketDuration(p);
		record.plannedRocketFlightDuration = tickDecision == null ? -1 : tickDecision.rocketFlightDuration;
		record.firedRocketFlightDuration = tickFiredDuration;
		record.boostFlightDuration = tickBoostIds.stream().mapToInt(id -> boostDurations.getOrDefault(id, 0)).max().orElse(-1);
		record.boostEntityIds = tickBoostIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
	}

	/**
	 * The attached boost fireworks riding the player, by entity id. The client
	 * cannot read an attached firework's remaining life, so the ids and their
	 * first-seen ticks are the evidence base for the lifetime estimate. The
	 * ab-17 audit found speeds consistent with TWO overlapping rockets while
	 * the estimate expressed one, so the driver now refuses to fire while a
	 * rocket is attached and the telemetry records the entity ids/count
	 * (audit item 4).
	 */
	private java.util.List<Integer> boostEntityIds(Minecraft mc, LocalPlayer p) {
		java.util.List<Integer> ids = new java.util.ArrayList<>();
		if (mc.level == null)
			return ids;
		for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof FireworkRocketEntity firework) || !firework.isAlive())
				continue;
			if (firework.distanceToSqr(p) >= 9.0)
				continue;
			net.minecraft.world.entity.Entity owner = firework.getOwner();
			if (owner != null && owner.getUUID().equals(p.getUUID()))
				ids.add(firework.getId());
		}
		return ids;
	}

	/** Updates the per-entity first-seen bookkeeping and returns the active ids. */
	private java.util.List<Integer> refreshBoostEntities(Minecraft mc, LocalPlayer p) {
		java.util.List<Integer> ids = boostEntityIds(mc, p);
		boostFirstSeen.keySet().removeIf(id -> !ids.contains(id));
		boostDurations.keySet().removeIf(id -> !ids.contains(id));
		for (Integer id : ids) {
			if (mc.level.getEntity(id) instanceof FireworkRocketEntity rocket)
				boostDurations.put(id, rocketDuration(rocket.getItem()));
		}
		for (Integer id : ids)
			boostFirstSeen.putIfAbsent(id, (long) moduleTick);
		return ids;
	}

	private int boostRemainingEstimateFor(java.util.List<Integer> ids) {
		if (ids.isEmpty())
			return 0;
		int remaining = 0;
		for (Integer id : ids) {
			Long firstSeen = boostFirstSeen.get(id);
			int age = firstSeen == null ? 0 : (int)(moduleTick - firstSeen);
			int value = FlightRocket.remainingEstimate(boostDurations.getOrDefault(id, 0), age);
			remaining = Math.max(remaining, value);
		}
		return remaining;
	}

	private static String dimensionOf(LocalPlayer p) {
		return p.level().dimension().location().toString();
	}

	// --- Observation ---------------------------------------------------------------
	/** One same-tick observation snapshot; everything read in this call. */
	public synchronized JsonObject observeJson(Minecraft mc) throws RpcException {
		LocalPlayer p = mc.player;
		if (p == null)
			throw RpcException.noClientPlayer();
		JsonObject o = new JsonObject();
		o.addProperty("tick", moduleTick);
		o.addProperty("phase", "end-client-tick");
		o.addProperty("dimension", p.level().dimension().location().toString());
		JsonObject position = new JsonObject();
		position.addProperty("x", p.getX());
		position.addProperty("y", p.getY());
		position.addProperty("z", p.getZ());
		o.add("position", position);
		JsonObject velocity = new JsonObject();
		velocity.addProperty("x", p.getDeltaMovement().x);
		velocity.addProperty("y", p.getDeltaMovement().y);
		velocity.addProperty("z", p.getDeltaMovement().z);
		o.add("velocity", velocity);
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		o.addProperty("gliding", p.isFallFlying());
		o.addProperty("onGround", p.onGround());
		o.addProperty("health", p.getHealth());
		o.addProperty("food", p.getFoodData().getFoodLevel());
		JsonObject box = new JsonObject();
		box.addProperty("minX", p.getBoundingBox().minX);
		box.addProperty("minY", p.getBoundingBox().minY);
		box.addProperty("minZ", p.getBoundingBox().minZ);
		box.addProperty("maxX", p.getBoundingBox().maxX);
		box.addProperty("maxY", p.getBoundingBox().maxY);
		box.addProperty("maxZ", p.getBoundingBox().maxZ);
		o.add("collisionBox", box);
		JsonObject equipment = new JsonObject();
		equipment.addProperty("mainHand", itemId(p.getMainHandItem()));
		equipment.addProperty("offHand", itemId(p.getOffhandItem()));
		equipment.addProperty("chest", itemId(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)));
		o.add("equipment", equipment);
		java.util.List<Integer> observedBoostIds = boostEntityIds(mc, p);
		JsonObject boost = new JsonObject();
		boost.addProperty("attached", !observedBoostIds.isEmpty());
		boost.addProperty("count", observedBoostIds.size());
		boost.addProperty("entityIds", observedBoostIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
		boost.addProperty("remainingTicks", boostRemainingEstimateFor(observedBoostIds));
		boost.addProperty("remainingSource", "recipe-nominal-minus-observed-age;server-expiry-unknown");
		o.add("boost", boost);
		// Launch handover facts: the state the macro handed over from, so the
		// host never mistakes its first observation for the ignition moment.
		o.add("launch", BotController.get().launchStatusJson());
		JsonObject session = new JsonObject();
		session.addProperty("id", sessionId);
		session.addProperty("state", state == null ? "none" : state.name());
		session.addProperty("applyingStarted", applyingStarted);
		o.add("session", session);
		return o;
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "" : stack.getItem().toString();
	}

	// --- Channel contract -----------------------------------------------------------
	/**
	 * Submits a flight channel. Idempotent by session id: a repeat returns the
	 * original response without touching state. An older generation is
	 * rejected. Acceptance only records the contract; R2a never starts
	 * applying it (that is R2b and is reported separately).
	 */
	public synchronized JsonObject submitChannel(String sessionId, String controlSessionId, long generation,
			long revision, long deadlineMs, String dimension, JsonObject channel) {
		JsonObject previous = handledSessions.get(sessionId);
		if (previous != null)
			return previous;

		JsonObject o = new JsonObject();
		o.addProperty("sessionId", sessionId);
		o.addProperty("accepted", false);

		Long currentGeneration = currentGenerationOrNull();
		if (currentGeneration != null && generation < currentGeneration) {
			o.addProperty("reason", "stale_generation");
			o.addProperty("expectedGeneration", currentGeneration);
		}
		else if (ownership.acceptBlockReason(System.currentTimeMillis()) != null) {
			// A recovery owns the aircraft: no new route is adopted and the
			// frozen failure, window, cooldown and boost state stay untouched
			// (ab-23 repair plan, A2). The host must not revoke-and-retry here.
			o.addProperty("reason", "recovering");
			o.addProperty("phase", ownership.phase().name());
			o.addProperty("routeOutcome", ownership.routeOutcome().name());
		}
		else if (state == SessionState.RUNNING || state == SessionState.ACCEPTED) {
			// A route submitted by the same flight task is a handover, not a
			// second writer: keep flying the current path and adopt the new one
			// at a tick boundary (R4 review item 3). Everything else keeps the
			// single-writer refusal.
			JsonObject handover = acceptHandover(sessionId, controlSessionId, generation, revision, dimension, channel, o);
			if (handover == null)
				return o;
			rememberSession(sessionId, handover);
			return handover;
		}
		else if (BotController.get().hasActiveInputTask()) {
			// One writer at a time (revision doc §5): the channel must not
			// start while a legacy drive owns the player, and the legacy drive
			// must not start while a channel is live.
			o.addProperty("reason", "control_busy");
		}
		else if (!ControlOwnership.accept(controlSessionId, revision)) {
			// The session was revoked or the sequence is stale (CD-0 §3.1).
			o.addProperty("reason", "stale_control_session");
		}
		else {
			this.sessionId = sessionId;
			this.controlSessionId = controlSessionId;
			this.generation = generation;
			if (generation > lastGeneration)
				lastGeneration = generation;
			this.revision = revision;
			this.deadlineMs = deadlineMs;
			this.sessionDimension = dimension == null ? "" : dimension;
			this.state = SessionState.ACCEPTED;
			this.endReason = "";
			this.lastEndDetail = "";
			this.acceptedAtMs = System.currentTimeMillis();
			this.applyingStarted = false;
			this.applyingSinceTick = -1;
			this.launchRequested = false;
			this.recoveryProbe = null;
			this.recoveryHasLandingTarget = false;
			this.landingSession = null;
			this.landingHasPlan = false;
			this.recoveryStableTicks = 0;
			this.recoveryReselects = 0;
			// A new route starts a fresh ownership: running, prepare phase.
			ownership.startRoute(deadlineMs);
			o.addProperty("accepted", true);
			o.addProperty("startedApplying", false);
			if (channel != null)
				o.add("echo", channel);
			// R2b: a channel payload with an ordered path starts the session
			// driver. The path is the host's verified route (R1 output) and is
			// taken as-is — the sweep re-verifies every candidate against the
			// live client world each tick.
			List<FlightSession.Waypoint> path = pathFromChannel(channel);
			if (path != null) {
				// The channel's parameters become the controller's shared set,
				// so the landing plan and the probe validate with the same
				// candidate limits the route used (C2).
				params = paramsFromChannel(channel);
				session = new FlightSession(path, flightBlockQuery(Minecraft.getInstance()), deadlineMs, params, HANDOVER_GRACE_MS);
				session.setRouteIdentity(sessionId, revision);
				sessionStartTick = moduleTick;
						o.addProperty("pathPoints", path.size());
				// Echo the value the session actually adopted: echoing the
				// request without consuming it was an R2b defect.
				o.addProperty("entryReach", params.entryReach);
				o.addProperty("terminalReach", session.terminalReach());
			}
		}
		// Only an ACCEPTED submit is remembered for idempotency. A refused
		// attempt (stale_generation, session_active, control_busy, ...) leaves
		// no session to protect, and remembering it would replay the refusal
		// forever — the runner's documented repair (retry with the receipt's
		// expected generation, or revoke the leftover and retry) could never
		// take effect (R3 live, 2026-09-20).
		if (o.has("accepted") && o.get("accepted").getAsBoolean())
			rememberSession(sessionId, o);
		return o;
	}

	/**
	 * Accepts a route submitted by the same flight task as a pending handover.
	 * The current route keeps flying; the pending one is adopted at a tick
	 * boundary once its entry is reachable (R4 review item 3). Returns the
	 * response when accepted, or null after writing a typed refusal into
	 * {@code o} (the caller returns it).
	 */
	private JsonObject acceptHandover(String sessionId, String controlSessionId, long generation,
			long revision, String dimension, JsonObject channel, JsonObject o) {
		if (controlSessionId == null || !controlSessionId.equals(this.controlSessionId)) {
			o.addProperty("reason", "session_active");
			o.addProperty("activeSessionId", this.sessionId);
			return null;
		}
		if (generation < this.generation || revision <= this.revision) {
			o.addProperty("reason", "handover_stale");
			o.addProperty("activeSessionId", this.sessionId);
			return null;
		}
		String requestDimension = dimension == null ? "" : dimension;
		if (!sessionDimension.isEmpty() && !sessionDimension.equals(requestDimension)) {
			o.addProperty("reason", "handover_dimension");
			return null;
		}
		List<FlightSession.Waypoint> path = pathFromChannel(channel);
		if (path == null) {
			o.addProperty("reason", "handover_no_path");
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc == null ? null : mc.player;
		if (p == null || !p.isFallFlying()) {
			o.addProperty("reason", "handover_not_flying");
			return null;
		}
		// Water has no glide model: accepting a leg while floating would hand
		// the driver a route it cannot fly (R4 cave-diag-16).
		if (p.isInWater()) {
			o.addProperty("reason", "handover_in_water");
			return null;
		}
		// The route was planned from the frontier the glider is approaching;
		// an entry far from her is a route for a different place, not this leg.
		FlightSession.Waypoint first = path.get(0);
		double entryDistance = distance3(first.x(), first.y(), first.z(), p.getX(), p.getY(), p.getZ());
		if (entryDistance > HANDOVER_ENTRY_LIMIT) {
			o.addProperty("reason", "handover_entry_far");
			o.addProperty("entryDistance", entryDistance);
			return null;
		}
		params = paramsFromChannel(channel);
		FlightSession candidate = new FlightSession(path, flightBlockQuery(mc), deadlineMs, params, HANDOVER_GRACE_MS);
		candidate.setRouteIdentity(sessionId, revision);
		// The state machine keeps the NEWEST valid route: a duplicate, stale
		// or out-of-order delivery must not replace a newer pending route
		// (R4 review group 3).
		if (!handover.offer(sessionId, revision, candidate, System.currentTimeMillis(), this.revision)) {
			o.addProperty("reason", "handover_stale");
			o.addProperty("activeSessionId", this.sessionId);
			return null;
		}
		o.addProperty("accepted", true);
		o.addProperty("pending", true);
		o.addProperty("activeSessionId", this.sessionId);
		o.addProperty("pathPoints", path.size());
		o.addProperty("entryReach", params.entryReach);
		o.addProperty("terminalReach", candidate.terminalReach());
		o.addProperty("entryDistance", entryDistance);
		return o;
	}

	/** Parses the channel's ordered path; null when the payload carries none. */
	private static List<FlightSession.Waypoint> pathFromChannel(JsonObject channel) {
		if (channel == null || !channel.has("path") || !channel.get("path").isJsonArray())
			return null;
		List<FlightSession.Waypoint> path = new ArrayList<>();
		for (var element : channel.getAsJsonArray("path")) {
			if (!element.isJsonObject())
				return null;
			JsonObject point = element.getAsJsonObject();
			path.add(new FlightSession.Waypoint(
					point.get("x").getAsDouble(),
					point.get("y").getAsDouble(),
					point.get("z").getAsDouble()));
		}
		return path.size() >= 2 ? path : null;
	}

	/**
	 * Reads the session parameters the channel payload carries. The host sends
	 * {@code entryReach}; an absent or invalid value keeps the default. The
	 * adopted value is echoed in the submit response (R2b fix).
	 */
	private static FlightSession.Params paramsFromChannel(JsonObject channel) {
		FlightSession.Params params = new FlightSession.Params();
		if (channel != null && channel.has("entryReach") && channel.get("entryReach").isJsonPrimitive()) {
			double reach = channel.get("entryReach").getAsDouble();
			if (reach > 0 && reach <= 64)
				params.entryReach = reach;
		}
		// The host marks a handover leg `through` (ab-30 plan §2); anything else
		// (absent, unknown, `stop`) keeps the terminal hold's descent.
		if (channel != null && channel.has("kind") && channel.get("kind").isJsonPrimitive()) {
			String kind = channel.get("kind").getAsString();
			params.throughWaypoint = "through".equals(kind);
			params.stopAtEnd = "stop".equals(kind);
		}
		if (channel != null && channel.has("terminalReach") && channel.get("terminalReach").isJsonPrimitive()) {
			double reach = channel.get("terminalReach").getAsDouble();
			if (Double.isFinite(reach) && reach > 0 && reach <= params.entryReach)
				params.terminalReach = reach;
		}
		params.terminalPlanning = params.stopAtEnd && channel != null && channel.has("terminalPlanning")
				&& channel.get("terminalPlanning").getAsBoolean();
		return params;
	}

	/**
	 * The block query the session sweeps against: live client world state.
	 *
	 * Passability comes from the block's real collision shape, not from its id
	 * suffix. Most pass-without-collision blocks collapse to `passable` (grass,
	 * sugar cane, rails, torches). Water keeps its real id: it has no collision
	 * like the others, but it is also a MEDIUM (entering it ends the glide) and
	 * a SURFACE (the hold and continuation checks must not scan through it to
	 * the riverbed). Collapsing water to `passable` made every water-surface
	 * clearance read as the riverbed, so a low hold targeted y=61 instead of
	 * the water top (R4 client review 2026-09-21, item 1). Lava and fire also
	 * have empty shapes but damage on contact, so they stay obstacles by id.
	 * Unknown/unloaded stays null (never air, CD-0).
	 */
	private FlightSession.BlockQuery flightBlockQuery(Minecraft mc) {
		return new FlightSession.BlockQuery() {
			@Override
			public double frictionAt(double x, double y, double z) {
				var pos = net.minecraft.core.BlockPos.containing(x, y, z);
				if (mc.level == null || !mc.level.isLoaded(pos))
					return Double.NaN;
				var support = mc.level.getBlockState(pos);
				// The landing model uses a flat full-block top. Partial shapes
				// need their actual contact plane before they can certify runout.
				if (!support.isCollisionShapeFullBlock(mc.level, pos))
					return Double.NaN;
				return support.getBlock().getFriction();
			}

			@Override
			public String idAt(double x, double y, double z) {
				if (mc.level == null)
					return null;
				var pos = net.minecraft.core.BlockPos.containing(x, y, z);
				if (!mc.level.isLoaded(pos))
					return null;
				net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
				// Native air needs no registry lookup or shape construction. Other
				// blocks still use fluid and collision semantics below.
				if (state.isAir()) return "passable";
				String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
				if (id.endsWith("lava") || id.endsWith("fire") || id.equals("sweet_berry_bush"))
					return id;
				if (id.endsWith("water") || !state.getFluidState().isEmpty())
					return "minecraft:water";
				if (state.getCollisionShape(mc.level, pos).isEmpty())
					return "passable";
				return id;
			}

			/**
			 * Collision-shape tag for rejection telemetry (ab-17 audit section
			 * 6): "full" for a full cube, "partial" for a non-empty shape
			 * smaller than a cube, "empty" for none, "unknown" when unloaded.
			 */
			@Override
			public String shapeAt(double x, double y, double z) {
				if (mc.level == null)
					return "unknown";
				var pos = net.minecraft.core.BlockPos.containing(x, y, z);
				if (!mc.level.isLoaded(pos))
					return "unknown";
				net.minecraft.world.phys.shapes.VoxelShape shape =
						mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
				if (shape.isEmpty())
					return "empty";
				net.minecraft.world.phys.AABB bounds = shape.bounds();
				boolean full = bounds.minX <= 0.0 && bounds.minY <= 0.0 && bounds.minZ <= 0.0
						&& bounds.maxX >= 1.0 && bounds.maxY >= 1.0 && bounds.maxZ >= 1.0;
				return full ? "full" : "partial";
			}
		};
	}

	public synchronized JsonObject revokeChannel(String sessionId) {
		JsonObject o = new JsonObject();
		o.addProperty("sessionId", sessionId);
		if (this.sessionId == null || !this.sessionId.equals(sessionId)) {
			o.addProperty("revoked", false);
			o.addProperty("reason", this.sessionId == null ? "no_session" : "session_mismatch");
			return o;
		}
		// The revoke request and the confirmed release are separate facts
		// (revision doc §7): the session stops at once, and reports it.
		boolean wasActive = state == SessionState.RUNNING || state == SessionState.ACCEPTED;
		if (wasActive)
			terminateIfActive("revoked");
		o.addProperty("revoked", true);
		o.addProperty("wasActive", wasActive);
		o.addProperty("endReason", endReason);
		return o;
	}

	/**
	 * Terminates a live channel from an external lifecycle event: death,
	 * disconnect, world exit, or the bridge watchdog when no channel covers
	 * the drive. The launch macro is cancelled too, so no input survives the
	 * stop (R2b fix).
	 */
	public synchronized void externalStop(String reason) {
		terminateIfActive(reason);
	}

	/**
	 * True while a channel may own input. The control guard must not clear a
	 * live channel on the legacy bridge heartbeat (R2b fix): within the
	 * channel window the client drives by the session deadline, and
	 * disconnect, death, dimension change and revoke remain terminating
	 * conditions. The controller runs after the guard in the same tick, so a
	 * passed deadline is reported as {@code deadline}, not {@code bridge_timeout}.
	 */
	public synchronized boolean hasLiveChannel() {
		// A recovery still inside its policy window is a live autonomous owner:
		// the guard must not clear it on the bridge heartbeat (ab-23 repair
		// plan, A3).
		return state == SessionState.ACCEPTED || state == SessionState.RUNNING
				|| ownership.hasLiveControl(System.currentTimeMillis());
	}

	/**
	 * One termination entry point: mark the terminal state, cancel the launch
	 * macro, and drop the pending session so no later tick can write input
	 * (R2b fix). {@code applyingStarted} stays as the fact that input was once
	 * written.
	 */
	private void terminateIfActive(String reason) {
		if (state == SessionState.RUNNING || state == SessionState.ACCEPTED) {
			state = SessionState.TERMINATED;
			endReason = reason;
		}
		if (launchRequested) {
			BotController.get().cancelLaunch();
			launchRequested = false;
		}
		if (session != null) session.closePlanning();
		session = null;
		handover.dropPending();
		// An explicit revoke cancels the recovery owner too; every other stop
		// (death, disconnect, dimension change, damage, deadline) releases the
		// phase while the route outcome stays whatever it already was (A2).
		if ("revoked".equals(reason))
			ownership.revoke();
		else
			ownership.release(reason);
	}

	/** 3D distance between two points; Java's Math.hypot takes only two. */
	private static double distance3(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private Long currentGenerationOrNull() {
		// The client has no independent generation source in R2a: the stored
		// value is the highest generation this controller has accepted, which
		// is exactly what "an older submit is stale" needs to compare against.
		// A reconnect clears the session but keeps lastGeneration, so an old
		// submit replayed after a reconnect is still rejected.
		return lastGeneration > 0 ? Long.valueOf(lastGeneration) : null;
	}

	private void rememberSession(String sessionId, JsonObject response) {
		if (handledSessions.size() >= SESSION_MEMORY)
			handledSessions.remove(firstKey(handledSessions));
		handledSessions.put(sessionId, response);
	}

	private static String firstKey(LinkedHashMap<String, ?> map) {
		for (String key : map.keySet())
			return key;
		return null;
	}

	// --- Ignition with dedup ---------------------------------------------------------
	/**
	 * Fires one firework from either hand, deduplicated by op id. A repeated
	 * op id returns the first response verbatim; the rocket does not fire
	 * again (execution plan §6).
	 */
	public synchronized JsonObject igniteFirework(String opId) throws RpcException {
		JsonObject previous = usedOps.get(opId);
		if (previous != null)
			return previous;

		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null)
			throw RpcException.noClientPlayer();

		JsonObject o = new JsonObject();
		o.addProperty("opId", opId);
		net.minecraft.world.InteractionHand hand = null;
		if (isFirework(p.getMainHandItem()))
			hand = net.minecraft.world.InteractionHand.MAIN_HAND;
		else if (isFirework(p.getOffhandItem()))
			hand = net.minecraft.world.InteractionHand.OFF_HAND;
		if (hand == null) {
			o.addProperty("fired", false);
			o.addProperty("reason", "no_firework_in_hands");
		}
		else {
			mc.gameMode.useItem(p, hand);
			lastIgniteAtMs = System.currentTimeMillis();
			o.addProperty("fired", true);
			o.addProperty("hand", hand == net.minecraft.world.InteractionHand.MAIN_HAND ? "main" : "off");
			// The ignition lands in the trajectory ring as its own record.
			TickRecord record = new TickRecord();
			record.tick = moduleTick;
			fillObservation(mc, p, record);
			record.rocketFiredThisTick = true;
			if (sessionId != null)
				record.sessionId = sessionId;
			record.revision = revision;
			record.holding = session != null && session.isHolding();
			record.inWater = p.isInWater();
			record.rocketsInHands = countRocketsInHands(p);
			record.rocketsInInventory = countRocketsInInventory(p);
			ring[ringHead] = record;
			ringHead = (ringHead + 1) % TRAJECTORY_CAPACITY;
			if (ringCount < TRAJECTORY_CAPACITY)
				ringCount++;
			else
				lostRecords += 1;
		}
		if (usedOps.size() >= OP_MEMORY)
			usedOps.remove(firstKey(usedOps));
		usedOps.put(opId, o);
		return o;
	}

	private static boolean isFirework(ItemStack stack) {
		return isFireworkItem(stack);
	}

	// --- Status and trajectory batch ----------------------------------------------------
	public synchronized JsonObject statusJson(long sinceTick) {
		JsonObject o = new JsonObject();
		o.addProperty("sessionId", sessionId);
		o.addProperty("state", state == null ? "none" : state.name());
		o.addProperty("endReason", endReason);
		o.addProperty("endDetail", lastEndDetail);
		o.addProperty("acceptedAtMs", acceptedAtMs);
		o.addProperty("applyingStarted", applyingStarted);
		o.addProperty("applyingSinceTick", applyingSinceTick);
		// Handover protocol facts (R4 review item 3): the host uses these to
		// submit the next leg early and to see when the client adopted it.
		o.addProperty("handoverCapable", true);
		if (handover.pendingId() != null)
			o.addProperty("pendingSessionId", handover.pendingId());
		o.addProperty("handoverCount", handover.handoverCount());
		o.addProperty("holding", session != null && session.isHolding());
		// Route outcome and physical control phase are separate facts (A2/A3):
		// the host must not infer "released" from a business end reason.
		o.addProperty("phase", ownership.phase().name());
		o.addProperty("routeOutcome", ownership.routeOutcome().name());
		o.addProperty("recoveryReason", ownership.failureReason());
		o.addProperty("recovering", ownership.recovering());
		o.addProperty("recoveryReselects", recoveryReselects);
		o.addProperty("recoveryUnresolved", recoveryUnresolved);
		o.addProperty("recoveryFrameVerified", recoveryFrameVerified);
		o.addProperty("recoveryStableTicks", recoveryStableTicks);
		o.addProperty("releaseReason", ownership.releaseReason());
		o.addProperty("build", dev.mcpfabric.McpFabric.MOD_VERSION);
		if (recoveryHasLandingTarget) {
			o.addProperty("landingTargetX", recoveryLandingX);
			o.addProperty("landingTargetY", recoveryLandingY);
			o.addProperty("landingTargetZ", recoveryLandingZ);
		}
		// Rocket supply telemetry (R4 review 3.3): the drive must be able to
		// fire, and the counters show whether a missing ignition was a supply
		// problem or a decision problem.
		o.addProperty("rocketResupplies", rocketResupplies);
		o.addProperty("controlSessionId", controlSessionId);
		o.addProperty("generation", generation);
		o.addProperty("revision", revision);
		o.addProperty("deadlineMs", deadlineMs);
		o.addProperty("dimension", sessionDimension);
		if (session != null) {
			o.addProperty("entryReach", session.entryReach());
			o.addProperty("terminalReach", session.terminalReach());
		}

		// Trajectory batch: records newer than sinceTick, oldest first, with
		// the number of records the ring already overwrote since the last read.
		JsonArray samples = new JsonArray();
		if (ringCount > 0) {
			int oldest = (ringHead - ringCount + TRAJECTORY_CAPACITY) % TRAJECTORY_CAPACITY;
			for (int i = 0; i < ringCount; i++) {
				TickRecord record = ring[(oldest + i) % TRAJECTORY_CAPACITY];
				if (record == null || record.tick <= sinceTick)
					continue;
				JsonObject sample = new JsonObject();
				sample.addProperty("tick", record.tick);
				sample.addProperty("controlPhase", record.controlPhase);
				JsonObject position = new JsonObject();
				position.addProperty("x", record.x);
				position.addProperty("y", record.y);
				position.addProperty("z", record.z);
				sample.add("position", position);
				JsonObject velocity = new JsonObject();
				velocity.addProperty("x", record.vx);
				velocity.addProperty("y", record.vy);
				velocity.addProperty("z", record.vz);
				sample.add("velocity", velocity);
				sample.addProperty("yaw", record.yaw);
				sample.addProperty("pitch", record.pitch);
				sample.addProperty("gliding", record.gliding);
				sample.addProperty("onGround", record.onGround);
				sample.addProperty("boostAttached", record.boostAttached);
				sample.addProperty("boostRemainingEstimate", record.boostRemainingEstimate);
				sample.addProperty("rocketFlightDuration", record.rocketFlightDuration);
				sample.addProperty("plannedRocketFlightDuration", record.plannedRocketFlightDuration);
				sample.addProperty("firedRocketFlightDuration", record.firedRocketFlightDuration);
				sample.addProperty("boostFlightDuration", record.boostFlightDuration);
				sample.addProperty("boostCount", record.boostCount);
				sample.addProperty("boostEntityIds", record.boostEntityIds);
				sample.addProperty("rocketFiredThisTick", record.rocketFiredThisTick);
				sample.addProperty("inputOwner", record.inputOwner);
				sample.addProperty("health", record.health);
				sample.addProperty("sessionId", record.sessionId);
				sample.addProperty("revision", record.revision);
				sample.addProperty("holding", record.holding);
				sample.addProperty("inWater", record.inWater);
				sample.addProperty("rocketsInHands", record.rocketsInHands);
				sample.addProperty("rocketsInInventory", record.rocketsInInventory);
				// Decision telemetry (ab-17 audit section 6) + clocks for the
				// video/tick alignment the audit asked for.
				sample.addProperty("targetX", record.targetX);
				sample.addProperty("targetY", record.targetY);
				sample.addProperty("targetZ", record.targetZ);
				sample.addProperty("cursor", record.cursor);
				sample.addProperty("predictedEndTicks", record.predictedEndTicks);
				sample.addProperty("predictedEndCursor", record.predictedEndCursor);
				sample.addProperty("predictedEndX", record.predictedEndX);
				sample.addProperty("predictedEndY", record.predictedEndY);
				sample.addProperty("predictedEndZ", record.predictedEndZ);
				sample.addProperty("predictedEndReason", record.predictedEndReason);
				sample.addProperty("preview", record.preview);
				sample.addProperty("terminalAction", record.terminalAction);
				sample.addProperty("rejectKind", record.rejectKind);
				sample.addProperty("rejectTick", record.rejectTick);
				sample.addProperty("rejectX", record.rejectX);
				sample.addProperty("rejectY", record.rejectY);
				sample.addProperty("rejectZ", record.rejectZ);
				sample.addProperty("rejectBlock", record.rejectBlock);
				sample.addProperty("rejectShape", record.rejectShape);
				sample.addProperty("rejectCollisions", record.rejectCollisions);
				sample.addProperty("rejectFloor", record.rejectFloor);
				sample.addProperty("rejectSpeed", record.rejectSpeed);
				sample.addProperty("rejectTerminal", record.rejectTerminal);
				sample.addProperty("pendingSessionId", record.pendingSessionId);
				sample.addProperty("wallMs", record.wallMs);
				sample.addProperty("nanoMs", record.nanoMs);
				sample.addProperty("controlPhase", record.controlPhase);
				sample.addProperty("routeOutcome", record.routeOutcome);
				sample.addProperty("progress", record.progress);
				sample.addProperty("predictedEndProgress", record.predictedEndProgress);
				sample.addProperty("chosenPolicy", record.chosenPolicy);
				sample.addProperty("planningState", record.planningState);
				sample.addProperty("evaluated", record.evaluated);
				sample.addProperty("feasible", record.feasible);
				sample.addProperty("budgetExhausted", record.budgetExhausted);
				sample.addProperty("searchCompleted", record.searchCompleted);
				sample.addProperty("physicsSteps", record.physicsSteps);
				sample.addProperty("blockQueries", record.blockQueries);
				sample.addProperty("cacheHits", record.cacheHits);
				sample.addProperty("simMs", record.simMs);
				sample.addProperty("preparationUs", record.preparationUs);
				sample.addProperty("orderingUs", record.orderingUs);
				sample.addProperty("candidatesUs", record.candidatesUs);
				sample.addProperty("selectionUs", record.selectionUs);
				sample.addProperty("reservationUs", record.reservationUs);
				sample.addProperty("cooldownActive", record.cooldownActive);
				sample.addProperty("transitionReason", record.transitionReason);
				sample.addProperty("safeCandidates", record.safeCandidates);
				sample.addProperty("safeVerified", record.safeVerified);
				sample.addProperty("safeEmergency", record.safeEmergency);
				sample.addProperty("safeContactTicks", record.safeContactTicks);
				samples.add(sample);
			}
		}
		o.add("trajectory", samples);
		o.addProperty("trajectoryLost", lostRecords);
		o.addProperty("ringCapacity", TRAJECTORY_CAPACITY);
		lastReadTick = moduleTick;
		return o;
	}

	/** Clearing helper for the disconnect/death paths (called on the game thread). */
	public synchronized void clearTransient() {
		terminateIfActive("disconnected");
	}

	/** Small view the handlers use to expose the trajectory cursor. */
	public synchronized long lastReadTick() {
		return lastReadTick;
	}

}










