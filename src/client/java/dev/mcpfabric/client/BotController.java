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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
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

	/** Approximate projectile speed in blocks/tick, used to estimate flight time. */
	private static final double COMBAT_PROJECTILE_SPEED = 3.0;
	/** Flight-time clamp: at least one tick, at most two seconds. */
	private static final double COMBAT_MIN_FLIGHT_TICKS = 1.0;
	private static final double COMBAT_MAX_FLIGHT_TICKS = 40.0;
	/** Lead distance cap in blocks, so an extreme velocity cannot over-aim. */
	private static final double COMBAT_MAX_LEAD_BLOCKS = 6.0;

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
	/** Consecutive stable ticks that end a lone-pad settle. */
	private static final int JUMP_SETTLE_STABLE_TICKS = 2;
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

	public synchronized JsonObject startJump(double tx, double ty, double tz,
			double takeoffX, double takeoffZ, double dirX, double dirZ, boolean sprint, boolean brake,
			double takeoffRadius, double landingRadius, long deadlineMillis) {
		jumpTargetX = tx;
		jumpTargetY = ty;
		jumpTargetZ = tz;
		jumpTakeoffX = takeoffX;
		jumpTakeoffZ = takeoffZ;
		double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
		jumpDirX = length < 1e-6 ? 0 : dirX / length;
		jumpDirZ = length < 1e-6 ? 0 : dirZ / length;
		jumpSprint = sprint;
		jumpBrake = brake;
		jumpTakeoffRadius = takeoffRadius > 0 ? takeoffRadius : 0.35;
		jumpLandingRadius = landingRadius > 0 ? landingRadius : 0.7;
		jumpDeadline = deadlineMillis;
		jumpTicks = 0;
		jumpSettleTicks = 0;
		jumpStableTicks = 0;
		jumpTakeoffIssued = false;
		jumpAirborneSeen = false;
		jumpTouchedDown = false;
		jumpPhase = "prepare";
		jumpInput = "none";
		jumpPredictedStop = 0;
		jumpState = "running";
		jumpEndReason = "running";
		return jumpStatusJson();
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
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			o.addProperty("isSprinting", p.isSprinting());
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
			jumpPhase = "settle";
			// Settle along the flight direction, not by facing the target: the
			// velocity is in world space, and re-aiming after overshooting turns
			// the brake into a push away from the goal. The stop estimate uses
			// the ground friction so the key decision matches where the bot will
			// actually come to rest.
			double along = (p.getX() - jumpTargetX) * jumpDirX + (p.getZ() - jumpTargetZ) * jumpDirZ;
			double vAlong = p.getDeltaMovement().x * jumpDirX + p.getDeltaMovement().z * jumpDirZ;
			jumpPredictedStop = along + vAlong / (1 - GROUND_SPEED_RETENTION);
			if (jumpBrake) {
				jumpSettleTicks++;
				// The settle is bounded: a controller that cannot centre the bot
				// must not report failure for a landing that is already on the
				// pad, so the cap accepts the measured position when it is
				// inside the host's goal tolerance.
				if (jumpSettleTicks >= JUMP_SETTLE_MAX_TICKS && Math.abs(along) <= 0.5) {
					stopAllMovement();
					finishJump("done", "landed");
					return;
				}
				if (Math.abs(along) <= JUMP_SETTLE_CENTER && Math.abs(vAlong) <= JUMP_SETTLE_SPEED) {
					jumpStableTicks++;
					if (jumpStableTicks >= JUMP_SETTLE_STABLE_TICKS) {
						stopAllMovement();
						finishJump("done", "landed");
						return;
					}
					releaseHorizontal(p);
					return;
				}
				jumpStableTicks = 0;
				if (jumpPredictedStop > JUMP_SETTLE_CENTER) {
					// Will rest past the centre: face along the flight and push back.
					aimAlongFlight(p);
					pressHorizontal(false);
					return;
				}
				if (jumpPredictedStop < -JUMP_SETTLE_CENTER) {
					aimAlongFlight(p);
					pressHorizontal(true);
					return;
				}
				// The coast lands inside the window: release and let friction stop it.
				releaseHorizontal(p);
				return;
			}
			if (horizontal <= JUMP_SETTLE_RADIUS || jumpSettleTicks >= JUMP_SETTLE_MAX_TICKS) {
				stopAllMovement();
				finishJump("done", "landed");
				return;
			}
			// A staircase landing keeps its speed and only walks the last
			// fraction to the stand point.
			jumpSettleTicks++;
			aimAtPoint(p, jumpTargetX, jumpTargetY, jumpTargetZ);
			pressHorizontal(true);
			return;
		}
		if (p.getY() < jumpTargetY - jumpFallTolerance) {
			finishJump("failed", "fell");
			return;
		}
		if (jumpTicks > 200) {
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
		if (!jumpTakeoffIssued && p.onGround() && passed >= -jumpTakeoffRadius) {
			jumpTakeoffIssued = true;
			jumpPhase = "takeoff";
			pressHorizontal(true);
			jumpHeld = true;
			return;
		}
		// The flight keeps its forward press: releasing mid-air barely changes
		// the velocity and made hops land short of their pad. All stop control
		// happens after the first touchdown, where friction and the back key
		// actually work (the settle above).
		pressHorizontal(true);
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
				if (entity instanceof AbstractArrow || entity instanceof ThrownTrident)
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
		if (!weapon.equals("trident") && findArrowSlot(p) < 0) {
			combatState = "done";
			combatEndReason = "no_ammo";
			return combatStatusJson();
		}
		// A loaded crossbow starts at the aim/fire beat; an empty one loads first.
		combatCharged = weapon.equals("crossbow") && isCrossbowCharged(p);
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
			// Step 3: the jump task owns the movement input while it runs, so it
			// is ticked after navigation and before the key application below.
			if (jumpState.equals("running")) {
				tickJump(mc, p);
			}
			// MC-4d: the weapon task runs after movement so aiming wins the look
			// for this tick, and before the use-key hold below.
			if (combatPhase != CombatPhase.IDLE) {
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
			if (jumpOnceTicks > 0) jumpOnceTicks--;
			tickMining(mc);
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
		if (!p.isUsingItem())
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (isCrossbowCharged(p)) {
			combatCharged = true;
			combatPhase = CombatPhase.CHARGING;
			combatHoldTicks = 0;
			return;
		}
		// The load is a bounded window; a crossbow that never charges fails honestly.
		if (combatLoadTicks > 80) {
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
		if (!p.isUsingItem())
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (p.getTicksUsingItem() >= RIPTIDE_CHARGE_TICKS) {
			// Normal release: this is the vanilla riptide launch.
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
			double tx = target.getX();
			double ty = target.getY() + target.getBbHeight() * 0.5;
			double tz = target.getZ();
			if (combatLeadEnabled) {
				double distance = p.getEyePosition().distanceTo(new Vec3(tx, ty, tz));
				double flightTicks = Mth.clamp(distance / COMBAT_PROJECTILE_SPEED,
						COMBAT_MIN_FLIGHT_TICKS, COMBAT_MAX_FLIGHT_TICKS);
				Vec3 vel = target.getDeltaMovement();
				tx += Mth.clamp(vel.x * flightTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
				ty += Mth.clamp(vel.y * flightTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
				tz += Mth.clamp(vel.z * flightTicks, -COMBAT_MAX_LEAD_BLOCKS, COMBAT_MAX_LEAD_BLOCKS);
			}
			combatAimX = tx;
			combatAimY = ty;
			combatAimZ = tz;
			combatAimHasPosition = true;
			combatAimSource = "fresh";
			aimAtPoint(p, combatAimX, combatAimY, combatAimZ);
			return;
		}
		if (combatAimHasPosition) {
			combatAimSource = "last_seen";
			aimAtPoint(p, combatAimX, combatAimY, combatAimZ);
			return;
		}
		// Never seen the entity in render range: fall back to the pinned point.
		combatAimX = combatTargetX;
		combatAimY = combatTargetY;
		combatAimZ = combatTargetZ;
		combatAimSource = "pinned";
		aimAtPoint(p, combatAimX, combatAimY, combatAimZ);
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
					: id.equals("minecraft:arrow") || id.endsWith("_arrow");
			if (matches)
				count += stack.getCount();
		}
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
			if (!isArrow && !isTrident)
				continue;
			if (expectTrident != isTrident)
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
		return true;
	}

	private int findArrowSlot(LocalPlayer p) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && ARROW_ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
				return slot;
		}
		return -1;
	}

	private boolean isCrossbowCharged(LocalPlayer p) {
		ItemStack held = p.getMainHandItem();
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
