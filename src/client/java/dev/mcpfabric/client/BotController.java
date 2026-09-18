package dev.mcpfabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick driver for the local player: holds desired movement input (applied through key
 * mappings so it integrates with the vanilla input pipeline), single-shot jumps, survival mining,
 * and navigation following. The single instance ticks from {@code ClientTickEvents.END_CLIENT_TICK}.
 */
public final class BotController {
	private static final BotController INSTANCE = new BotController();

	public static BotController get() {
		return INSTANCE;
	}

	private BotController() {}

	// desired movement
	private volatile boolean fwd, back, left, right, jumpHeld, sneak, sprint;
	private int jumpOnceTicks = 0;

	// survival mining
	private BlockPos miningPos;
	private Direction miningFace = Direction.UP;

	// navigation
	private List<BlockPos> path;
	private int pathIndex;
	private BlockPos navTarget;
	private double reachRadius = 1.0;
	private boolean navSprint;
	private long navDeadline;
	private double lastDist = Double.MAX_VALUE;
	private int stuckTicks;
	private volatile String navState = "idle";
	private boolean drivingKeys;
	/** Command id that started the current navigation; echoed in reflex events. */
	private volatile String navCommandId;
	/** When true, the reflex controller is holding the use key (eating). */
	private boolean useHeld;

	// navigation terminal state (P2): last end reason, time, distance, position
	private volatile long navEndedAt;
	private volatile String navEndReason = "idle";
	private volatile double navEndDistance = Double.NaN;
	private volatile boolean navEndHasPosition;
	private volatile double navEndX, navEndY, navEndZ;

	// pending cleanup requests, applied on the next client tick (P1)
	private boolean releaseUseRequested;
	private boolean stopMiningRequested;

	// --- MC-4d ranged weapon task ----------------------------------------------------------
	/** Per-tick weapon state machine; mirrors the navigation task shape (start/status/stop). */
	private enum CombatPhase { IDLE, LOADING, CHARGING, HOLDING, TRACKING }
	private static final List<String> ARROW_ITEMS = List.of(
			"minecraft:arrow", "minecraft:tipped_arrow", "minecraft:spectral_arrow");

	/**
	 * Flight-time clamp: at least one tick, at most two seconds. Kept for the lead pass, which
	 * clamps the ballistic solver's predicted flight time to the same window.
	 */
	private static final double COMBAT_MIN_FLIGHT_TICKS = 1.0;
	private static final double COMBAT_MAX_FLIGHT_TICKS = 40.0;
	/** Lead distance cap in blocks, so an extreme velocity cannot over-aim. */
	private static final double COMBAT_MAX_LEAD_BLOCKS = 6.0;
	/** Own or vehicle motion below this counts as at rest when picking a lead. */
	private static final double COMBAT_MIN_MOTION_SQR = 1.0E-4;
	/** Ticks the crossbow load use is held before the deliberate release (vanilla load 25). */
	private static final int CROSSBOW_LOAD_HOLD_TICKS = 30;
	/** Ticks per hold+release cycle while waiting for the charged component. */
	private static final int CROSSBOW_LOAD_CYCLE_TICKS = 45;
	/** Bounded window before an uncharged crossbow is reported as load_timeout. */
	private static final int CROSSBOW_LOAD_TIMEOUT_TICKS = 135;
	/**
	 * Ticks the client-side tracked position trails the server.
	 *
	 * Tracked entities are interpolated over a fixed window (vanilla lerps a
	 * remote entity to its new position over three ticks), so a rider's client
	 * position is about this far behind the server. An aim built from the client
	 * position must add this before the flight time (F-26), otherwise the arrow
	 * lands that many ticks of travel behind a moving target.
	 */
	private static final double COMBAT_CLIENT_INTERP_TICKS = 3.0;

	/**
	 * Ballistic launch model mirrored from the audited host profile.
	 *
	 * The host owns the versioned profile (`ballistics/profile.ts`, `PROFILE_VERSION 1.21.1`,
	 * `SOLUTION_REVISION 1`), but the client aims every tick, so it runs the same per-tick model
	 * locally instead of receiving one stale solution. Constants: arrow gravity 0.05 and air
	 * inertia 0.99 (`AbstractArrow#tick`), spawn at `eyeY - 0.1` (`AbstractArrow` constructor),
	 * bow speed `power(charge) * 3.0` (`BowItem#releaseUsing/#getPowerForTime`), crossbow
	 * 3.15/1.6 and trident 2.5 (`CrossbowItem#getShootingPower`, `TridentItem#releaseUsing`).
	 * Without this solve the aim points straight at the target and every shot lands about one
	 * block low at 20 blocks (range measurement, 2026-09-17).
	 */
	private static final double BALLISTIC_GRAVITY = 0.05;
	private static final double BALLISTIC_AIR_INERTIA = 0.99;
	private static final double BALLISTIC_SPAWN_OFFSET_Y = -0.1;
	private static final double BALLISTIC_BOW_MAX_SPEED = 3.0;
	private static final int BALLISTIC_BOW_FULL_CHARGE_TICKS = 20;
	private static final double BALLISTIC_CROSSBOW_ARROW_SPEED = 3.15;
	private static final double BALLISTIC_CROSSBOW_FIREWORK_SPEED = 1.6;
	private static final double BALLISTIC_TRIDENT_SPEED = 2.5;
	/** A tick segment that passes this close to the aim point counts as a hit. */
	private static final double BALLISTIC_HIT_RADIUS = 0.3;
	/** The search lets a curve overshoot the target's distance by this many blocks before it stops. */
	private static final double BALLISTIC_PAST_TARGET_MARGIN = 2.0;
	private static final int BALLISTIC_MAX_TICKS = 120;
	private static final double BALLISTIC_PITCH_MIN_DEG = -80.0;
	private static final double BALLISTIC_PITCH_MAX_DEG = 80.0;
	private static final int BALLISTIC_COARSE_STEPS = 32;
	/** Refinement offsets in degrees around the best coarse pitch, then around its best fine pitch. */
	private static final double[] BALLISTIC_REFINE_OFFSETS = { -2, -1.5, -1, -0.5, -0.25, 0, 0.25, 0.5, 1, 1.5, 2 };
	private static final double[] BALLISTIC_FINE_OFFSETS = { -0.5, -0.4, -0.3, -0.2, -0.1, 0, 0.1, 0.2, 0.3, 0.4, 0.5 };

	/** One solved launch: the pitch to hold, the predicted flight time and whether a curve hit. */
	private record BallisticAim(double pitch, int flightTicks, boolean hit) {}

	/** One simulated pitch candidate and its closest approach to the aim point. */
	private record BallisticShot(double pitch, double closest, int tick, double peakY, boolean blocked) {
		boolean hit() {
			return !blocked && closest <= BALLISTIC_HIT_RADIUS;
		}

		/**
		 * Candidate ranking: a clear hit wins, then the earliest hit, then the flattest arc;
		 * a clear miss ranks by its closest approach, and a blocked curve ranks below every
		 * clear one. A target no curve can reach still keeps the blocked curve that passed
		 * nearest, which is the least bad shot.
		 */
		double score() {
			if (hit())
				return tick * 1000.0 + peakY;
			if (blocked)
				return 1.0e12 + closest * 10000.0 + tick * 10.0;
			return 1.0e9 + closest * 10000.0 + tick * 10.0;
		}
	}

	private CombatPhase combatPhase = CombatPhase.IDLE;
	private String combatWeapon;
	private double combatTargetX, combatTargetY, combatTargetZ;
	private String combatTargetUuid;
	private int combatMaxShots = 1;
	private int combatChargeTicks = 20;
	/** Delay between separate projectile-tracking scans after a release. */
	private int combatTrackTicks;
	private int combatShotsFired;
	private int combatHoldTicks;
	private boolean combatCharged;
	private int combatLoadTicks;
	/** F-28: one release per hold+release load cycle is enough. */
	private boolean combatLoadReleased;
	/** Monotonic per-task shot sequence, so a receipt can name each fired projectile. */
	private int combatNextShot;
	private final List<UUID> combatProjectiles = new ArrayList<>();
	/** UUID of the trident thrown by this task; used for the return/回收 report. */
	private UUID combatTridentUuid;
	/** Trident count across the inventory right after the throw; compared for return. */
	private int combatTridentCountAfter = -1;
	/** Set only when the throw's trident was seen come back; never "any trident exists". */
	private boolean combatTridentReturned;
	private int combatReturnTicks;
	/** Game tick at the moment the last projectile was launched; bounds event attribution. */
	private long combatLastShotTick;
	/**
	 * Aim point recomputed every tick from the pinned target's current position.
	 *
	 * A target that moved after {@code combat_start} must be aimed at its fresh
	 * position, not the coordinates captured when the shot began. When the entity
	 * is not in render range the previous point is kept; {@code combatAimSource}
	 * names which case produced the current point.
	 */
	private double combatAimX, combatAimY, combatAimZ;
	private boolean combatAimHasPosition;
	private String combatAimSource = "pinned";
	/** Bounded linear lead; disabled by config so the aim can be read straight. */
	private boolean combatLeadEnabled = true;
	/** Last aim-tick sample, used to estimate motion when the entity carries none (riders). */
	private UUID combatMotionUuid;
	private long combatMotionTick = Long.MIN_VALUE;
	private double combatMotionX, combatMotionY, combatMotionZ;
	/** Last lead source and its raw inputs, reported in the combat status (F-24). */
	private String combatLeadSource = "none";
	private Vec3 combatLeadVelocity;
	private double combatLeadOwnSpeed = -1.0;
	private boolean combatLeadVehicleFound;
	private double combatLeadVehicleSpeed = -1.0;
	private double combatLeadMeasuredSpeed = -1.0;
	/** Final flight ticks the aim solve used, and the exact release state (F-26). */
	private double combatLastFlightTicks = -1.0;
	private boolean combatReleaseCaptured;
	private double combatReleaseAimX, combatReleaseAimY, combatReleaseAimZ;
	private double combatReleaseTargetX, combatReleaseTargetY, combatReleaseTargetZ;
	private double combatReleaseLeadX, combatReleaseLeadY, combatReleaseLeadZ;
	private String combatReleaseLeadSource = "none";
	private double combatReleaseChargeTicks = -1.0;
	private int combatReleaseHoldTicks = -1;
	private double combatReleaseFlightTicks = -1.0;
	private double combatReleaseArrowX = Double.NaN;
	private double combatReleaseArrowY = Double.NaN;
	private double combatReleaseArrowZ = Double.NaN;
	private int combatReleaseArrowTick = -1;
	/** A release waiting for its projectile or ammo-delta confirmation. */
	private boolean combatShotPending;
	private int combatShotPendingTicks;
	private int combatAmmoBefore;
	private String combatShotVerifiedBy;
	/** One entry per fired shot: "projectile" or "ammo" (aligned with shot order). */
	private final List<String> combatShotVerifications = new ArrayList<>();
	/** The crossbow was charged at the moment the fire use started. */
	private boolean combatShotWasCharged;
	/** Projectiles already present when the task started; never this task's. */
	private final Set<UUID> combatPreExistingProjectiles = new HashSet<>();
	private volatile String combatState = "idle";
	private volatile String combatEndReason = "idle";

	// --- Step 3 per-tick jump task ----------------------------------------------------------
	/**
	 * A one-block hop the host planned: the bot aims at the landing every tick,
	 * holds forward, and presses jump only once it is grounded and has passed
	 * the host's takeoff line. The host's 150 ms sampling cannot time a takeoff
	 * or correct a landing; this task does both a tick at a time and reports the
	 * real landing back.
	 */
	private volatile String jumpState = "idle";
	private volatile String jumpEndReason = "idle";
	private double jumpTargetX, jumpTargetY, jumpTargetZ;
	private double jumpTakeoffX, jumpTakeoffZ;
	private double jumpDirX, jumpDirZ;
	private double jumpTakeoffRadius = 0.35;
	private double jumpLandingRadius = 0.7;
	/** Dropping this far below the destination ends the attempt. */
	private double jumpFallTolerance = 1.2;
	private boolean jumpSprint;
	/** The flight overshoots the landing and no face past it stops the travel. */
	private boolean jumpBrake;
	private long jumpDeadline;
	private int jumpTicks;
	/**
	 * Ticks one task may run before it reports deadline.
	 *
	 * The cap scales with the submitted chain: the combo fixture submits a long
	 * chain, and a fixed 200 ticks (ten seconds) expired while the task was still
	 * settling on an iron-bar top (ailed / deadline, two edges done). The
	 * payload deadline from the host is the real bound; this is the safety net.
	 */
	private int jumpTickCap = 200;
	/** Takeoff re-arms allowed for one edge after a short landing. */
	private static final int JUMP_EDGE_RETRY_MAX = 3;
	private int jumpRetryCount;
	/** Ground friction speed retention, used by the settling stop estimate. */
	private static final double GROUND_SPEED_RETENTION = 0.546;
	/**
	 * A lone-pad landing is settled inside this centre error and speed.
	 *
	 * The window is wider than the 0.15/0.02 the offline recurrence suggested:
	 * a bang-bang controller with that window oscillated around the centre at
	 * walking speed and ran into the settle cap short of it (live run: 0.55
	 * short). 0.3 of a 0.5 half-width still leaves the whole footprint on the
	 * pad, and the host's goal tolerance accepts it.
	 */
	private static final double JUMP_SETTLE_CENTER = 0.3;
	private static final double JUMP_SETTLE_SPEED = 0.1;
	/**
	 * Ground input acceleration per tick and the support footprint margin.
	 *
	 * <p>The player half-width is about 0.3, so a centre 0.3 from a 1-block pad
	 * still hangs a corner over the edge; the candidates must keep the centre
	 * inside 0.2 of the block centre per world axis (Step 3 batch 2).
	 */
	private static final double GROUND_INPUT_ACCELERATION = 0.098;
	private static final double JUMP_SUPPORT_MARGIN = 0.2;
	/** Parking zone half-width per world axis: lone pads need the tight one. */
	private static final double JUMP_PARK_ZONE_TIGHT = 0.15;
	private static final double JUMP_PARK_ZONE_WIDE = 0.3;
	/** Candidate settle inputs as (forward, strafe-right) component signs. */
	private static final int[][] JUMP_SETTLE_CANDIDATES = {
			{ 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
			{ 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
	};
	private double jumpAlongError;
	private double jumpLateralError;
	private double jumpZone;
	private double jumpPredictedX, jumpPredictedZ;
	/**
	 * Batch 4 prediction record, taken at the takeoff decision.
	 *
	 * <p>Recorded before any key changes and never used to choose inputs yet:
	 * the predicted trails are aligned against the real per-tick trail first.
	 */
	private JsonObject jumpPredictionFirst;
	private JsonObject jumpPredictionNow;
	private JsonObject jumpPredictionWait;
	private final List<JsonObject> jumpRealTrail = new ArrayList<>();
	private int jumpRealTrailBase;
	/** Consecutive stable ticks that end a lone-pad settle. */
	private static final int JUMP_SETTLE_STABLE_TICKS = 2;
	/** Bound on centring a landing before handing over to the next edge. */
	private static final int JUMP_HANDOFF_SETTLE_TICKS = 12;
	/** Drift above which the takeoff waits for friction to stop the bot. */
	private static final double JUMP_TAKEOFF_MAX_SPEED = 0.06;
	/** Bounded waiting at the line before a takeoff is forced or refused. */
	private static final int JUMP_TAKEOFF_WAIT_MAX = 12;
	private int jumpWaitTicks;
	private int jumpStableTicks;
	/**
	 * State-machine facts for one jump edge.
	 *
	 * <p>Issuing the jump input is not a takeoff, and touching down is not a
	 * completion. Without these the task re-pressed jump the moment it was
	 * grounded again and turned a landing adjustment into another takeoff.
	 */
	private boolean jumpTakeoffIssued;
	private boolean jumpAirborneSeen;
	private boolean jumpTouchedDown;
	private volatile String jumpPhase = "idle";
	private volatile String jumpInput = "none";
	private double jumpPredictedStop;
	/**
	 * Ticks spent walking the last fraction to the target after touchdown.
	 *
	 * <p>The landing radius (0.7) lets the hop end beside the target; a planner
	 * node is the stand point (the block center), and the host's arrival radius
	 * (0.45) expects it. A landing that stops 0.5 away leaves the edge
	 * unconfirmed and the run stalls. The settle walks to the center a tick at a
	 * time, which the host's 150 ms polls cannot do, and is bounded so a target
	 * the bot cannot reach still ends with an honest landing report.
	 */
	private static final double JUMP_SETTLE_RADIUS = 0.3;
	private static final int JUMP_SETTLE_MAX_TICKS = 30;
	private int jumpSettleTicks;

	/**
	 * One jump edge with its full provenance (Step 3 batch 1).
	 *
	 * <p>`from`/`to` are the exact stand points of the plan's path steps,
	 * including their real support heights, so the mod can check its own start
	 * position and report which edge it executed.
	 */
	public static final class JumpEdge {
		public String edgeId = "";
		public double fromX, fromY, fromZ;
		public double targetX, targetY, targetZ;
		public double takeoffX, takeoffZ;
		public double dirX, dirZ;
		public boolean sprint;
		public boolean brake;
		public double takeoffRadius = 0.35;
		public double landingRadius = 0.7;
	}

	private String jumpEdgeId = "";
	/** `stop` at the destination, `continue` into the queued next edge. */
	private volatile String jumpIntent = "stop";
	private double jumpFromX, jumpFromY, jumpFromZ;
	/**
	 * Edges waiting behind the active one (Step 3 batch 3).
	 *
	 * A climbing chain is submitted as one list, so the mod hands over at every
	 * touchdown and the host never re-submits between hops (that round trip let
	 * the bot slide). The host tops the queue up only when it runs low.
	 */
	private final List<JumpEdge> jumpQueue = new ArrayList<>();
	private volatile String jumpNextEdgeId = "";
	/** Completed edges of the running task, as `edgeId@x,y,z|vx,vz`. */
	private final List<String> jumpCompletedEdges = new ArrayList<>();
	private volatile int jumpCompletedCount;

	/**
	 * Starts one climb: the first edge becomes active, the rest queue behind it.
	 *
	 * @param edges at least one edge; each is executed after the previous one
	 * reports its real touchdown, with no host round trip in between.
	 */
	public synchronized JsonObject startJump(List<JumpEdge> edges, String landingIntent, long deadlineMillis) {
		if (edges == null || edges.isEmpty())
			throw new IllegalArgumentException("startJump needs at least one edge");
		jumpQueue.clear();
		jumpQueue.addAll(edges);
		applyEdge(jumpQueue.remove(0));
		jumpNextEdgeId = jumpQueue.isEmpty() ? "" : jumpQueue.get(0).edgeId;
		jumpIntent = landingIntent == null || landingIntent.isEmpty() ? "stop" : landingIntent;
		jumpDeadline = deadlineMillis;
		jumpTicks = 0;
		jumpTickCap = Math.max(200, edges.size() * 200);
		jumpRetryCount = 0;
		jumpPredictedStop = 0;
		jumpCompletedEdges.clear();
		jumpCompletedCount = 0;
		jumpPredictionFirst = null;
		jumpPredictionNow = null;
		jumpPredictionWait = null;
		jumpWaitTicks = 0;
		jumpTakeoffTrace = "";
		jumpRealTrail.clear();
		jumpRealTrailBase = 0;
		jumpState = "running";
		jumpEndReason = "running";
		return jumpStatusJson();
	}

	/** Adds edges behind the active one; the host tops the queue up with this. */
	public synchronized JsonObject appendJump(List<JumpEdge> edges) {
		if (edges != null)
			jumpQueue.addAll(edges);
		jumpNextEdgeId = jumpQueue.isEmpty() ? "" : jumpQueue.get(0).edgeId;
		return jumpStatusJson();
	}

	/** Copies one edge into the active fields and resets the per-edge machine. */
	private void applyEdge(JumpEdge edge) {
		jumpRetryCount = 0;
		jumpEdgeId = edge.edgeId == null ? "" : edge.edgeId;
		jumpFromX = edge.fromX;
		jumpFromY = edge.fromY;
		jumpFromZ = edge.fromZ;
		jumpTargetX = edge.targetX;
		jumpTargetY = edge.targetY;
		jumpTargetZ = edge.targetZ;
		jumpTakeoffX = edge.takeoffX;
		jumpTakeoffZ = edge.takeoffZ;
		double length = Math.sqrt(edge.dirX * edge.dirX + edge.dirZ * edge.dirZ);
		jumpDirX = length < 1e-6 ? 0 : edge.dirX / length;
		jumpDirZ = length < 1e-6 ? 0 : edge.dirZ / length;
		jumpSprint = edge.sprint;
		jumpBrake = edge.brake;
		jumpTakeoffRadius = edge.takeoffRadius > 0 ? edge.takeoffRadius : 0.35;
		jumpLandingRadius = edge.landingRadius > 0 ? edge.landingRadius : 0.7;
		jumpSettleTicks = 0;
		jumpStableTicks = 0;
		jumpTakeoffIssued = false;
		jumpAirborneSeen = false;
		jumpTouchedDown = false;
		jumpPhase = "prepare";
		jumpInput = "none";
	}

	/** Records one edge's real landing, once, as the execution evidence. */
	private void recordEdgeCompletion(LocalPlayer p) {
		if (!jumpCompletedEdges.isEmpty()
				&& jumpCompletedEdges.get(jumpCompletedEdges.size() - 1).startsWith(jumpEdgeId + "@"))
			return;
		jumpCompletedEdges.add(jumpEdgeId + "@" + p.getX() + "," + p.getY() + "," + p.getZ()
				+ "|" + p.getDeltaMovement().x + "," + p.getDeltaMovement().z);
		jumpCompletedCount = jumpCompletedEdges.size();
	}

	public synchronized JsonObject cancelJump() {
		if (jumpState.equals("running")) {
			jumpState = "cancelled";
			jumpEndReason = "cancelled";
			stopAllMovement();
		}
		return jumpStatusJson();
	}

	public synchronized boolean isJumpActive() {
		return jumpState.equals("running");
	}

	public synchronized JsonObject jumpStatusJson() {
		JsonObject o = new JsonObject();
		o.addProperty("state", jumpState);
		o.addProperty("endReason", jumpEndReason);
		o.addProperty("ticks", jumpTicks);
		o.addProperty("phase", jumpPhase);
		o.addProperty("brakeRequested", jumpBrake);
		o.addProperty("effectiveInput", jumpInput);
		o.addProperty("predictedStop", jumpPredictedStop);
		o.addProperty("stableTicks", jumpStableTicks);
		o.addProperty("alongError", jumpAlongError);
		o.addProperty("lateralError", jumpLateralError);
		o.addProperty("parkZone", jumpZone);
		JsonObject predicted = new JsonObject();
		predicted.addProperty("x", jumpPredictedX);
		predicted.addProperty("z", jumpPredictedZ);
		o.add("predictedStopXZ", predicted);
		o.addProperty("edgeId", jumpEdgeId);
		o.addProperty("landingIntent", jumpIntent);
		o.addProperty("nextEdgeId", jumpNextEdgeId);
		o.addProperty("completedCount", jumpCompletedCount);
		JsonArray completed = new JsonArray();
		for (String record : jumpCompletedEdges)
			completed.add(record);
		o.add("completedEdges", completed);
		// Batch 4, record-only prediction evidence.
		if (jumpPredictionFirst != null)
			o.add("predictionFirst", jumpPredictionFirst);
		if (jumpPredictionNow != null)
			o.add("predictionNow", jumpPredictionNow);
		if (jumpPredictionWait != null)
			o.add("predictionWait", jumpPredictionWait);
		o.addProperty("waitTicks", jumpWaitTicks);
		o.addProperty("realTrailBase", jumpRealTrailBase);
		o.addProperty("takeoffTrace", jumpTakeoffTrace);
		JsonArray realTrail = new JsonArray();
		for (JsonObject entry : jumpRealTrail)
			realTrail.add(entry);
		o.add("realTrail", realTrail);
		JsonObject from = new JsonObject();
		from.addProperty("x", jumpFromX);
		from.addProperty("y", jumpFromY);
		from.addProperty("z", jumpFromZ);
		o.add("from", from);
		JsonObject to = new JsonObject();
		to.addProperty("x", jumpTargetX);
		to.addProperty("y", jumpTargetY);
		to.addProperty("z", jumpTargetZ);
		o.add("to", to);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			o.addProperty("isSprinting", p.isSprinting());
			o.addProperty("support", BuiltInRegistries.BLOCK.getKey(p.getBlockStateOn().getBlock()).toString());
			JsonObject motion = new JsonObject();
			motion.addProperty("x", p.getDeltaMovement().x);
			motion.addProperty("z", p.getDeltaMovement().z);
			o.add("motion", motion);
			JsonObject position = new JsonObject();
			position.addProperty("x", p.getX());
			position.addProperty("y", p.getY());
			position.addProperty("z", p.getZ());
			o.add("position", position);
			o.addProperty("onGround", p.onGround());
			double dx = jumpTargetX - p.getX();
			double dz = jumpTargetZ - p.getZ();
			o.addProperty("distance", Math.sqrt(dx * dx + dz * dz));
			double passed = (p.getX() - jumpTakeoffX) * jumpDirX + (p.getZ() - jumpTakeoffZ) * jumpDirZ;
			o.addProperty("takeoffPassed", passed >= -jumpTakeoffRadius);
		}
		return o;
	}

	private void finishJump(String state, String reason) {
		jumpState = state;
		jumpEndReason = reason;
		stopAllMovement();
	}

	/**
	 * One tick of the jump task.
	 *
	 * <p>Landing is the only success: grounded, inside the landing radius and at
	 * the destination level. Falling more than the tolerance below the
	 * destination, the deadline, or leaving the world each fail the task.
	 */
	private void tickJump(Minecraft mc, LocalPlayer p) {
		jumpTicks++;
		if (p == null) {
			finishJump("failed", "no_player");
			return;
		}
		recordRealTrail(p);
		if (System.currentTimeMillis() > jumpDeadline) {
			finishJump("failed", "deadline");
			return;
		}
		double dx = jumpTargetX - p.getX();
		double dz = jumpTargetZ - p.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		boolean onTarget = horizontal <= jumpLandingRadius
				&& Math.abs(p.getY() - jumpTargetY) <= 0.35;
		if (onTarget && p.onGround()) {
			recordEdgeCompletion(p);
			// Two-edge handoff: the next edge is already known, so no host round
			// trip is needed between the hops (that gap let the bot slide even
			// with every key released). A sharp turn with speed still carrying in
			// the old direction is the case that slides a one-block chain off its
			// edge: kill that velocity first, then hand over. A straight
			// continuation keeps its speed.
			if (!jumpQueue.isEmpty() && jumpIntent.equals("continue")) {
				jumpPhase = "handoff";
				// Hand over only from a settled stand: the next edge is one block
				// wide, and a handoff at 0.6 off centre with residual velocity
				// slid the bot off the chain (live chain run). The settle below
				// runs in this edge's frame, bounded, and keeps the next edge
				// queued until the bot is parked.
				if (settleJump(p, true))
					return;
				JumpEdge next = jumpQueue.remove(0);
				jumpNextEdgeId = jumpQueue.isEmpty() ? "" : jumpQueue.get(0).edgeId;
				applyEdge(next);
				return;
			}
			jumpPhase = "settle";
			settleJump(p, false);
			return;
		}
		if (p.getY() < jumpTargetY - jumpFallTolerance) {
			finishJump("failed", "fell");
			return;
		}
		if (p.onGround() && jumpTouchedDown && Math.abs(p.getY() - jumpTargetY) > 0.35) {
			// Touched down off the target level: the hop fell short (live combo
			// run: she landed at the iron-bar base, and the task pressed forward
			// into the post until its deadline, 800 ticks of nothing). Re-arm the
			// same edge for a bounded number of attempts, then fail so the host
			// replans instead of hanging.
			jumpRetryCount++;
			if (jumpRetryCount > JUMP_EDGE_RETRY_MAX) {
				finishJump("failed", "short_landing");
				return;
			}
			jumpTakeoffIssued = false;
			jumpAirborneSeen = false;
			jumpTouchedDown = false;
			jumpWaitTicks = 0;
			jumpPhase = "prepare";
			return;
		}
		if (jumpTicks > jumpTickCap) {
			finishJump("failed", "deadline");
			return;
		}
		// Aim every tick: a rotation that lags the takeoff is what sent
		// host-side hops in the previous direction.
		aimAtPoint(p, jumpTargetX, jumpTargetY, jumpTargetZ);
		double passed = (p.getX() - jumpTakeoffX) * jumpDirX + (p.getZ() - jumpTakeoffZ) * jumpDirZ;
		// State machine: prepare -> takeoff -> airborne -> adjust -> done.
		// Issuing jump is its own state; only an observed liftoff leaves it, the
		// jump key is released there, and a touchdown never re-arms it.
		if (!p.onGround())
			jumpAirborneSeen = true;
		else if (jumpAirborneSeen)
			jumpTouchedDown = true;
		jumpPhase = jumpTouchedDown ? "adjust" : jumpAirborneSeen ? "airborne" : "prepare";
		if (jumpTakeoffIssued && !jumpAirborneSeen) {
			// The input was issued but no liftoff was observed (a wall in the
			// way): keep it issued for this edge only, never a second press.
			jumpHeld = true;
			return;
		}
		if (!jumpTakeoffIssued && p.onGround()) {
			// The takeoff gate is the prediction itself. A fixed speed gate is a
			// false-negative source: the audited successful chain takeoff carried
			// 0.096 of run-up (0.068 lateral to the block-centre line) and that
			// speed was what slid it around the pillar corner, yet it exceeds the
			// old 0.06 threshold. No candidate means "no plan found in this state
			// and search range"; the caller replans, it never disables the edge.
			if (passed >= -jumpTakeoffRadius) {
				// Batch 4 decision, re-evaluated every tick (the expert's
				// "execute one tick, predict again"): jump when the takeoff is
				// predicted to land on a support at the destination level, and
				// hold while no candidate can. A refusal is reported as
				// no_safe_takeoff so the caller replans; it is never a verdict
				// that the edge is impassable.
				// Candidate search over the takeoff position and timing. The
				// direct jump is the first candidate; the others reposition or
				// hold for a few ticks first, which is what lets a hop that the
				// adjacent pillar clips line up beside it.
				JumpPredictor.Result now = JumpPredictor.simulate(p, 1, 0, true, jumpTargetX, jumpTargetZ, 24, 8, 0, 0, 0, jumpSprint);
				JumpPredictor.Result wait = JumpPredictor.simulate(p, 1, 0, true, jumpTargetX, jumpTargetZ, 24, 8, 1, 0, 0, jumpSprint);
				JumpPredictor.Result best = now;
				int bestPreTicks = 0;
				double bestPreForward = 0;
				double bestPreStrafe = 0;
				double bestScore = takeoffScore(now, 0);
				if (jumpPredictionNow == null) {
					jumpPredictionNow = JumpPredictor.toJson(now, 26);
					jumpPredictionFirst = jumpPredictionNow;
					jumpPredictionWait = JumpPredictor.toJson(wait, 26);
					jumpRealTrailBase = jumpTicks;
					debugTakeoff(p, now, wait, takeoffScore(now, 0), takeoffScore(wait, 0));
				}
				for (int[] candidate : JUMP_TAKEOFF_CANDIDATES) {
					int preTicks = candidate[0];
					double preForward = candidate[1];
					double preStrafe = candidate[2];
					JumpPredictor.Result result = JumpPredictor.simulate(
							p, 1, 0, true, jumpTargetX, jumpTargetZ, 24, 8, preTicks, preForward, preStrafe, jumpSprint);
					double score = takeoffScore(result, preTicks);
					if (!Double.isFinite(score))
						continue;
					if (!Double.isFinite(bestScore) || score < bestScore - 1e-6) {
						bestScore = score;
						best = result;
						bestPreTicks = preTicks;
						bestPreForward = preForward;
						bestPreStrafe = preStrafe;
					}
				}
				if (!Double.isFinite(bestScore)) {
					jumpWaitTicks++;
					if (jumpWaitTicks > JUMP_TAKEOFF_WAIT_MAX) {
						finishJump("failed", "no_safe_takeoff");
						return;
					}
					holdPosition(p);
					return;
				}
				if (bestPreTicks > 0) {
					// Reposition this tick (the search runs again next tick, so a
					// changed state cannot leave the bot committed). The wait cap
					// applies to every reposition, not only to multi-tick ones:
					// with the cap skipped for one-tick candidates the search
					// walked the bot forward across the pad without ever
					// committing to a jump (live chain run: 22 waits, fell).
					jumpWaitTicks++;
					if (jumpWaitTicks > JUMP_TAKEOFF_WAIT_MAX) {
						finishJump("failed", "no_safe_takeoff");
						return;
					}
					jumpPredictionNow = JumpPredictor.toJson(best, 26);
					applyRepositionInput(p, bestPreForward, bestPreStrafe);
					return;
				}
				// The executed takeoff's own prediction replaces the first
				// snapshot, so the evidence matches the flight that happened.
				jumpPredictionNow = JumpPredictor.toJson(best, 26);
				jumpTakeoffIssued = true;
				jumpPhase = "takeoff";
				pressHorizontal(true);
				jumpHeld = true;
				return;
			}
		}
		// The flight keeps its forward press: releasing mid-air barely changes
		// the velocity and made hops land short of their pad. All stop control
		// happens after the first touchdown, where friction and the back key
		// actually work (the settle above).
		pressHorizontal(true);
	}

	/**
	 * Two-dimensional settling (Step 3 batch 2).
	 *
	 * <p>The landing is controlled in the edge's own frame: the along axis is the
	 * flight direction and the lateral axis its perpendicular, so a residual
	 * velocity from the previous hop is seen even when the heading is already
	 * correct. Each tick predicts where a release would stop the bot, and if the
	 * coast does not rest inside the parking zone it enumerates the nine
	 * horizontal inputs, rejects any whose predicted rest leaves the landing
	 * support, and executes the one that stops closest to the centre for exactly
	 * one tick before deciding again. Touched down, stopped and ready-to-continue
	 * stay separate verdicts.
	 */
	private boolean settleJump(LocalPlayer p, boolean advanceOnParked) {
		double centreX = Math.floor(jumpTargetX) + 0.5;
		double centreZ = Math.floor(jumpTargetZ) + 0.5;
		// A handoff only needs the bot safely inside the block (wide zone); the
		// final stop is what the tight zone is for.
		jumpZone = advanceOnParked || !jumpBrake ? JUMP_PARK_ZONE_WIDE : JUMP_PARK_ZONE_TIGHT;
		double normalX = -jumpDirZ;
		double normalZ = jumpDirX;
		double vx = p.getDeltaMovement().x;
		double vz = p.getDeltaMovement().z;
		double speed = Math.hypot(vx, vz);
		jumpAlongError = (p.getX() - centreX) * jumpDirX + (p.getZ() - centreZ) * jumpDirZ;
		jumpLateralError = (p.getX() - centreX) * normalX + (p.getZ() - centreZ) * normalZ;
		jumpSettleTicks++;

		double stopSpeed = advanceOnParked ? JUMP_SETTLE_SPEED : jumpBrake ? 0.02 : JUMP_SETTLE_SPEED;
		boolean parked = Math.abs(p.getX() - centreX) <= jumpZone
				&& Math.abs(p.getZ() - centreZ) <= jumpZone
				&& speed <= stopSpeed;
		if (parked) {
			jumpStableTicks++;
			if (jumpStableTicks >= JUMP_SETTLE_STABLE_TICKS) {
				stopAllMovement();
				if (advanceOnParked)
					return false;
				finishJump("done", "landed");
				return true;
			}
			releaseHorizontal(p);
			return true;
		}
		jumpStableTicks = 0;
		int cap = advanceOnParked ? JUMP_HANDOFF_SETTLE_TICKS : JUMP_SETTLE_MAX_TICKS;
		// The settle is bounded: a controller that cannot centre the bot must not
		// report failure for a landing that is already on the pad, so the cap
		// accepts the measured position when it is inside the host's tolerance.
		// A grounded, slow bot on any support also counts: on a thin iron-bar
		// top the centring corrections can stall, and the live combo task sat in
		// this phase until its deadline with two edges done. The next edge's
		// takeoff prediction validates the spot anyway.
		if (jumpSettleTicks >= cap
				&& (Math.hypot(jumpAlongError, jumpLateralError) <= 0.5
					|| (p.onGround() && speed <= 0.05))) {
			stopAllMovement();
			if (advanceOnParked)
				return false;
			finishJump("done", "landed");
			return true;
		}

		// Coasting: where a release comes to rest.
		double predictedX = p.getX() + vx / (1 - GROUND_SPEED_RETENTION);
		double predictedZ = p.getZ() + vz / (1 - GROUND_SPEED_RETENTION);
		double bestX = predictedX;
		double bestZ = predictedZ;
		double bestScore = stopDistance(predictedX, predictedZ, centreX, centreZ);
		boolean bestOnSupport = onLandingSupport(predictedX, predictedZ, centreX, centreZ);
		int bestForward = 0;
		int bestStrafe = 0;
		for (int[] candidate : JUMP_SETTLE_CANDIDATES) {
			int forward = candidate[0];
			int strafe = candidate[1];
			double inputX = forward * jumpDirX + strafe * normalX;
			double inputZ = forward * jumpDirZ + strafe * normalZ;
			double length = Math.hypot(inputX, inputZ);
			if (length > 1e-6) {
				inputX /= length;
				inputZ /= length;
			}
			// One tick of input, then release: this tick's travel is the
			// post-input speed, and the remaining travel decays by q per tick.
			double candidateX = p.getX() + (vx + GROUND_INPUT_ACCELERATION * inputX) / (1 - GROUND_SPEED_RETENTION);
			double candidateZ = p.getZ() + (vz + GROUND_INPUT_ACCELERATION * inputZ) / (1 - GROUND_SPEED_RETENTION);
			if (!onLandingSupport(candidateX, candidateZ, centreX, centreZ))
				continue;
			double score = stopDistance(candidateX, candidateZ, centreX, centreZ);
			if (!bestOnSupport || score < bestScore) {
				bestOnSupport = true;
				bestScore = score;
				bestForward = forward;
				bestStrafe = strafe;
				bestX = candidateX;
				bestZ = candidateZ;
			}
		}
		jumpPredictedX = bestX;
		jumpPredictedZ = bestZ;
		jumpPredictedStop = Math.hypot(bestX - centreX, bestZ - centreZ);
		aimAlongFlight(p);
		applySettleInput(bestForward, bestStrafe);
		return true;
	}

	/**
	 * Records the real state for this tick, phase included.
	 *
	 * The entry is taken before this tick's keys take effect, so a consumer
	 * aligns it with the prediction's first simulated tick by one offset.
	 */
	private void recordRealTrail(LocalPlayer p) {
		if (jumpRealTrail.size() >= 48)
			return;
		JsonObject entry = new JsonObject();
		entry.addProperty("tick", jumpTicks);
		entry.addProperty("x", p.getX());
		entry.addProperty("y", p.getY());
		entry.addProperty("z", p.getZ());
		entry.addProperty("vx", p.getDeltaMovement().x);
		entry.addProperty("vz", p.getDeltaMovement().z);
		entry.addProperty("onGround", p.onGround());
		entry.addProperty("phase", jumpPhase);
		jumpRealTrail.add(entry);
	}

	/**
	 * Scores one predicted takeoff option; not finite means "do not use".
	 *
	 * A safe option touches down after the takeoff and still rests on a floor at
	 * the destination level, as close to the handoff target as possible. A
	 * landing below the target (the bot would stand against the step instead of
	 * climbing it) or one that slides off its far edge scores unsafe. Everything
	 * else — including a refusal — is a statement about this state and this
	 * search range, not about the edge.
	 *
	 * <p>Repositioning carries a small cost so a plan that is already safe jumps
	 * now. Without it the search kept preferring "one more forward tick then
	 * jump" — each tick improved the predicted landing — and the bot walked the
	 * whole pad instead of committing (live chain run: 22 waits, walked off).
	 */
	private double takeoffScore(JumpPredictor.Result prediction, int preTicks) {
		if (prediction.landTick < 0 || !prediction.staysOnSupport)
			return Double.NaN;
		if (Math.abs(prediction.restY - jumpTargetY) > 0.35)
			return Double.NaN;
		return prediction.restToTarget + JUMP_REPOSITION_COST * preTicks;
	}

	/** Per-tick cost of repositioning before the takeoff, in blocks. */
	private static final double JUMP_REPOSITION_COST = 0.03;

	/** Compact takeoff-decision trace for the live diagnosis. */
	private void debugTakeoff(LocalPlayer p, JumpPredictor.Result now, JumpPredictor.Result wait, double nowScore, double waitScore) {
		jumpTakeoffTrace = "now=" + (Double.isFinite(nowScore) ? String.format(java.util.Locale.ROOT, "%.3f", nowScore) : "unsafe")
				+ " wait=" + (Double.isFinite(waitScore) ? String.format(java.util.Locale.ROOT, "%.3f", waitScore) : "unsafe")
				+ " pos=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", p.getX(), p.getY(), p.getZ())
				+ " nowLand=" + (now.landTick >= 0 ? String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", now.landX, now.landY, now.landZ) : "none")
				+ " waitLand=" + (wait.landTick >= 0 ? String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", wait.landX, wait.landY, wait.landZ) : "none");
	}

	private volatile String jumpTakeoffTrace = "";

	/** Takeoff candidates as {preTicks, preForward, preStrafe}. */
	private static final int[][] JUMP_TAKEOFF_CANDIDATES = {
			{ 1, 0, 0 }, { 1, 1, 0 }, { 2, 1, 0 },
			{ 1, 1, 1 }, { 1, 1, -1 }, { 2, 1, 1 }, { 2, 1, -1 },
			{ 1, -1, 0 },
	};

	/** Applies one candidate's repositioning input, view on the target. */
	private void applyRepositionInput(LocalPlayer p, double forward, double strafe) {
		int forwardSign = forward > 0 ? 1 : forward < 0 ? -1 : 0;
		int strafeSign = strafe > 0 ? 1 : strafe < 0 ? -1 : 0;
		aimAtPoint(p, jumpTargetX, jumpTargetY, jumpTargetZ);
		fwd = forwardSign > 0;
		back = forwardSign < 0;
		left = strafeSign < 0;
		right = strafeSign > 0;
		sprint = false;
		jumpHeld = false;
		jumpInput = inputName(forwardSign, strafeSign);
	}

	/**
	 * Stops the bot without walking toward an unprotected edge.
	 *
	 * A refusal or a wait that drifts off a one-block source is how the bot fell
	 * on the pillar hop while the prediction had already refused the takeoff:
	 * the counter-thrust faces the drift, so the stop happens in place.
	 */
	private void holdPosition(LocalPlayer p) {
		double vx = p.getDeltaMovement().x;
		double vz = p.getDeltaMovement().z;
		if (Math.hypot(vx, vz) <= JUMP_TAKEOFF_MAX_SPEED) {
			releaseHorizontal(p);
			return;
		}
		aimAtPoint(p, p.getX() + vx, p.getY(), p.getZ() + vz);
		pressHorizontal(false);
	}

	private static double stopDistance(double x, double z, double centreX, double centreZ) {
		return Math.hypot(x - centreX, z - centreZ);
	}

	/** True when the feet centre stays inside the landing block. */
	private static boolean onLandingSupport(double x, double z, double centreX, double centreZ) {
		return Math.abs(x - centreX) <= 0.5 - JUMP_SUPPORT_MARGIN + 1e-6
				&& Math.abs(z - centreZ) <= 0.5 - JUMP_SUPPORT_MARGIN + 1e-6;
	}

	/** Applies one candidate input, with the view already on the flight. */
	private void applySettleInput(int forward, int strafe) {
		fwd = forward > 0;
		back = forward < 0;
		left = strafe < 0;
		right = strafe > 0;
		sprint = false;
		jumpHeld = false;
		jumpInput = inputName(forward, strafe);
	}

	private static String inputName(int forward, int strafe) {
		String along = forward > 0 ? "forward" : forward < 0 ? "back" : "";
		String side = strafe > 0 ? "right" : strafe < 0 ? "left" : "";
		if (along.isEmpty())
			return side.isEmpty() ? "none" : side;
		if (side.isEmpty())
			return along;
		return along + "-" + side;
	}

	/** Forward + sprint as one key set, recorded for the status trace. */
	private void pressHorizontal(boolean forward) {
		fwd = forward;
		back = !forward;
		left = right = false;
		sprint = forward && jumpSprint;
		jumpHeld = false;
		jumpInput = forward ? "forward" : "back";
	}

	/** Releases every horizontal key and lets friction stop the bot. */
	private void releaseHorizontal(LocalPlayer p) {
		fwd = back = left = right = false;
		sprint = false;
		jumpHeld = false;
		jumpInput = "none";
	}

	/** Keeps the view on the flight direction so `back` opposes the velocity. */
	private void aimAlongFlight(LocalPlayer p) {
		aimAtPoint(p, p.getX() + jumpDirX, p.getY(), p.getZ() + jumpDirZ);
	}

	// --- MC-4e riptide movement task --------------------------------------------------------
	private enum RiptidePhase { IDLE, CHARGING, FLYING }
	/** Vanilla riptide launches after roughly half a second of use. */
	private static final int RIPTIDE_CHARGE_TICKS = 12;
	/** Flight is tracked until the player stabilises or this bound expires (~5s). */
	private static final int RIPTIDE_FLIGHT_TIMEOUT_TICKS = 100;
	private RiptidePhase riptidePhase = RiptidePhase.IDLE;
	private double riptideTargetX, riptideTargetY, riptideTargetZ;
	private int riptideTicks;
	private int riptideDurabilityBefore;
	private int riptideDurabilityAfter;
	private double riptideFromX, riptideFromY, riptideFromZ;
	private double riptideToX, riptideToY, riptideToZ;
	private boolean riptideHasPosition;
	private volatile String riptideState = "idle";
	private volatile String riptideEndReason = "idle";
	private volatile String riptideUnmet;

	// --- public control surface (called from handlers, on the render thread) ----------------

	public synchronized void setMovement(Boolean f, Boolean b, Boolean l, Boolean r, Boolean jump, Boolean sn, Boolean sp) {
		if (f != null) fwd = f;
		if (b != null) back = b;
		if (l != null) left = l;
		if (r != null) right = r;
		if (jump != null) jumpHeld = jump;
		if (sn != null) sneak = sn;
		if (sp != null) sprint = sp;
	}

	public synchronized void stopAllMovement() {
		fwd = back = left = right = jumpHeld = sneak = sprint = false;
		jumpOnceTicks = 0;
	}

	public synchronized void jumpOnce() {
		jumpOnceTicks = Math.max(jumpOnceTicks, 2);
	}

	public synchronized void startMining(BlockPos pos, Direction face) {
		this.miningPos = pos;
		this.miningFace = face;
	}

	public synchronized void stopMining() {
		this.miningPos = null;
	}

	public synchronized void startNavigation(List<BlockPos> path, BlockPos target, double reachRadius, boolean sprint, long deadlineMillis) {
		startNavigation(path, target, reachRadius, sprint, deadlineMillis, null);
	}

	public synchronized void startNavigation(List<BlockPos> path, BlockPos target, double reachRadius, boolean sprint, long deadlineMillis, String commandId) {
		this.path = path;
		this.pathIndex = 0;
		this.navTarget = target;
		this.reachRadius = reachRadius;
		this.navSprint = sprint;
		this.navDeadline = deadlineMillis;
		this.navCommandId = commandId;
		this.lastDist = Double.MAX_VALUE;
		this.stuckTicks = 0;
		this.navState = "navigating";
		this.navEndReason = "navigating";
		this.navEndedAt = 0;
		this.navEndDistance = Double.NaN;
		this.navEndHasPosition = false;
	}

	/** Command id of the navigation in flight, or null. Used to tag reflex preemption. */
	public synchronized String currentNavigationCommandId() {
		return navCommandId;
	}

	/** Reflex hold on the use key; wins over the normal key release path. */
	public synchronized void setUseHeld(boolean held) {
		useHeld = held;
	}

	public synchronized void stopNavigation(String reason) {
		captureEnd(reason);
		this.path = null;
		this.navState = reason;
		fwd = false;
		sprint = false;
	}

	/**
	 * Starts the per-tick ranged weapon task (MC-4d).
	 *
	 * <p>The pre-flight checks run on the render thread before any {@code use}: the
	 * weapon must be selectable and, for bow/crossbow, an arrow must be in the
	 * inventory. Either failure ends the task with {@code weapon_unavailable} or
	 * {@code no_ammo} and no use is started.
	 *
	 * @return the task state after the pre-flight check ({@code running} or a terminal reason).
	 */
	public synchronized JsonObject startCombat(String weapon, double tx, double ty, double tz,
			String targetUuid, int maxShots, int chargeTicks, Minecraft mc) {
		stopCombatInternal("superseded");
		// MC-4e: starting a weapon task supersedes an in-flight riptide charge.
		if (riptidePhase != RiptidePhase.IDLE) {
			LocalPlayer holder = mc != null ? mc.player : null;
			if (holder != null) {
				mc.options.keyUse.setDown(false);
				holder.stopUsingItem();
			}
			finishRiptide("cancelled", "superseded", null);
		}
		combatWeapon = weapon;
		combatTargetX = tx;
		combatTargetY = ty;
		combatTargetZ = tz;
		combatTargetUuid = targetUuid;
		combatMaxShots = Math.max(1, Math.min(maxShots, 16));
		combatChargeTicks = Math.max(1, Math.min(chargeTicks, 200));
		combatTrackTicks = 0;
		combatShotsFired = 0;
		combatHoldTicks = 0;
		combatCharged = false;
		combatLoadTicks = 0;
		combatNextShot = 0;
		combatProjectiles.clear();
		combatTridentUuid = null;
		combatTridentCountAfter = -1;
		combatTridentReturned = false;
		combatReturnTicks = 0;
		combatLastShotTick = 0;
		combatAimHasPosition = false;
		combatAimSource = "pinned";
		combatAimX = tx;
		combatAimY = ty;
		combatAimZ = tz;
		combatLeadEnabled = McpFabric.config() == null || McpFabric.config().combatAimLead;
		combatShotPending = false;
		combatShotPendingTicks = 0;
		combatAmmoBefore = 0;
		combatShotVerifiedBy = null;
		combatShotVerifications.clear();
		combatPreExistingProjectiles.clear();
		if (mc.level != null) {
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof AbstractArrow || entity instanceof ThrownTrident || entity instanceof FireworkRocketEntity)
					combatPreExistingProjectiles.add(entity.getUUID());
			}
		}
		combatEndReason = "";

		LocalPlayer p = mc.player;
		if (p == null) {
			combatState = "done";
			combatEndReason = "no_player";
			return combatStatusJson();
		}
		if (!selectWeapon(mc, p, weapon)) {
			combatState = "done";
			combatEndReason = "weapon_unavailable";
			return combatStatusJson();
		}
		if (!weapon.equals("trident") && findArrowSlot(p) < 0
				&& !(weapon.equals("crossbow") && hasFireworkAmmo(p))) {
			combatState = "done";
			combatEndReason = "no_ammo";
			return combatStatusJson();
		}
		// A loaded crossbow starts at the aim/fire beat; an empty one loads first.
		combatCharged = weapon.equals("crossbow") && isCrossbowCharged(p);
		if (weapon.equals("crossbow") && !combatCharged)
			prepareFireworkOffhand(mc, p);
		combatPhase = weapon.equals("crossbow") && !combatCharged ? CombatPhase.LOADING : CombatPhase.CHARGING;
		combatState = "running";
		combatEndReason = "running";
		return combatStatusJson();
	}

	/** Requests a state-aware abort of the weapon task (see mc-4d-spec D3). */
	public synchronized JsonObject cancelCombat(Minecraft mc) {
		LocalPlayer p = mc != null ? mc.player : null;
		// MC-4e: the shared abort path also stops an in-flight riptide charge.
		// `stopUsingItem` never triggers the release that propels the player.
		if (riptidePhase != RiptidePhase.IDLE) {
			if (p != null) {
				mc.options.keyUse.setDown(false);
				p.stopUsingItem();
			}
			riptidePhase = RiptidePhase.IDLE;
			riptideState = "cancelled";
			riptideEndReason = "cancelled";
		}
		if (combatPhase == CombatPhase.IDLE && combatState.equals("idle")) {
			return combatStatusJson();
		}
		// A projectile already left the world: keep that fact, stop future shots.
		boolean projectileLeft = combatShotsFired > 0;
		// Bow/trident mid-charge abort through stopUsingItem so no release fires;
		// a crossbow load abort keeps whatever charge was already applied.
		if (p != null && (combatPhase == CombatPhase.CHARGING || combatPhase == CombatPhase.HOLDING
				|| combatPhase == CombatPhase.LOADING)) {
			mc.options.keyUse.setDown(false);
			p.stopUsingItem();
		}
		combatPhase = CombatPhase.IDLE;
		combatState = "cancelled";
		combatCharged = combatWeapon != null && combatWeapon.equals("crossbow") && p != null && isCrossbowCharged(p);
		combatEndReason = projectileLeft ? "cancelled_after_shot" : "cancelled";
		return combatStatusJson();
	}

	public synchronized JsonObject combatStatusJson() {
		JsonObject o = new JsonObject();
		o.addProperty("state", combatState);
		if (combatWeapon != null) o.addProperty("weapon", combatWeapon);
		o.addProperty("shotsFired", combatShotsFired);
		JsonArray uuids = new JsonArray();
		for (UUID u : combatProjectiles) uuids.add(u.toString());
		o.add("projectileUuids", uuids);
		JsonArray verifications = new JsonArray();
		for (String v : combatShotVerifications) verifications.add(v);
		o.add("shotVerifiedBy", verifications);
		o.addProperty("endReason", combatEndReason);
		if (combatNextShot > 0) {
			JsonObject last = new JsonObject();
			last.addProperty("shot", combatNextShot);
			if (combatShotVerifiedBy != null)
				last.addProperty("verifiedBy", combatShotVerifiedBy);
			if (!combatProjectiles.isEmpty()) {
				last.addProperty("projectileUuid", combatProjectiles.get(combatProjectiles.size() - 1).toString());
			}
			o.add("lastShot", last);
		}
		if (combatWeapon != null && combatWeapon.equals("crossbow")) {
			o.addProperty("charged", combatCharged);
		}
		// B-08: the projectile each carried ranged weapon would use next, so the
		// host can pick the ammo-specific ballistic profile before the task.
		LocalPlayer statusPlayer = Minecraft.getInstance().player;
		if (statusPlayer != null)
			o.add("projectiles", weaponProjectiles(statusPlayer));
		if (combatWeapon != null && combatWeapon.equals("trident") && combatShotsFired > 0) {
			o.addProperty("returned", combatTridentReturned);
		}
		if (combatAimHasPosition) {
			JsonObject aim = new JsonObject();
			aim.addProperty("x", combatAimX);
			aim.addProperty("y", combatAimY);
			aim.addProperty("z", combatAimZ);
			o.add("aimTarget", aim);
		}
		// F-24: the lead the aim actually used and where it came from, so a
		// moving-target shot can be audited without a debugger.
		JsonObject lead = new JsonObject();
		lead.addProperty("source", combatLeadSource);
		if (combatLeadVelocity != null) {
			lead.addProperty("x", combatLeadVelocity.x);
			lead.addProperty("y", combatLeadVelocity.y);
			lead.addProperty("z", combatLeadVelocity.z);
		}
		lead.addProperty("ownSpeed", combatLeadOwnSpeed);
		lead.addProperty("vehicleFound", combatLeadVehicleFound);
		lead.addProperty("vehicleSpeed", combatLeadVehicleSpeed);
		lead.addProperty("measuredSpeed", combatLeadMeasuredSpeed);
		o.add("aimLead", lead);
		// F-26: the state the release fired with and the arrow the server
		// actually launched, so an under-leading shot can be traced to the aim
		// inputs, the charge level or the rotation the server had seen.
		if (combatReleaseCaptured) {
			JsonObject release = new JsonObject();
			release.addProperty("holdTicks", combatReleaseHoldTicks);
			release.addProperty("chargeTicks", combatReleaseChargeTicks);
			release.addProperty("flightTicks", combatReleaseFlightTicks);
			release.addProperty("leadSource", combatReleaseLeadSource);
			JsonObject aimAtRelease = new JsonObject();
			aimAtRelease.addProperty("x", combatReleaseAimX);
			aimAtRelease.addProperty("y", combatReleaseAimY);
			aimAtRelease.addProperty("z", combatReleaseAimZ);
			release.add("aim", aimAtRelease);
			JsonObject targetAtRelease = new JsonObject();
			targetAtRelease.addProperty("x", combatReleaseTargetX);
			targetAtRelease.addProperty("y", combatReleaseTargetY);
			targetAtRelease.addProperty("z", combatReleaseTargetZ);
			release.add("target", targetAtRelease);
			JsonObject leadVelocity = new JsonObject();
			leadVelocity.addProperty("x", combatReleaseLeadX);
			leadVelocity.addProperty("y", combatReleaseLeadY);
			leadVelocity.addProperty("z", combatReleaseLeadZ);
			release.add("leadVelocity", leadVelocity);
			JsonObject arrow = new JsonObject();
			if (!Double.isNaN(combatReleaseArrowX)) {
				arrow.addProperty("x", combatReleaseArrowX);
				arrow.addProperty("y", combatReleaseArrowY);
				arrow.addProperty("z", combatReleaseArrowZ);
				arrow.addProperty("speed", Math.sqrt(combatReleaseArrowX * combatReleaseArrowX
						+ combatReleaseArrowY * combatReleaseArrowY + combatReleaseArrowZ * combatReleaseArrowZ));
			}
			arrow.addProperty("observedAtTrackTick", combatReleaseArrowTick);
			release.add("arrow", arrow);
			o.add("release", release);
		}
		o.addProperty("aimSource", combatAimSource);
		if (combatLastShotTick > 0) {
			o.addProperty("lastShotTick", combatLastShotTick);
		}
		return o;
	}

	/** True while the weapon task is mid-flight (used by status and guards). */
	public synchronized boolean isCombatActive() {
		return combatPhase != CombatPhase.IDLE;
	}

	private void stopCombatInternal(String reason) {
		if (combatPhase == CombatPhase.IDLE && !combatState.equals("running"))
			return;
		combatPhase = CombatPhase.IDLE;
		combatState = combatShotsFired > 0 ? "cancelled" : "done";
		combatEndReason = reason;
	}

	/**
	 * Starts the per-tick riptide movement task (MC-4e).
	 *
	 * <p>The client-side conditions are checked before any use: a riptide trident
	 * must be selectable and the player must be in water or rain. Either failure
	 * ends the task with {@code riptide_unavailable} and the unmet condition name,
	 * and no use is started.
	 *
	 * @return the task state ({@code running} or a terminal reason).
	 */
	public synchronized JsonObject startRiptide(double tx, double ty, double tz, Minecraft mc) {
		riptidePhase = RiptidePhase.IDLE;
		riptideState = "idle";
		riptideEndReason = "idle";
		riptideUnmet = null;
		riptideTicks = 0;
		riptideHasPosition = false;
		riptideDurabilityBefore = 0;
		riptideDurabilityAfter = 0;
		riptideTargetX = tx;
		riptideTargetY = ty;
		riptideTargetZ = tz;

		LocalPlayer p = mc.player;
		if (p == null) {
			finishRiptide("done", "no_player", null);
			return riptideStatusJson();
		}
		if (!p.isInWaterOrRain()) {
			finishRiptide("done", "riptide_unavailable", "not_in_water_or_rain");
			return riptideStatusJson();
		}
		if (!selectRiptideTrident(mc, p)) {
			finishRiptide("done", "riptide_unavailable", "no_riptide_trident");
			return riptideStatusJson();
		}
		// Supersede any weapon task: only one special-use task may own input.
		stopCombatInternal("superseded");
		aimAtPoint(p, tx, ty, tz);
		riptideDurabilityBefore = p.getMainHandItem().getDamageValue();
		riptideFromX = p.getX();
		riptideFromY = p.getY();
		riptideFromZ = p.getZ();
		riptideToX = riptideFromX;
		riptideToY = riptideFromY;
		riptideToZ = riptideFromZ;
		riptideHasPosition = true;
		riptidePhase = RiptidePhase.CHARGING;
		riptideState = "running";
		riptideEndReason = "running";
		return riptideStatusJson();
	}

	/** Aborts the riptide task through the use-abort path; never releases the throw. */
	public synchronized JsonObject cancelRiptide(Minecraft mc) {
		if (riptidePhase != RiptidePhase.IDLE) {
			LocalPlayer p = mc != null ? mc.player : null;
			if (p != null) {
				mc.options.keyUse.setDown(false);
				p.stopUsingItem();
			}
			riptidePhase = RiptidePhase.IDLE;
			riptideState = "cancelled";
			riptideEndReason = "cancelled";
		}
		return riptideStatusJson();
	}

	public synchronized JsonObject riptideStatusJson() {
		JsonObject o = new JsonObject();
		o.addProperty("state", riptideState);
		o.addProperty("endReason", riptideEndReason);
		if (riptideUnmet != null) o.addProperty("unmet", riptideUnmet);
		o.addProperty("ticks", riptideTicks);
		if (riptideHasPosition) {
			JsonObject from = new JsonObject();
			from.addProperty("x", riptideFromX);
			from.addProperty("y", riptideFromY);
			from.addProperty("z", riptideFromZ);
			o.add("from", from);
			JsonObject to = new JsonObject();
			to.addProperty("x", riptideToX);
			to.addProperty("y", riptideToY);
			to.addProperty("z", riptideToZ);
			o.add("to", to);
			JsonObject displacement = new JsonObject();
			displacement.addProperty("x", riptideToX - riptideFromX);
			displacement.addProperty("y", riptideToY - riptideFromY);
			displacement.addProperty("z", riptideToZ - riptideFromZ);
			o.add("displacement", displacement);
			o.addProperty("distance", Math.sqrt(
					Math.pow(riptideToX - riptideFromX, 2)
							+ Math.pow(riptideToY - riptideFromY, 2)
							+ Math.pow(riptideToZ - riptideFromZ, 2)));
		}
		o.addProperty("durabilityBefore", riptideDurabilityBefore);
		o.addProperty("durabilityAfter", riptideDurabilityAfter);
		return o;
	}

	/** True while the riptide task is mid-flight (used by guards and status). */
	public synchronized boolean isRiptideActive() {
		return riptidePhase != RiptidePhase.IDLE;
	}

	private void finishRiptide(String state, String reason, String unmet) {
		riptidePhase = RiptidePhase.IDLE;
		riptideState = state;
		riptideEndReason = reason;
		riptideUnmet = unmet;
		// F-25: never leave the charge key held after the task.
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.options != null)
			mc.options.keyUse.setDown(false);
	}

	/**
	 * P1 cleanup: release every control surface the bot may hold.
	 *
	 * Called from lifecycle callbacks and the heartbeat watchdog. Movement
	 * intent clears immediately; key releases and item-use stops are applied
	 * on the next client tick, so the player is free within two game ticks
	 * even when the caller runs on a network thread.
	 */
	public synchronized void clearAll(String reason) {
		stopAllMovement();
		if (jumpState.equals("running")) {
			jumpState = "cancelled";
			jumpEndReason = reason;
		}
		miningPos = null;
		stopMiningRequested = true;
		releaseUseRequested = true;
		captureEnd(reason);
		path = null;
		navState = reason;
		navCommandId = null;
		// MC-4d: a preemption/death/disconnect terminates the weapon task as
		// well. `releaseUseRequested` above already stops the vanilla use on the
		// next tick, so an in-flight charge cannot fire after this.
		if (combatPhase != CombatPhase.IDLE) {
			combatPhase = CombatPhase.IDLE;
			combatState = "cancelled";
			combatEndReason = reason;
		}
		// MC-4e: an in-flight riptide charge is aborted the same way. The
		// `releaseUseRequested` flag above stops the vanilla use on the next
		// tick, so the charge cannot release into a throw after this.
		if (riptidePhase != RiptidePhase.IDLE) {
			riptidePhase = RiptidePhase.IDLE;
			riptideState = "cancelled";
			riptideEndReason = reason;
		}
	}

	/** Whether any control surface is currently held by the bot. */
	public synchronized boolean isDriving() {
		return fwd || back || left || right || jumpHeld || sneak || sprint
				|| jumpOnceTicks > 0 || path != null || miningPos != null
				|| jumpState.equals("running")
				|| combatPhase != CombatPhase.IDLE || riptidePhase != RiptidePhase.IDLE
				|| releaseUseRequested || stopMiningRequested;
	}

	/** Records the terminal distance/position for the status report. */
	private void captureEnd(String reason) {
		navEndReason = reason;
		navEndedAt = System.currentTimeMillis();
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && navTarget != null) {
			navEndDistance = p.position().distanceTo(Vec3.atBottomCenterOf(navTarget));
			navEndHasPosition = true;
			navEndX = p.getX();
			navEndY = p.getY();
			navEndZ = p.getZ();
		} else {
			navEndDistance = Double.NaN;
			navEndHasPosition = false;
		}
	}

	public synchronized JsonObject statusJson() {
		JsonObject o = new JsonObject();
		boolean active = path != null;
		o.addProperty("active", active);
		o.addProperty("state", navState);
		if (navTarget != null) {
			JsonObject t = new JsonObject();
			t.addProperty("x", navTarget.getX());
			t.addProperty("y", navTarget.getY());
			t.addProperty("z", navTarget.getZ());
			o.add("target", t);
		}
		if (active) {
			o.addProperty("remainingNodes", Math.max(0, path.size() - pathIndex));
			o.addProperty("deadline", navDeadline);
		}
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && navTarget != null) {
			o.addProperty("distance", p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)));
		}
		// P2 terminal report: the last end reason with the measured position.
		o.addProperty("endReason", navEndReason);
		if (navEndedAt > 0) {
			o.addProperty("endedAt", navEndedAt);
		}
		if (!Double.isNaN(navEndDistance)) {
			o.addProperty("finalDistance", navEndDistance);
		}
		if (navEndHasPosition) {
			JsonObject fp = new JsonObject();
			fp.addProperty("x", navEndX);
			fp.addProperty("y", navEndY);
			fp.addProperty("z", navEndZ);
			o.add("finalPosition", fp);
		}
		return o;
	}

	// --- tick --------------------------------------------------------------------------------

	public void onClientTick(Minecraft mc) {
		LocalPlayer p = mc.player;

		synchronized (this) {
			// P1 cleanup actions requested by lifecycle callbacks/threads.
			if (releaseUseRequested) {
				mc.options.keyUse.setDown(false);
				if (p != null) p.stopUsingItem();
				releaseUseRequested = false;
			}
			if (stopMiningRequested) {
				if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
				stopMiningRequested = false;
			}

			if (p == null) {
				if (drivingKeys) {
					releaseKeys(mc.options);
					drivingKeys = false;
				}
				return;
			}

			if (path != null) {
				steer(p);
			}
			// Step 3 arbitration: while the jump task runs it owns both the
			// movement input and the view. A later controller (the weapon aim)
			// must not rotate the bot away from the edge it just aligned to.
			boolean jumpRunning = jumpState.equals("running");
			if (jumpRunning) {
				tickJump(mc, p);
			}
			// MC-4d: the weapon task runs after movement so aiming wins the look
			// for this tick, and before the use-key hold below.
			if (combatPhase != CombatPhase.IDLE && !jumpRunning) {
				tickCombat(mc, p);
			}
			// MC-4e: the riptide task is mutually exclusive with the weapon task.
			if (riptidePhase != RiptidePhase.IDLE) {
				tickRiptide(mc, p);
			}
			boolean driving = fwd || back || left || right || jumpHeld || sneak || sprint || jumpOnceTicks > 0 || path != null;
			if (driving) {
				applyKeys(mc.options);
				drivingKeys = true;
			} else if (drivingKeys) {
				// Release any keys the bot forced down instead of continuing to stomp on them
				// every tick — without this, real keyboard input can never move the player
				// again once the bot has issued any movement command.
				releaseKeys(mc.options);
				drivingKeys = false;
			}
			if (jumpOnceTicks > 0) jumpOnceTicks--;			tickMining(mc);
			// Reflex use-key hold wins over the release path above.
			if (useHeld)
				mc.options.keyUse.setDown(true);
		}
	}

	private void applyKeys(Options o) {
		o.keyUp.setDown(fwd);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		o.keyJump.setDown(jumpHeld || jumpOnceTicks > 0);
	}

	private void releaseKeys(Options o) {
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyShift.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
	}

	private void tickMining(Minecraft mc) {
		if (miningPos == null) return;
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null || mc.level == null) {
			miningPos = null;
			return;
		}
		if (mc.level.getBlockState(miningPos).isAir()) {
			gm.stopDestroyBlock();
			miningPos = null;
			return;
		}
		gm.continueDestroyBlock(miningPos, miningFace);
	}

	private void steer(LocalPlayer p) {
		if (System.currentTimeMillis() > navDeadline) {
			stopNavigationInternal("deadline");
			return;
		}
		Vec3 tgt = Vec3.atBottomCenterOf(navTarget);
		if (p.position().distanceTo(tgt) <= Math.max(reachRadius, 0.6)) {
			stopNavigationInternal("reached");
			return;
		}
		if (pathIndex >= path.size()) {
			// P2: an exhausted path is not a reached target. The final distance
			// decides between a hit inside the tolerance and a bounded failure.
			double dist = p.position().distanceTo(tgt);
			stopNavigationInternal(dist <= Math.max(reachRadius, 0.6) ? "reached" : "path_exhausted");
			return;
		}

		BlockPos node = path.get(pathIndex);
		double cx = node.getX() + 0.5;
		double cz = node.getZ() + 0.5;
		double dx = cx - p.getX();
		double dz = cz - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);

		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);

		fwd = true;
		back = left = right = false;
		sprint = navSprint;

		if (node.getY() > p.getY() + 0.4) {
			jumpOnceTicks = Math.max(jumpOnceTicks, 1);
		}
		if (horiz < 0.55) {
			pathIndex++;
		}

		double dist = p.position().distanceTo(tgt);
		if (dist < lastDist - 0.01) {
			stuckTicks = 0;
			lastDist = dist;
		} else if (++stuckTicks > 60) {
			stuckTicks = 0;
			jumpOnceTicks = Math.max(jumpOnceTicks, 1);
		}
	}

	private void stopNavigationInternal(String reason) {
		captureEnd(reason);
		path = null;
		navState = reason;
		fwd = false;
		sprint = false;
	}

	// --- MC-4d weapon task -----------------------------------------------------------------

	/**
	 * One tick of the weapon state machine.
	 *
	 * <p>{@code LOADING} (crossbow only) runs a bounded load use and waits for the
	 * charged component; {@code CHARGING} aims and starts the vanilla use;
	 * {@code HOLDING} releases after {@code chargeTicks}; {@code TRACKING} scans
	 * for this task's projectile and, for a trident, its return.
	 */
	private void tickCombat(Minecraft mc, LocalPlayer p) {
		switch (combatPhase) {
			case LOADING -> tickCombatLoad(mc, p);
			case CHARGING -> tickCombatCharge(mc, p);
			case HOLDING -> tickCombatHold(mc, p);
			case TRACKING -> tickCombatTrack(mc, p);
			default -> { }
		}
	}

	private void tickCombatLoad(Minecraft mc, LocalPlayer p) {
		aimAtTarget(mc, p);
		combatLoadTicks++;
		// F-25/F-28: hold the use key so the vanilla input path cannot release
		// early, then release deliberately after the charge duration. A crossbow
		// keeps a held use open forever (CrossbowItem#useOnRelease is true) and
		// only loads inside releaseUsing, so a player's release is part of the
		// load, not an interruption of it.
		boolean releasing = combatLoadTicks % CROSSBOW_LOAD_CYCLE_TICKS > CROSSBOW_LOAD_HOLD_TICKS;
		if (releasing) {
			if (!combatLoadReleased) {
				mc.options.keyUse.setDown(false);
				mc.gameMode.releaseUsingItem(p);
				combatLoadReleased = true;
			}
		}
		else {
			combatLoadReleased = false;
			mc.options.keyUse.setDown(true);
			if (!p.isUsingItem())
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		}
		if (isCrossbowCharged(p)) {
			combatCharged = true;
			combatPhase = CombatPhase.CHARGING;
			combatHoldTicks = 0;
			return;
		}
		// The load is a bounded window; a crossbow that never charges fails honestly.
		if (combatLoadTicks > CROSSBOW_LOAD_TIMEOUT_TICKS) {
			combatPhase = CombatPhase.IDLE;
			combatState = "done";
			combatEndReason = "load_timeout";
		}
	}

	private void tickCombatCharge(Minecraft mc, LocalPlayer p) {
		// The task fires at most maxShots; for a trident keep tracking after the
		// last throw so the return/pickup report is not lost.
		if (combatNextShot >= combatMaxShots) {
			if (combatWeapon != null && combatWeapon.equals("trident")) {
				combatTrackTicks = 0;
				combatPhase = CombatPhase.TRACKING;
			}
			else {
				finishCombat("done", "done");
			}
			return;
		}
		aimAtTarget(mc, p);
		boolean crossbow = combatWeapon != null && combatWeapon.equals("crossbow");
		if (crossbow) {
			// A loaded crossbow fires on a single use. The aim is set in this
			// same tick, so hold two ticks first: the server still has the
			// previous rotation until the movement packet lands.
			combatHoldTicks++;
			if (combatHoldTicks < 2)
				return;
			if (!isCrossbowCharged(p)) {
				// The charge was lost (item switched or an earlier load failed):
				// load again instead of "firing" an empty crossbow.
				combatLoadTicks = 0;
				combatPhase = CombatPhase.LOADING;
				return;
			}
			// Never fire through another player; the crossbow stays loaded.
			if (friendlyInLine(p, mc)) {
				finishCombat("done", "friendly_blocked");
				return;
			}
			combatShotWasCharged = true;
			combatAmmoBefore = countAmmo(p);
			sendAimRotation(mc, p);
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			p.swing(InteractionHand.MAIN_HAND);
			combatShotPending = true;
			combatShotPendingTicks = 0;
			combatShotVerifiedBy = null;
			combatHoldTicks = 0;
			combatPhase = CombatPhase.HOLDING;
			return;
		}
		// Bow/trident: start the charge use and hold it for chargeTicks.
		// F-25: the key is held so the vanilla input path cannot release the use
		// each tick while the window has no screen open.
		mc.options.keyUse.setDown(true);
		if (!p.isUsingItem())
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		combatHoldTicks = 0;
		combatPhase = CombatPhase.HOLDING;
	}

	private void tickCombatHold(Minecraft mc, LocalPlayer p) {
		aimAtTarget(mc, p);
		boolean crossbow = combatWeapon != null && combatWeapon.equals("crossbow");
		if (!crossbow) {
			combatHoldTicks++;
			// F-25: keep the use key held for the whole charge; the release path
			// below clears it together with the release itself.
			mc.options.keyUse.setDown(true);
			// Re-assert the use while charging; key state alone raises no click.
			if (!p.isUsingItem() && combatHoldTicks % 2 == 0)
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (combatHoldTicks < combatChargeTicks)
				return;
			// Never release through another player: abort the charge instead.
			if (friendlyInLine(p, mc)) {
				mc.options.keyUse.setDown(false);
				p.stopUsingItem();
				finishCombat("done", "friendly_blocked");
				return;
			}
			// Normal release: fires the bow/trident projectile. The ammo count is
			// captured before the release so a fast arrow that hits within a tick
			// or two can still be verified by the ammo delta.
			combatAmmoBefore = countAmmo(p);
			captureReleaseSnapshot(mc, p);
			sendAimRotation(mc, p);
			mc.options.keyUse.setDown(false);
			mc.gameMode.releaseUsingItem(p);
			combatShotPending = true;
			combatShotPendingTicks = 0;
			combatShotVerifiedBy = null;
		}
		beginTracking(mc, p);
	}

	private void beginTracking(Minecraft mc, LocalPlayer p) {
		combatTrackTicks = 0;
		combatPhase = CombatPhase.TRACKING;
	}

	private void tickCombatTrack(Minecraft mc, LocalPlayer p) {
		combatTrackTicks++;
		// A release counts as a shot once the projectile is observed OR the ammo
		// count actually decreased. Fast arrows hit and disappear within a tick
		// or two, so projectile-only accounting under-reports real hits.
		if (combatShotPending) {
			combatShotPendingTicks++;
			Entity projectile = findOwnProjectile(mc, p);
			String verifiedBy = null;
			if (projectile != null) {
				verifiedBy = "projectile";
			}
			else if (combatWeapon != null && combatWeapon.equals("crossbow")
					&& combatShotWasCharged && !isCrossbowCharged(p)) {
				// A crossbow consumes its bolt at load time, so the ammo delta
				// happens before the shot. The charge clearing is the shot.
				verifiedBy = "charge-cleared";
			}
			else if (combatShotPendingTicks >= 2 && countAmmo(p) < combatAmmoBefore) {
				verifiedBy = "ammo";
			}
			else if (combatShotPendingTicks > 10) {
				combatShotPending = false;
				finishCombat("done", "no_projectile");
				return;
			}
			if (verifiedBy != null) {
				combatShotPending = false;
				combatNextShot++;
				combatShotsFired = combatNextShot;
				combatShotVerifiedBy = verifiedBy;
				combatShotVerifications.add(verifiedBy);
				if (projectile != null) {
					combatProjectiles.add(projectile.getUUID());
					// F-26: the first motion the client sees on its own arrow is the
					// direction and speed the server actually launched it with.
					if (Double.isNaN(combatReleaseArrowX)) {
						Vec3 arrowMotion = projectile.getDeltaMovement();
						combatReleaseArrowX = arrowMotion.x;
						combatReleaseArrowY = arrowMotion.y;
						combatReleaseArrowZ = arrowMotion.z;
						combatReleaseArrowTick = combatTrackTicks;
					}
					if (combatWeapon != null && combatWeapon.equals("trident"))
						combatTridentUuid = projectile.getUUID();
				}
				if (combatWeapon != null && combatWeapon.equals("trident"))
					combatTridentCountAfter = countTridents(p);
				combatLastShotTick = mc.level != null ? mc.level.getGameTime() : 0L;
				// Next beat: tickCombatCharge stops at maxShots.
				combatHoldTicks = 0;
				combatPhase = CombatPhase.CHARGING;
			}
			return;
		}

		// Trident return tracking: only this throw's trident coming back counts.
		// The old "any trident in the inventory" test reported true while a
		// backup trident was carried; the comparison is against the inventory
		// right after the throw and the fired projectile uuid.
		if (combatWeapon != null && combatWeapon.equals("trident")
				&& (combatTridentUuid != null || combatTridentCountAfter >= 0)) {
			combatReturnTicks++;
			if (tridentReturned(mc, p)) {
				combatTridentReturned = true;
				combatTridentUuid = null;
				finishCombat("done", "returned");
				return;
			}
			if (combatReturnTicks > 200) {
				boolean projectileGone = combatTridentUuid != null && !projectileStillPresent(mc, combatTridentUuid);
				finishCombat("done", projectileGone ? "lost" : "return_pending");
				return;
			}
			return;
		}

		if (combatNextShot >= combatMaxShots) {
			finishCombat("done", "done");
		} else {
			// No projectile observed for this beat: report honestly and stop.
			finishCombat("done", "no_projectile");
		}
	}

	private void finishCombat(String state, String reason) {
		combatPhase = CombatPhase.IDLE;
		combatState = state;
		combatEndReason = reason;
		// F-25: the use key is held while charging; never leave it held after the
		// task, or a focused client would keep using the held item.
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.options != null)
			mc.options.keyUse.setDown(false);
	}

	// --- MC-4e riptide task ----------------------------------------------------------------

	private void tickRiptide(Minecraft mc, LocalPlayer p) {
		switch (riptidePhase) {
			case CHARGING -> tickRiptideCharge(mc, p);
			case FLYING -> tickRiptideFlight(mc, p);
			default -> { }
		}
	}

	private void tickRiptideCharge(Minecraft mc, LocalPlayer p) {
		riptideTicks++;
		riptideToX = p.getX();
		riptideToY = p.getY();
		riptideToZ = p.getZ();
		// Conditions can lapse mid-charge (out of water, trident switched). The
		// abort path stops the use without the release that would propel the
		// player, so no accidental throw happens.
		if (!p.isInWaterOrRain()) {
			mc.options.keyUse.setDown(false);
			p.stopUsingItem();
			finishRiptide("done", "riptide_unavailable", "not_in_water_or_rain");
			return;
		}
		aimAtPoint(p, riptideTargetX, riptideTargetY, riptideTargetZ);
		// F-25: same held-key rule as the bow charge.
		mc.options.keyUse.setDown(true);
		if (!p.isUsingItem())
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (p.getTicksUsingItem() >= RIPTIDE_CHARGE_TICKS) {
			// Normal release: this is the vanilla riptide launch.
			sendAimRotation(mc, p);
			mc.options.keyUse.setDown(false);
			mc.gameMode.releaseUsingItem(p);
			riptideDurabilityAfter = p.getMainHandItem().getDamageValue();
			riptideFromX = p.getX();
			riptideFromY = p.getY();
			riptideFromZ = p.getZ();
			riptideHasPosition = true;
			riptideTicks = 0;
			riptidePhase = RiptidePhase.FLYING;
		}
	}

	private void tickRiptideFlight(Minecraft mc, LocalPlayer p) {
		riptideTicks++;
		riptideToX = p.getX();
		riptideToY = p.getY();
		riptideToZ = p.getZ();
		boolean stable = p.getDeltaMovement().length() < 0.05 && (p.onGround() || p.isInWater());
		if (stable) {
			finishRiptide("done", "launched", null);
			return;
		}
		if (riptideTicks > RIPTIDE_FLIGHT_TIMEOUT_TICKS)
			finishRiptide("done", "timeout", null);
	}

	/** Selects a hotbar/main riptide trident, moving a main stack into the hotbar when needed. */
	private boolean selectRiptideTrident(Minecraft mc, LocalPlayer p) {
		if (isRiptideTrident(p.getMainHandItem()))
			return true;
		int hotbar = -1;
		for (int slot = 0; slot <= 8; slot++) {
			if (isRiptideTrident(p.getInventory().getItem(slot))) {
				hotbar = slot;
				break;
			}
		}
		if (hotbar < 0) {
			int main = -1;
			for (int slot = 9; slot <= 35; slot++) {
				if (isRiptideTrident(p.getInventory().getItem(slot))) {
					main = slot;
					break;
				}
			}
			if (main < 0)
				return false;
			int dest = -1;
			for (int slot = 0; slot <= 8; slot++) {
				if (p.getInventory().getItem(slot).isEmpty()) {
					dest = slot;
					break;
				}
			}
			if (dest < 0)
				return false;
			MultiPlayerGameMode gm = mc.gameMode;
			if (gm == null)
				return false;
			int containerId = p.inventoryMenu.containerId;
			containerClick(gm, containerId, toMenuSlot(main), p);
			containerClick(gm, containerId, toMenuSlot(dest), p);
			hotbar = dest;
		}
		//? if >=1.21.5 {
		p.getInventory().setSelectedSlot(hotbar);
		//?} else
		p.getInventory().selected = hotbar;
		sendCarriedItem(mc, hotbar);
		return true;
	}

	private boolean isRiptideTrident(ItemStack stack) {
		if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("minecraft:trident"))
			return false;
		for (var entry : stack.getEnchantments().entrySet()) {
			var key = entry.getKey().unwrapKey().orElse(null);
			if (key != null && key.location().toString().equals("minecraft:riptide"))
				return true;
		}
		return false;
	}

	private void aimAtPoint(LocalPlayer p, double tx, double ty, double tz) {
		double dx = tx - p.getX();
		double dy = ty - p.getEyeY();
		double dz = tz - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	/**
	 * True when another player stands in the shot corridor to the pinned target.
	 *
	 * The corridor is a cylinder around the eye-to-aim line (radius 1.0) and is
	 * checked against the same predicted point the shot uses, so a lead shot and
	 * the friend check never disagree. The pinned target is skipped, so an
	 * intentional player target is not blocked.
	 */
	private boolean friendlyInLine(LocalPlayer p, Minecraft mc) {
		if (mc.level == null)
			return false;
		Vec3 eye = p.getEyePosition();
		double dx = combatAimX - eye.x;
		double dy = combatAimY - eye.y;
		double dz = combatAimZ - eye.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0E-4)
			return false;
		double ux = dx / length, uy = dy / length, uz = dz / length;
		for (var other : mc.level.players()) {
			if (other.getUUID().equals(p.getUUID()))
				continue;
			if (combatTargetUuid != null && other.getUUID().toString().equals(combatTargetUuid))
				continue;
			Vec3 point = other.position().add(0.0, other.getBbHeight() * 0.5, 0.0);
			double ox = point.x - eye.x, oy = point.y - eye.y, oz = point.z - eye.z;
			double along = ox * ux + oy * uy + oz * uz;
			if (along < 1.0 || along > length + 1.5)
				continue;
			double px = ox - along * ux, py = oy - along * uy, pz = oz - along * uz;
			if (Math.sqrt(px * px + py * py + pz * pz) <= 1.0)
				return true;
		}
		return false;
	}

	/**
	 * Recomputes the aim point from the pinned target's current position.
	 *
	 * The target is re-resolved by uuid every tick, so a target that stops after
	 * moving is aimed where it now stands instead of where it stood when the shot
	 * started. A bounded linear lead adds the estimated flight time times the
	 * entity velocity; {@code combatLeadEnabled} turns that off. When the entity
	 * leaves render range the previous point is kept and {@code combatAimSource}
	 * records the reason.
	 */
	private void aimAtTarget(Minecraft mc, LocalPlayer p) {
		Entity target = currentTargetEntity(mc);
		if (target != null) {
			double baseX = target.getX();
			double baseY = target.getY() + target.getBbHeight() * 0.5;
			double baseZ = target.getZ();
			double tx = baseX;
			double ty = baseY;
			double tz = baseZ;
			double speed = combatLaunchSpeed(p);
			if (combatLeadEnabled && speed > 0) {
				Vec3 vel = combatAimVelocity(mc, target);
				double flightTicks = Mth.clamp(p.getEyePosition().distanceTo(new Vec3(baseX, baseY, baseZ)) / speed,
						COMBAT_MIN_FLIGHT_TICKS, COMBAT_MAX_FLIGHT_TICKS);
				// Two lead passes: the first uses the direct distance estimate, the second the
				// flight time the ballistic solve just produced. Both extend the lead by the
				// fixed client interpolation window, because the position the target is read
				// from already trails the server by that much (F-26). The aim applied below
				// solves once more at the final lead point, so the shot and the recorded aim
				// agree.
				for (int pass = 0; pass < 2; pass++) {
					double leadTicks = flightTicks + COMBAT_CLIENT_INTERP_TICKS;
					tx = baseX + Mth.clamp(vel.x * leadTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
					ty = baseY + Mth.clamp(vel.y * leadTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
					tz = baseZ + Mth.clamp(vel.z * leadTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
					flightTicks = Mth.clamp(solveBallisticPitch(p, tx, ty, tz, speed).flightTicks(),
							COMBAT_MIN_FLIGHT_TICKS, COMBAT_MAX_FLIGHT_TICKS);
				}
				combatLastFlightTicks = flightTicks + COMBAT_CLIENT_INTERP_TICKS;
			}
			combatAimX = tx;
			combatAimY = ty;
			combatAimZ = tz;
			combatAimHasPosition = true;
			combatAimSource = "fresh";
			aimBallisticAtPoint(p, combatAimX, combatAimY, combatAimZ);
			return;
		}
		if (combatAimHasPosition) {
			combatAimSource = "last_seen";
			aimBallisticAtPoint(p, combatAimX, combatAimY, combatAimZ);
			return;
		}
		// Never seen the entity in render range: fall back to the pinned point.
		combatAimX = combatTargetX;
		combatAimY = combatTargetY;
		combatAimZ = combatTargetZ;
		combatAimSource = "pinned";
		aimBallisticAtPoint(p, combatAimX, combatAimY, combatAimZ);
	}

	/**
	 * Sends the current rotation before a release or use.
	 *
	 * The vanilla client sends its rotation with the movement packet at the
	 * start of the tick, but the combat aim is applied at the end of the tick.
	 * The release packet carries no rotation, so the server launches the
	 * projectile with the previous tick's aim (F-26: arrows left with about half
	 * the computed lead on a moving target). Sending the rotation here puts it
	 * ahead of the release packet on the same connection, in order.
	 */
	private void sendAimRotation(Minecraft mc, LocalPlayer p) {
		if (mc.getConnection() == null)
			return;
		mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
	}

	/**
	 * Captures the exact state the release fires with (F-26).
	 *
	 * The arrow's direction and speed are set by the server from the rotation
	 * and charge it has seen, so the release-time aim, the target's position,
	 * the lead inputs and the vanilla charge ticks have to be compared against
	 * the arrow that actually spawns. Nothing here changes behaviour.
	 */
	private void captureReleaseSnapshot(Minecraft mc, LocalPlayer p) {
		combatReleaseCaptured = true;
		combatReleaseAimX = combatAimX;
		combatReleaseAimY = combatAimY;
		combatReleaseAimZ = combatAimZ;
		Entity target = currentTargetEntity(mc);
		if (target != null) {
			combatReleaseTargetX = target.getX();
			combatReleaseTargetY = target.getY();
			combatReleaseTargetZ = target.getZ();
		}
		if (combatLeadVelocity != null) {
			combatReleaseLeadX = combatLeadVelocity.x;
			combatReleaseLeadY = combatLeadVelocity.y;
			combatReleaseLeadZ = combatLeadVelocity.z;
		}
		combatReleaseLeadSource = combatLeadSource;
		combatReleaseChargeTicks = p.getTicksUsingItem();
		combatReleaseHoldTicks = combatHoldTicks;
		combatReleaseFlightTicks = combatLastFlightTicks;
		combatReleaseArrowX = Double.NaN;
		combatReleaseArrowY = Double.NaN;
		combatReleaseArrowZ = Double.NaN;
		combatReleaseArrowTick = -1;
	}

	/**
	 * Motion used for the aim lead, in blocks per tick.
	 *
	 * A remote passenger reports a small residual in its own delta movement
	 * instead of the vehicle's speed, so the measured position change since the
	 * previous aim tick leads (F-24: a lead read from the rider alone aimed at
	 * where the target used to be). The client-side vehicle motion is a second
	 * source for the first tick, before a measurement exists. Both measured and
	 * vehicle values are noisy on a remote rider; measurement matched the live
	 * rail cart (0.40 blocks/tick) while the vehicle read spiked to 0.61, so the
	 * measurement wins whenever it is meaningful. A stationary target measures
	 * as at rest and gets no lead.
	 */
	private Vec3 combatAimVelocity(Minecraft mc, Entity target) {
		Vec3 own = target.getDeltaMovement();
		Entity root = target.getRootVehicle();
		Vec3 vehicle = root != null && root != target ? root.getDeltaMovement() : null;
		long now = mc.level != null ? mc.level.getGameTime() : 0L;
		boolean tracked = combatMotionUuid != null && combatMotionUuid.equals(target.getUUID())
				&& combatMotionTick != Long.MIN_VALUE && now > combatMotionTick;
		Vec3 measured = null;
		if (tracked) {
			double ticks = now - combatMotionTick;
			measured = new Vec3((target.getX() - combatMotionX) / ticks, (target.getY() - combatMotionY) / ticks,
					(target.getZ() - combatMotionZ) / ticks);
		}
		Vec3 choice;
		if (measured != null && measured.lengthSqr() > COMBAT_MIN_MOTION_SQR) {
			choice = measured;
			combatLeadSource = "measured";
		}
		else if (vehicle != null && vehicle.lengthSqr() > COMBAT_MIN_MOTION_SQR) {
			choice = vehicle;
			combatLeadSource = "vehicle";
		}
		else {
			choice = own;
			combatLeadSource = "own";
		}
		combatLeadVelocity = choice;
		combatLeadOwnSpeed = own.length();
		combatLeadVehicleFound = vehicle != null;
		combatLeadVehicleSpeed = vehicle != null ? vehicle.length() : -1.0;
		combatLeadMeasuredSpeed = measured != null ? measured.length() : -1.0;
		combatMotionUuid = target.getUUID();
		combatMotionTick = now;
		combatMotionX = target.getX();
		combatMotionY = target.getY();
		combatMotionZ = target.getZ();
		return choice;
	}

	/**
	 * Launch speed for the charge the held weapon will release at, or zero when unknown.
	 *
	 * The bow follows the vanilla power curve for {@code combatChargeTicks} (a release past the
	 * full draw keeps power 1); a loaded crossbow reports its projectile's speed; the trident
	 * throws at its fixed speed. An unknown weapon keeps the straight-aim path.
	 */
	private double combatLaunchSpeed(LocalPlayer p) {
		if (combatWeapon == null)
			return 0;
		if (combatWeapon.equals("bow")) {
			double ratio = Math.min(Math.max(combatChargeTicks, 0), BALLISTIC_BOW_FULL_CHARGE_TICKS)
					/ (double) BALLISTIC_BOW_FULL_CHARGE_TICKS;
			double power = Math.min((ratio * ratio + 2.0 * ratio) / 3.0, 1.0);
			return power * BALLISTIC_BOW_MAX_SPEED;
		}
		if (combatWeapon.equals("crossbow"))
			return crossbowFireworkLoaded(p) ? BALLISTIC_CROSSBOW_FIREWORK_SPEED : BALLISTIC_CROSSBOW_ARROW_SPEED;
		if (combatWeapon.equals("trident"))
			return BALLISTIC_TRIDENT_SPEED;
		return 0;
	}

	/**
	 * Downward acceleration for the current weapon's projectile.
	 *
	 * B-08: a crossbow firework rocket flies straight after launch (its thrust is
	 * not modelled), so it must not inherit the arrow's gravity drop.
	 */
	private double combatGravity(LocalPlayer p) {
		if (combatWeapon != null && combatWeapon.equals("crossbow") && crossbowFireworkLoaded(p))
			return 0.0;
		return BALLISTIC_GRAVITY;
	}

	/** True when the loaded crossbow holds a firework rocket, whose shot speed is 1.6. */
	private boolean crossbowFireworkLoaded(LocalPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty())
			return false;
		ChargedProjectiles charged = held.get(DataComponents.CHARGED_PROJECTILES);
		if (charged == null)
			return false;
		for (ItemStack projectile : charged.getItems()) {
			if (projectile.is(Items.FIREWORK_ROCKET))
				return true;
		}
		return false;
	}

	/**
	 * Aims at a point with the launch pitch that lands the projectile there.
	 *
	 * The yaw is the plain horizontal direction; the pitch comes from
	 * {@link #solveBallisticPitch}. An unknown weapon (speed 0) falls back to the straight aim
	 * that pointed at the target itself.
	 */
	private void aimBallisticAtPoint(LocalPlayer p, double tx, double ty, double tz) {
		double speed = combatLaunchSpeed(p);
		if (speed <= 0) {
			aimAtPoint(p, tx, ty, tz);
			return;
		}
		float yaw = (float) ((Mth.atan2(tz - p.getZ(), tx - p.getX()) * (180.0 / Math.PI)) - 90.0);
		float pitch = (float) solveBallisticPitch(p, tx, ty, tz, speed).pitch();
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	/**
	 * Solves the launch pitch for the aim point with a coarse-to-fine candidate search.
	 *
	 * Candidates are simulated with the audited arrow model; the winner is the earliest hit,
	 * then the flattest arc, and the closest approach on a miss. The returned flight time lets
	 * the caller converge its lead instead of estimating it with {@code distance / speed}.
	 */
	private BallisticAim solveBallisticPitch(LocalPlayer p, double tx, double ty, double tz, double speed) {
		double yawDeg = (Math.atan2(tz - p.getZ(), tx - p.getX()) * (180.0 / Math.PI)) - 90.0;
		double pitchStep = (BALLISTIC_PITCH_MAX_DEG - BALLISTIC_PITCH_MIN_DEG) / BALLISTIC_COARSE_STEPS;
		BallisticShot best = null;
		for (int index = 0; index <= BALLISTIC_COARSE_STEPS; index++) {
			BallisticShot shot = simulateBallisticShot(p, tx, ty, tz, speed, yawDeg, BALLISTIC_PITCH_MIN_DEG + index * pitchStep);
			if (best == null || shot.score() < best.score())
				best = shot;
		}
		for (double offset : BALLISTIC_REFINE_OFFSETS) {
			BallisticShot shot = simulateBallisticShot(p, tx, ty, tz, speed, yawDeg, best.pitch() + offset);
			if (shot.score() < best.score())
				best = shot;
		}
		for (double offset : BALLISTIC_FINE_OFFSETS) {
			BallisticShot shot = simulateBallisticShot(p, tx, ty, tz, speed, yawDeg, best.pitch() + offset);
			if (shot.score() < best.score())
				best = shot;
		}
		return new BallisticAim(best.pitch(), Math.max(1, best.tick()), best.hit());
	}

	/**
	 * Simulates one pitch candidate.
	 *
	 * The model mirrors `AbstractArrow#tick`: the tick segment is tested for the closest
	 * approach first, then drag and gravity update the velocity for the next tick. The launch
	 * direction mirrors `Projectile#shootFromRotation`; the bow and trident inherit the
	 * shooter's movement, the crossbow does not.
	 */
	private BallisticShot simulateBallisticShot(LocalPlayer p, double tx, double ty, double tz, double speed, double yawDeg, double pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double horizontal = Math.cos(pitch);
		double vx = -Math.sin(yaw) * horizontal * speed;
		double vy = -Math.sin(pitch) * speed;
		double vz = Math.cos(yaw) * horizontal * speed;
		if (!combatWeapon.equals("crossbow")) {
			Vec3 shooter = p.getDeltaMovement();
			vx += shooter.x;
			vy += p.onGround() ? 0 : shooter.y;
			vz += shooter.z;
		}
		double px = p.getX();
		double py = p.getEyeY() + BALLISTIC_SPAWN_OFFSET_Y;
		double pz = p.getZ();
		// A curve that passes the target's horizontal distance can never come
		// back to it (horizontal motion is monotone), so the search stops there.
		double targetReach = Math.hypot(tx - px, tz - pz) + BALLISTIC_PAST_TARGET_MARGIN;
		Level level = Minecraft.getInstance().level;
		double closest = Double.POSITIVE_INFINITY;
		int closestTick = 1;
		double peakY = py;
		boolean blocked = false;
		for (int tick = 1; tick <= BALLISTIC_MAX_TICKS; tick++) {
			double nx = px + vx;
			double ny = py + vy;
			double nz = pz + vz;
			vx *= BALLISTIC_AIR_INERTIA;
			vy = vy * BALLISTIC_AIR_INERTIA - combatGravity(p);
			vz *= BALLISTIC_AIR_INERTIA;
			// Terrain the arrow would hit first disqualifies this curve. The
			// client world is the same world the arrow flies in, so the check
			// needs no RPC; the clip uses vanilla block collision shapes.
			if (level != null) {
				HitResult clip = level.clip(new ClipContext(new Vec3(px, py, pz), new Vec3(nx, ny, nz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
				if (clip.getType() != HitResult.Type.MISS) {
					blocked = true;
					break;
				}
			}
			double distance = distanceToSegment(tx, ty, tz, px, py, pz, nx, ny, nz);
			if (distance < closest) {
				closest = distance;
				closestTick = tick;
			}
			if (ny > peakY)
				peakY = ny;
			px = nx;
			py = ny;
			pz = nz;
			if (closest <= BALLISTIC_HIT_RADIUS || py < -128.0)
				break;
			if (Math.hypot(px - p.getX(), pz - p.getZ()) > targetReach)
				break;
		}
		return new BallisticShot(pitchDeg, closest, closestTick, peakY, blocked);
	}

	/** Distance from a point to the segment (ax,ay,az)-(bx,by,bz). */
	private static double distanceToSegment(double x, double y, double z, double ax, double ay, double az, double bx, double by, double bz) {
		double dx = bx - ax;
		double dy = by - ay;
		double dz = bz - az;
		double lengthSquared = dx * dx + dy * dy + dz * dz;
		double t = lengthSquared < 1.0E-9 ? 0.0 : ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / lengthSquared;
		t = Mth.clamp(t, 0.0, 1.0);
		double cx = ax + dx * t - x;
		double cy = ay + dy * t - y;
		double cz = az + dz * t - z;
		return Math.sqrt(cx * cx + cy * cy + cz * cz);
	}

	/** The pinned target entity in the client world, or null when out of range. */
	private Entity currentTargetEntity(Minecraft mc) {
		if (mc.level == null || combatTargetUuid == null)
			return null;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.getUUID().toString().equals(combatTargetUuid))
				return e;
		}
		return null;
	}

	/** Total tridents carried across the whole player inventory. */
	private int countTridents(LocalPlayer p) {
		int count = 0;
		for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("minecraft:trident"))
				count += stack.getCount();
		}
		return count;
	}

	/**
	 * True only when this throw's trident came back.
	 *
	 * Evidence is the inventory difference since the throw (a trident count above
	 * the post-throw snapshot) combined with the fired projectile uuid resolving
	 * when one was tracked. A carried backup trident cannot satisfy this: the
	 * thrown one is still missing until it is recalled or picked up.
	 */
	private boolean tridentReturned(Minecraft mc, LocalPlayer p) {
		if (combatTridentCountAfter < 0)
			return false;
		if (countTridents(p) <= combatTridentCountAfter)
			return false;
		return combatTridentUuid == null || !projectileStillPresent(mc, combatTridentUuid);
	}

	/** Ammo count for the current weapon: arrows for bow/crossbow, tridents otherwise. */
	private int countAmmo(LocalPlayer p) {
		boolean trident = combatWeapon != null && combatWeapon.equals("trident");
		int count = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack stack = p.getInventory().getItem(i);
			if (stack.isEmpty())
				continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			boolean matches = trident
					? id.equals("minecraft:trident")
					: id.equals("minecraft:arrow") || id.endsWith("_arrow")
							|| (combatWeapon != null && combatWeapon.equals("crossbow") && id.equals("minecraft:firework_rocket"));
			if (matches)
				count += stack.getCount();
		}
		// B-08: a prepared crossbow keeps its rockets in the off hand.
		if (!trident && combatWeapon != null && combatWeapon.equals("crossbow") && p.getOffhandItem().is(Items.FIREWORK_ROCKET))
			count += p.getOffhandItem().getCount();
		return count;
	}

	private Entity findOwnProjectile(Minecraft mc, LocalPlayer p) {
		if (mc.level == null)
			return null;
		boolean expectTrident = combatWeapon != null && combatWeapon.equals("trident");
		for (Entity e : mc.level.entitiesForRendering()) {
			if (combatProjectiles.contains(e.getUUID()) || combatPreExistingProjectiles.contains(e.getUUID()))
				continue;
			boolean isArrow = e instanceof AbstractArrow;
			boolean isTrident = e instanceof ThrownTrident;
			boolean isFirework = e instanceof FireworkRocketEntity;
			if (!isArrow && !isTrident && !isFirework)
				continue;
			if (expectTrident != isTrident)
				continue;
			// B-08: a firework rocket is this task's projectile only for a crossbow.
			if (isFirework && (combatWeapon == null || !combatWeapon.equals("crossbow")))
				continue;
			// Owner is a Projectile accessor, not an Entity one.
			Entity owner = e instanceof Projectile projectile ? projectile.getOwner() : null;
			if (owner == null || !owner.getUUID().equals(p.getUUID()))
				continue;
			return e;
		}
		return null;
	}

	private boolean projectileStillPresent(Minecraft mc, UUID uuid) {
		if (mc.level == null)
			return false;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.getUUID().equals(uuid))
				return true;
		}
		return false;
	}

	private boolean selectWeapon(Minecraft mc, LocalPlayer p, String weapon) {
		String itemId = switch (weapon) {
			case "bow" -> "minecraft:bow";
			case "crossbow" -> "minecraft:crossbow";
			case "trident" -> "minecraft:trident";
			default -> null;
		};
		if (itemId == null)
			return false;
		int hotbar = -1;
		for (int slot = 0; slot <= 8; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
				hotbar = slot;
				break;
			}
		}
		if (hotbar < 0) {
			int main = -1;
			for (int slot = 9; slot <= 35; slot++) {
				ItemStack stack = p.getInventory().getItem(slot);
				if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
					main = slot;
					break;
				}
			}
			if (main < 0)
				return false;
			int dest = -1;
			for (int slot = 0; slot <= 8; slot++) {
				if (p.getInventory().getItem(slot).isEmpty()) {
					dest = slot;
					break;
				}
			}
			if (dest < 0)
				return false;
			MultiPlayerGameMode gm = mc.gameMode;
			if (gm == null)
				return false;
			int containerId = p.inventoryMenu.containerId;
			containerClick(gm, containerId, toMenuSlot(main), p);
			containerClick(gm, containerId, toMenuSlot(dest), p);
			hotbar = dest;
		}
		//? if >=1.21.5 {
		p.getInventory().setSelectedSlot(hotbar);
		//?} else
		p.getInventory().selected = hotbar;
		sendCarriedItem(mc, hotbar);
		return true;
	}

	/**
	 * Sends the held slot to the server (B-08).
	 *
	 * The inventory's selected slot is client-side state; vanilla only sends the
	 * carried-item packet from its own hotbar input. A weapon or tool swap done by
	 * the bot must announce the slot itself, or a use acts on the previous item
	 * and a crossbow load never charges.
	 */
	private void sendCarriedItem(Minecraft mc, int hotbar) {
		if (mc.getConnection() != null)
			mc.getConnection().send(new ServerboundSetCarriedItemPacket(hotbar));
	}

	/**
	 * B-08: moves one firework rocket to the off hand for a crossbow load.
	 *
	 * A crossbow's held-projectile check accepts arrows or fireworks, but its
	 * inventory scan is arrows only, so a firework in the backpack never loads.
	 * With no arrows in the inventory the load must take the off-hand rocket; if
	 * the off hand already holds one, or arrows exist and win the draw, nothing
	 * changes.
	 */
	private void prepareFireworkOffhand(Minecraft mc, LocalPlayer p) {
		if (findArrowSlot(p) >= 0)
			return;
		if (p.getOffhandItem().is(Items.FIREWORK_ROCKET))
			return;
		int slot = findFireworkSlot(p);
		if (slot < 0 || slot > 35)
			return;
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null)
			return;
		int containerId = p.inventoryMenu.containerId;
		containerClick(gm, containerId, toMenuSlot(slot), p);
		containerClick(gm, containerId, 45, p);
	}

	private int findArrowSlot(LocalPlayer p) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && ARROW_ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
				return slot;
		}
		return -1;
	}

	/** B-08: slot of a firework rocket, the crossbow's second supported ammo. */
	private int findFireworkSlot(LocalPlayer p) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.is(Items.FIREWORK_ROCKET))
				return slot;
		}
		return -1;
	}

	/**
	 * B-08: firework ammo for a crossbow, including the rocket already in the off
	 * hand. A prepared crossbow keeps its rockets there (the only slot the vanilla
	 * held-projectile check accepts), so the inventory scan alone would report
	 * {@code no_ammo} once the stack has moved.
	 */
	private boolean hasFireworkAmmo(LocalPlayer p) {
		return p.getOffhandItem().is(Items.FIREWORK_ROCKET) || findFireworkSlot(p) >= 0;
	}

	/**
	 * B-08: the projectile each carried ranged weapon would use next.
	 *
	 * A charged crossbow reports its loaded stack; an unloaded one reports the
	 * vanilla draw order (arrows first, then a firework rocket). A bow reports the
	 * next stack its projectile lookup would draw. Empty when the ammo is missing
	 * or no such weapon is carried.
	 */
	private JsonObject weaponProjectiles(LocalPlayer p) {
		JsonObject out = new JsonObject();
		out.addProperty("bow", bowProjectileId(p));
		out.addProperty("crossbow", crossbowProjectileId(p));
		out.addProperty("trident", carried(p, Items.TRIDENT) ? "minecraft:trident" : "");
		return out;
	}

	private String bowProjectileId(LocalPlayer p) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (stack.isEmpty() || !stack.is(Items.BOW))
				continue;
			ItemStack drawn = p.getProjectile(stack);
			if (!drawn.isEmpty())
				return BuiltInRegistries.ITEM.getKey(drawn.getItem()).toString();
		}
		return "";
	}

	private String crossbowProjectileId(LocalPlayer p) {
		boolean hasCrossbow = false;
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (stack.isEmpty() || !stack.is(Items.CROSSBOW))
				continue;
			hasCrossbow = true;
			ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
			if (charged != null) {
				for (ItemStack projectile : charged.getItems()) {
					if (!projectile.isEmpty())
						return BuiltInRegistries.ITEM.getKey(projectile.getItem()).toString();
				}
			}
		}
		if (!hasCrossbow)
			return "";
		// The vanilla held-projectile check runs before the inventory scan, so an
		// off-hand firework rocket is what the load would take first.
		if (p.getOffhandItem().is(Items.FIREWORK_ROCKET))
			return BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString();
		int arrowSlot = findArrowSlot(p);
		if (arrowSlot >= 0)
			return BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(arrowSlot).getItem()).toString();
		int fireworkSlot = findFireworkSlot(p);
		if (fireworkSlot >= 0)
			return BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(fireworkSlot).getItem()).toString();
		return "";
	}

	private boolean carried(LocalPlayer p, Item item) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.is(item))
				return true;
		}
		return false;
	}

	private boolean isCrossbowCharged(LocalPlayer p) {		ItemStack held = p.getMainHandItem();
		if (held.isEmpty())
			return false;
		ChargedProjectiles charged = held.get(DataComponents.CHARGED_PROJECTILES);
		if (charged == null)
			return false;
		// After a shot the component can remain present but empty; that is the
		// unloaded state, not a loaded crossbow.
		for (ItemStack projectile : charged.getItems()) {
			if (!projectile.isEmpty())
				return true;
		}
		return false;
	}

	/** Player-inventory index to player-menu slot (hotbar 36-44, main 9-35). */
	private static int toMenuSlot(int inv) {
		if (inv >= 0 && inv <= 8)
			return 36 + inv;
		if (inv >= 9 && inv <= 35)
			return inv;
		return 45;
	}

	private static void containerClick(MultiPlayerGameMode gm, int containerId, int slot, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, 0, ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}
}