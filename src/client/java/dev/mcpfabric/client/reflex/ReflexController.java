package dev.mcpfabric.client.reflex;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.config.McpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * MC-0d survival reflexes (patch P3).
 *
 * Three tick-driven, client-local behaviours: escape-hazard, auto-eat, and
 * defend. They never call the model; when a reflex preempts a running
 * navigation it clears the controller with `reflex_preempted` and reports the
 * command id, so the core can replan. Events go through the shared EventBus
 * (`game:reflex`) and are merged per cause within the configured window.
 */
public final class ReflexController {
	private static final ReflexController INSTANCE = new ReflexController();

	public static ReflexController get() {
		return INSTANCE;
	}

	private ReflexController() {}

	private enum Mode { IDLE, ESCAPING, EATING, COUNTERING, DISENGAGING, WATER_LANDING }

	private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

	/** Downward speed (blocks/tick) below which a fall is treated as a hazard. */
	private static final double WATER_LANDING_FALL_SPEED = -0.5;
	/** Bounded window (ticks) in which the bucket use may be placed before landing. */
	private static final int WATER_LANDING_MAX_TICKS = 60;

	private Mode mode = Mode.IDLE;
	private int ticksInMode;
	private final Map<String, Long> lastEmitByCause = new HashMap<>();

	private Vec3 positionBefore;
	private int hungerBefore;
	private float healthBefore;
	private String foodItemId;
	private int foodSlot = -1;
	private int eatTicks;
	private int attackCooldown;
	private String attackerUuid;
	private String attackerName;
	private LivingEntity attackTarget;
	private int lastHurtTime;
	/** Command id the current reflex incident preempted, echoed on completion. */
	private String preemptedCommandId;

	/** MC-4e water-bucket landing. One attempt per fall; reset when back on a safe footing. */
	private boolean waterLandingAttempted;
	private int waterLandingTicks;
	private int waterSlot = -1;
	private boolean waterPlaced;
	private float fallDistanceBefore;
	private float fallDistanceAfter;

	public void onClientTick(Minecraft mc) {
		var reflex = McpFabric.config().reflex;
		if (reflex == null || !reflex.enabled)
			return;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null || p.isDeadOrDying())
			return;
		ticksInMode++;

		// MC-4e: a landed player earns one new water-landing attempt for the
		// next fall; the flag stays set while airborne so a fall is not retried.
		if (p.onGround() || p.isInWater() || p.isInLava())
			waterLandingAttempted = false;

		if (mode == Mode.IDLE) {
			if (reflex.escapeHazard && detectHazard(p)) {
				startEscape(p, level);
				return;
			}
			if (reflex.waterLanding && detectWaterLanding(p, reflex)) {
				startWaterLanding(mc, p, reflex);
				return;
			}
			if (reflex.autoEat && p.getFoodData().getFoodLevel() < reflex.hungerThreshold) {
				startEat(mc, p, reflex);
				return;
			}
			if (reflex.defend && detectAttacked(p)) {
				startDefend(mc, p, reflex);
				return;
			}
			return;
		}

		switch (mode) {
			case ESCAPING -> tickEscape(mc, p, level);
			case EATING -> tickEat(mc, p, reflex);
			case COUNTERING -> tickCounter(mc, p, reflex);
			case DISENGAGING -> tickDisengage(mc, p, reflex);
			case WATER_LANDING -> tickWaterLanding(mc, p);
			default -> mode = Mode.IDLE;
		}
	}

	// --- escape-hazard ---------------------------------------------------------------------

	private boolean detectHazard(LocalPlayer p) {
		return p.isInLava() || p.isOnFire()
				|| (p.isInWater() && p.getAirSupply() < 150)
				|| p.isInWall();
	}

	private void startEscape(LocalPlayer p, ClientLevel level) {
		String preempted = BotController.get().currentNavigationCommandId();
		BotController.get().clearAll("reflex_preempted");
		mode = Mode.ESCAPING;
		ticksInMode = 0;
		preemptedCommandId = preempted;
		positionBefore = p.position();
	}

	private void tickEscape(Minecraft mc, LocalPlayer p, ClientLevel level) {
		if (!detectHazard(p) && p.onGround() && isSafeStand(level, p.blockPosition())) {
			mode = Mode.IDLE;
			// Converge: release the escape movement, do not keep walking.
			BotController.get().stopAllMovement();
			emitCompletion("hazard", "escaped", null);
			return;
		}
		if (ticksInMode > 60) {
			BotController.get().stopAllMovement();
			mode = Mode.IDLE;
			emitCompletion("hazard", "failed", "escape_timeout");
			return;
		}

		BlockPos feet = p.blockPosition();
		Direction best = null;
		for (Direction direction : HORIZONTALS) {
			if (isSafeStand(level, feet.relative(direction))) {
				best = direction;
				break;
			}
		}
		if (best == null) {
			BotController.get().jumpOnce();
			return;
		}

		float yaw = yawFor(best);
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
		boolean jump = level.getBlockState(feet.relative(best)).isSolid()
				|| !level.getBlockState(feet.relative(best).below()).isSolid();
		BotController.get().setMovement(true, false, false, false, jump, false, false);
	}

	private boolean isSafeStand(ClientLevel level, BlockPos pos) {
		BlockState below = level.getBlockState(pos.below());
		BlockState at = level.getBlockState(pos);
		BlockState above = level.getBlockState(pos.above());
		if (below.is(Blocks.LAVA) || at.is(Blocks.LAVA) || at.is(Blocks.FIRE) || above.is(Blocks.FIRE))
			return false;
		if (!below.isSolid())
			return false;
		return at.getFluidState().isEmpty() && above.getFluidState().isEmpty();
	}

	private float yawFor(Direction direction) {
		return switch (direction) {
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case NORTH -> 180.0F;
			case EAST -> -90.0F;
			default -> 0.0F;
		};
	}

	// --- auto-eat --------------------------------------------------------------------------

	private void startEat(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		String preempted = BotController.get().currentNavigationCommandId();
		BotController.get().clearAll("reflex_preempted");
		mode = Mode.EATING;
		ticksInMode = 0;
		eatTicks = 0;
		hungerBefore = p.getFoodData().getFoodLevel();
		positionBefore = p.position();

		int slot = findFoodSlot(p);
		// MC-4c: when only the main inventory has food, move one stack into an
		// empty hotbar slot first. No empty slot means no_food, not a displaced
		// hand item.
		if (slot > 8)
			slot = moveFoodToHotbar(mc, p);
		if (slot < 0) {
			mode = Mode.IDLE;
			preemptedCommandId = preempted;
			emitCompletion("hunger", "failed", "no_food");
			return;
		}
		preemptedCommandId = preempted;
		foodSlot = slot;
		foodItemId = p.getInventory().getItem(slot).getItem().toString();
		//? if >=1.21.5 {
		/*p.getInventory().setSelectedSlot(slot);*/
		//?} else
		p.getInventory().selected = slot;
		BotController.get().setUseHeld(true);
		mc.options.keyUse.setDown(true);
		// Key state alone never triggers `consumeClick`, so the first use must
		// be explicit; tickEat repeats it until the eating starts.
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
	}

	private void tickEat(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		eatTicks++;
		// `KeyMapping.setDown` does not raise a click, so hold-to-eat needs an
		// explicit use call until the item-using state is live.
		if (!p.isUsingItem() && eatTicks % 2 == 0) {
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		}
		if (p.getFoodData().getFoodLevel() >= reflex.hungerThreshold) {
			stopEating(mc, p);
			emitCompletion("hunger", "ate", null);
			return;
		}
		if (eatTicks > 100 || findHotbarFoodSlot(p) < 0) {
			stopEating(mc, p);
			emitCompletion("hunger", "failed", eatTicks > 100 ? "eat_timeout" : "no_food");
		}
	}

	private void stopEating(Minecraft mc, LocalPlayer p) {
		BotController.get().setUseHeld(false);
		mc.options.keyUse.setDown(false);
		p.stopUsingItem();
		mode = Mode.IDLE;
	}

	/** First food stack in the hotbar (0-8), or -1 when the hotbar has none. */
	private int findHotbarFoodSlot(LocalPlayer p) {
		for (int slot = 0; slot <= 8; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.has(DataComponents.FOOD))
				return slot;
		}
		return -1;
	}

	/** First food stack in the hotbar, then the main inventory (9-35), or -1. */
	private int findFoodSlot(LocalPlayer p) {
		int hotbar = findHotbarFoodSlot(p);
		if (hotbar >= 0)
			return hotbar;
		for (int slot = 9; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.has(DataComponents.FOOD))
				return slot;
		}
		return -1;
	}

	/**
	 * Moves one main-inventory food stack into the first empty hotbar slot through
	 * the player menu's PICKUP clicks, then returns that hotbar slot.
	 *
	 * @return the hotbar slot now holding the food, or -1 when there is no food,
	 *         no empty hotbar slot, or the click did not move the stack.
	 */
	private int moveFoodToHotbar(Minecraft mc, LocalPlayer p) {
		return moveMainStackToHotbar(mc, p, stack -> stack.has(DataComponents.FOOD));
	}

	/** Moves one main-inventory water bucket into the hotbar; see {@link #moveMainStackToHotbar}. */
	private int moveWaterToHotbar(Minecraft mc, LocalPlayer p) {
		return moveMainStackToHotbar(mc, p, stack -> stack.is(Items.WATER_BUCKET));
	}

	/**
	 * Moves one matching main-inventory stack into the first empty hotbar slot.
	 *
	 * @return the hotbar slot now holding the stack, or -1 when no matching stack
	 *         exists, no empty hotbar slot is free, or the click did not move it.
	 */
	private int moveMainStackToHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> match) {
		int source = -1;
		for (int slot = 9; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && match.test(stack)) {
				source = slot;
				break;
			}
		}
		if (source < 0)
			return -1;
		int dest = -1;
		for (int slot = 0; slot <= 8; slot++) {
			if (p.getInventory().getItem(slot).isEmpty()) {
				dest = slot;
				break;
			}
		}
		if (dest < 0)
			return -1;
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null)
			return -1;
		int containerId = p.inventoryMenu.containerId;
		containerClick(gm, containerId, toMenuSlot(source), p);
		containerClick(gm, containerId, toMenuSlot(dest), p);
		ItemStack moved = p.getInventory().getItem(dest);
		return !moved.isEmpty() && match.test(moved) ? dest : -1;
	}

	/** Player-inventory index to player-menu slot (hotbar 36-44, main 9-35). */
	private static int toMenuSlot(int inv) {
		if (inv >= 0 && inv <= 8)
			return 36 + inv;
		if (inv >= 9 && inv <= 35)
			return inv;
		if (inv >= 36 && inv <= 39)
			return 5 + (inv - 36);
		return 45;
	}

	/**
	 * One PICKUP click. {@code handleInventoryMouseClick(..., ClickType, ...)} became
	 * {@code handleContainerInput(..., ContainerInput, ...)} in 26.1.
	 */
	private static void containerClick(MultiPlayerGameMode gm, int containerId, int slot, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, 0, ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	// --- water landing (MC-4e) ---------------------------------------------------------------

	/**
	 * True when a fall is long and fast enough to justify one water-bucket attempt.
	 *
	 * <p>Reads the vanilla {@code fallDistance} and vertical motion. Gliding, water,
	 * lava and a repeated fall are excluded; a landed player resets the attempt in
	 * {@link #onClientTick}.
	 */
	private boolean detectWaterLanding(LocalPlayer p, McpConfig.ReflexConfig reflex) {
		if (waterLandingAttempted)
			return false;
		if (p.onGround() || p.isInWater() || p.isInLava() || p.isFallFlying())
			return false;
		if (p.getDeltaMovement().y > WATER_LANDING_FALL_SPEED)
			return false;
		return p.fallDistance >= reflex.waterLandingMinFall;
	}

	private void startWaterLanding(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		String preempted = BotController.get().currentNavigationCommandId();
		// The reflex takes the input: clearAll ends navigation and any weapon task,
		// and releases the use key on the next tick (mc-0d ownership).
		BotController.get().clearAll("reflex_preempted");
		// A preempted eat may have left the shared use-hold set; the landing
		// places water through an explicit use, so clear it before selecting.
		BotController.get().setUseHeld(false);
		mode = Mode.WATER_LANDING;
		ticksInMode = 0;
		waterLandingAttempted = true;
		waterPlaced = false;
		waterLandingTicks = 0;
		waterSlot = -1;
		fallDistanceBefore = p.fallDistance;
		fallDistanceAfter = p.fallDistance;
		preemptedCommandId = preempted;

		int slot = findWaterBucketSlot(p);
		if (slot > 8)
			slot = moveWaterToHotbar(mc, p);
		if (slot < 0) {
			mode = Mode.IDLE;
			emitCompletion("hazard", "failed", "no_water_bucket");
			return;
		}
		waterSlot = slot;
		//? if >=1.21.5 {
		/*p.getInventory().setSelectedSlot(slot);*/
		//?} else
		p.getInventory().selected = slot;
		lookStraightDown(p);
	}

	private void tickWaterLanding(Minecraft mc, LocalPlayer p) {
		waterLandingTicks++;
		fallDistanceAfter = p.fallDistance;
		lookStraightDown(p);

		if (p.onGround() || p.isInWater() || p.isInLava()) {
			BotController.get().stopAllMovement();
			mode = Mode.IDLE;
			emitCompletion("hazard", waterPlaced ? "water_landed" : "failed", waterPlaced ? null : "placement_failed");
			return;
		}

		ItemStack held = p.getMainHandItem();
		boolean stillBucket = !held.isEmpty() && held.is(Items.WATER_BUCKET);
		if (stillBucket && waterLandingTicks <= WATER_LANDING_MAX_TICKS)
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (!stillBucket)
			waterPlaced = true;

		if (waterLandingTicks > WATER_LANDING_MAX_TICKS) {
			BotController.get().stopAllMovement();
			mode = Mode.IDLE;
			emitCompletion("hazard", waterPlaced ? "water_landed" : "failed", waterPlaced ? null : "placement_failed");
		}
	}

	private void lookStraightDown(LocalPlayer p) {
		p.setXRot(90.0F);
		p.setYRot(p.getYRot());
		p.setYHeadRot(p.getYRot());
		p.setYBodyRot(p.getYRot());
	}

	/** First water bucket in the hotbar, then the main inventory (9-35), or -1. */
	private int findWaterBucketSlot(LocalPlayer p) {
		for (int slot = 0; slot <= 35; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.is(Items.WATER_BUCKET))
				return slot;
		}
		return -1;
	}

	// --- defend ----------------------------------------------------------------------------

	private boolean detectAttacked(LocalPlayer p) {
		boolean hurt = p.hurtTime > 0;
		boolean rising = hurt && lastHurtTime == 0;
		lastHurtTime = p.hurtTime;
		return rising;
	}

	private void startDefend(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		DamageSource source = p.getLastDamageSource();
		Entity attacker = source != null ? source.getEntity() : null;
		if (!(attacker instanceof LivingEntity living) || attacker instanceof Player)
			return;
		preemptedCommandId = BotController.get().currentNavigationCommandId();
		BotController.get().clearAll("reflex_preempted");
		attackTarget = living;
		attackerUuid = living.getUUID().toString();
		attackerName = living.getName().getString();
		healthBefore = p.getHealth();
		positionBefore = p.position();
		attackCooldown = 0;
		boolean counter = reflex.attackBack && p.getHealth() > reflex.defendHealthThreshold;
		mode = counter ? Mode.COUNTERING : Mode.DISENGAGING;
		ticksInMode = 0;
	}

	private void tickCounter(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		if (!isValidTarget(attackTarget)) {
			mode = Mode.IDLE;
			BotController.get().stopAllMovement();
			emitCompletion("attacked", "countered", null);
			return;
		}
		if (p.getHealth() <= reflex.defendHealthThreshold) {
			mode = Mode.DISENGAGING;
			ticksInMode = 0;
			return;
		}
		if (ticksInMode > 200) {
			mode = Mode.IDLE;
			BotController.get().stopAllMovement();
			emitCompletion("attacked", "failed", "defend_timeout");
			return;
		}
		double distance = p.position().distanceTo(attackTarget.position());
		face(p, attackTarget);
		if (distance > 2.4) {
			BotController.get().setMovement(true, false, false, false, false, false, true);
		} else {
			BotController.get().stopAllMovement();
			if (attackCooldown <= 0) {
				mc.gameMode.attack(p, attackTarget);
				p.swing(InteractionHand.MAIN_HAND);
				attackCooldown = 10;
			}
		}
		if (attackCooldown > 0) attackCooldown--;
	}

	private void tickDisengage(Minecraft mc, LocalPlayer p, McpConfig.ReflexConfig reflex) {
		if (!isValidTarget(attackTarget)) {
			mode = Mode.IDLE;
			BotController.get().stopAllMovement();
			emitCompletion("attacked", "disengaged", null);
			return;
		}
		double distance = p.position().distanceTo(attackTarget.position());
		if (distance >= reflex.disengageDistance || ticksInMode > 120) {
			mode = Mode.IDLE;
			BotController.get().stopAllMovement();
			emitCompletion("attacked", distance >= reflex.disengageDistance ? "disengaged" : "failed",
					distance >= reflex.disengageDistance ? null : "disengage_timeout");
			return;
		}
		float yaw = yawAway(p, attackTarget);
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
		BotController.get().setMovement(true, false, false, false, false, false, true);
	}

	private boolean isValidTarget(LivingEntity target) {
		return target != null && target.isAlive() && !target.isRemoved();
	}

	private void face(LocalPlayer p, LivingEntity target) {
		double dx = target.getX() - p.getX();
		double dz = target.getZ() - p.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private float yawAway(LocalPlayer p, LivingEntity target) {
		double dx = p.getX() - target.getX();
		double dz = p.getZ() - target.getZ();
		return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
	}

	// --- events ----------------------------------------------------------------------------

	/** Emits the one event for a finished incident and clears the pending preemption. */
	private void emitCompletion(String cause, String action, String reason) {
		emit(cause, action, reason, preemptedCommandId);
		preemptedCommandId = null;
	}

	private void emit(String cause, String action, String reason, String preemptedCommandId) {
		var reflex = McpFabric.config().reflex;
		long now = System.currentTimeMillis();
		if (action != null && !"failed".equals(action)) {
			Long last = lastEmitByCause.get(cause);
			if (last != null && now - last < reflex.mergeWindowMs)
				return;
		}
		lastEmitByCause.put(cause, now);

		JsonObject o = new JsonObject();
		o.addProperty("cause", cause);
		o.addProperty("gameTick", gameTick());
		o.addProperty("at", now);
		if (action != null) {
			o.addProperty("action", action);
		}
		if (reason != null) {
			o.addProperty("reason", reason);
		}
		if (preemptedCommandId != null) {
			o.addProperty("preemptedCommandId", preemptedCommandId);
		}
		addPosition(o, "positionBefore", positionBefore);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			addPosition(o, "positionAfter", p.position());
			if ("hunger".equals(cause)) {
				o.addProperty("hungerBefore", hungerBefore);
				o.addProperty("hungerAfter", p.getFoodData().getFoodLevel());
				if (foodItemId != null) {
					o.addProperty("itemId", foodItemId);
				}
				// MC-4c: which hotbar slot the food came from (after a
				// main-inventory move when that was needed).
				if (foodSlot >= 0) {
					o.addProperty("slot", foodSlot);
				}
			}
			if ("attacked".equals(cause)) {
				o.addProperty("healthBefore", healthBefore);
				o.addProperty("healthAfter", p.getHealth());
				if (attackerUuid != null) {
					o.addProperty("attackerUuid", attackerUuid);
				}
				if (attackerName != null) {
					o.addProperty("attackerName", attackerName);
				}
			}
			// MC-4e: report the observed fall, not a claim of safety.
			if ("hazard".equals(cause) && ("water_landed".equals(action) || "failed".equals(action))) {
				o.addProperty("fallDistanceBefore", fallDistanceBefore);
				o.addProperty("fallDistanceAfter", p.fallDistance);
				if (waterSlot >= 0) {
					o.addProperty("slot", waterSlot);
				}
			}
		}
		McpFabric.events().emit("game:reflex", o);
	}

	private void addPosition(JsonObject o, String key, Vec3 position) {
		if (position == null)
			return;
		JsonObject p = new JsonObject();
		p.addProperty("x", position.x);
		p.addProperty("y", position.y);
		p.addProperty("z", position.z);
		o.add(key, p);
	}

	private long gameTick() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null ? level.getGameTime() : 0L;
	}
}
