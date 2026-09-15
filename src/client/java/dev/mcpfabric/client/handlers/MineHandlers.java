package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Read-only harvest evaluation (CD-M1).
 *
 * <p>The evaluation is side-effect free: it never switches the real hotbar.
 * Eligibility and speed come from item components and the block state, and the
 * caller re-verifies with a fresh read after it actually equips a tool. The
 * current player condition is reported from {@code hasCorrectToolForDrops} and
 * {@code getDestroyProgress} without starting a dig.
 */
public final class MineHandlers {
	private MineHandlers() {}

	/** Candidates per evaluation; a bounded list keeps the record small. */
	private static final int MAX_CANDIDATES = 36;

	public static void register(RpcRouter router) {
		router.register("mine.evaluateHarvest", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) {
				return missing(pos, "chunk_not_loaded");
			}

			BlockState state = level.getBlockState(pos);
			JsonObject o = new JsonObject();
			o.addProperty("blockStateId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			o.addProperty("x", pos.getX());
			o.addProperty("y", pos.getY());
			o.addProperty("z", pos.getZ());
			o.addProperty("dimension", dimensionOf(p));
			o.addProperty("playerUuid", p.getUUID().toString());
			//? if <1.21.11 {
			o.addProperty("air", state.isAir());
			//?}

			float blockSpeed = state.getDestroySpeed(level, pos);
			boolean requiresTool = state.requiresCorrectToolForDrops();
			o.addProperty("requiresTool", requiresTool);
			o.addProperty("harvestEligible", !requiresTool || p.hasCorrectToolForDrops(state));

			// The current (held) player condition is real; candidate estimates are
			// derived from the same speed formula without equipping anything.
			float heldProgress = blockSpeed <= 0 ? 0 : state.getDestroyProgress(p, level, pos);
			o.addProperty("expectedDropMode", expectedDropMode(p.getMainHandItem()));
			if (heldProgress > 0) {
				o.addProperty("estimatedTicks", (int) Math.ceil(1.0f / heldProgress));
				o.addProperty("estimateQuality", "exact");
			} else {
				o.addProperty("estimateQuality", "unknown");
			}

			o.add("candidates", candidates(p, level, pos, state, blockSpeed));
			o.add("hazards", hazards(level, pos, state));
			o.add("unmet", unmet(p, state, blockSpeed, requiresTool));

			boolean inventoryFull = p.getInventory().getFreeSlot() == -1;
			if (inventoryFull)
				unmetAdd(o, "inventory_full");
			return o;
		}));
	}

	/** A record that names the read failure instead of pretending air. */
	private static JsonObject missing(BlockPos pos, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		o.addProperty("error", reason);
		JsonArray unmet = new JsonArray();
		unmet.add("data_unavailable");
		o.add("unmet", unmet);
		return o;
	}

	private static JsonArray candidates(LocalPlayer p, ClientLevel level, BlockPos pos, BlockState state, float blockSpeed) {
		JsonArray out = new JsonArray();
		Inventory inv = p.getInventory();
		//? if >=1.21.5 {
		var items = inv.getNonEquipmentItems();
		//?} else
		/*var items = inv.items;*/
		ItemStack held = p.getMainHandItem();
		float playerSpeed = p.getDestroySpeed(state);
		int count = 0;
		for (int slot = 0; slot < items.size() && count < MAX_CANDIDATES; slot++) {
			ItemStack stack = items.get(slot);
			if (stack.isEmpty())
				continue;
			boolean correct = stack.isCorrectToolForDrops(state);
			float candidateSpeed = stack.getDestroySpeed(state);
			// A plain hand/block is not a tool candidate unless it is the only
			// thing that can break the block.
			if (!correct && candidateSpeed <= 1.0f && !stack.isDamageableItem())
				continue;
			JsonObject c = new JsonObject();
			c.addProperty("slot", slot);
			c.addProperty("hotbar", slot <= 8);
			c.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			c.addProperty("count", stack.getCount());
			if (stack.isDamageableItem()) {
				c.addProperty("damage", stack.getDamageValue());
				c.addProperty("maxDamage", stack.getMaxDamage());
			}
			JsonObject ench = enchantments(stack);
			if (ench.size() > 0)
				c.add("enchantments", ench);
			c.addProperty("harvestEligible", correct);
			c.addProperty("destroySpeed", candidateSpeed);
			c.addProperty("expectedDropMode", expectedDropMode(stack));

			if (blockSpeed <= 0) {
				c.addProperty("estimateQuality", "unknown");
			} else if (stack == held) {
				float progress = state.getDestroyProgress(p, level, pos);
				if (progress > 0) {
					c.addProperty("estimatedTicks", (int) Math.ceil(1.0f / progress));
					c.addProperty("estimateQuality", "exact");
				}
			} else {
				// Replace the held item's contribution with this candidate's so the
				// estimate keeps the player's active modifiers (haste, fatigue, water).
				float adjustedSpeed = playerSpeed - held.getDestroySpeed(state) + candidateSpeed;
				int divisor = correct ? 30 : 100;
				float progress = adjustedSpeed / blockSpeed / divisor;
				if (progress > 0) {
					c.addProperty("estimatedTicks", (int) Math.ceil(1.0f / progress));
					c.addProperty("estimateQuality", "approximate");
				}
			}
			out.add(c);
			count++;
		}
		return out;
	}

	private static JsonObject enchantments(ItemStack stack) {
		JsonObject ench = new JsonObject();
		var enchantments = stack.getEnchantments();
		if (enchantments.isEmpty())
			return ench;
		for (var entry : enchantments.entrySet()) {
			var key = entry.getKey().unwrapKey().orElse(null);
			if (key != null)
				ench.addProperty(key.location().toString(), entry.getIntValue());
		}
		return ench;
	}

	/** Expected drop rule of a candidate; both silk touch and fortune is unknown. */
	private static String expectedDropMode(ItemStack stack) {
		boolean silk = hasEnchantment(stack, "minecraft:silk_touch");
		boolean fortune = hasEnchantment(stack, "minecraft:fortune");
		if (silk && fortune)
			return "unknown";
		if (silk)
			return "silk-touch";
		if (fortune)
			return "fortune";
		return "normal";
	}

	/** True when the stack carries the named enchantment at any level. */
	private static boolean hasEnchantment(ItemStack stack, String id) {
		for (var entry : stack.getEnchantments().entrySet()) {
			var key = entry.getKey().unwrapKey().orElse(null);
			if (key != null && key.location().toString().equals(id))
				return true;
		}
		return false;
	}

	private static JsonArray hazards(ClientLevel level, BlockPos pos, BlockState state) {
		JsonArray out = new JsonArray();
		boolean fallingSelf = state.getBlock() instanceof FallingBlock;
		BlockState above = level.getBlockState(pos.above());
		boolean fallingAbove = above.getBlock() instanceof FallingBlock;
		if (fallingSelf || fallingAbove)
			out.add("falling-sand");
		if (fallingAbove || level.getBlockState(pos.above(2)).getBlock() instanceof FallingBlock)
			out.add("ceiling-collapse");

		boolean fluid = !state.getFluidState().isEmpty();
		boolean lava = isLava(state.getFluidState());
		for (Direction dir : Direction.values()) {
			FluidState neighbor = level.getBlockState(pos.relative(dir)).getFluidState();
			if (!neighbor.isEmpty())
				fluid = true;
			if (isLava(neighbor))
				lava = true;
		}
		if (fluid)
			out.add("fluids");
		if (lava)
			out.add("nearby-danger");

		// A non-solid block resting on this one drops when it is removed.
		if (!above.isAir() && above.getCollisionShape(level, pos.above()).isEmpty())
			out.add("self-support");
		return out;
	}

	private static boolean isLava(FluidState state) {
		return !state.isEmpty() && state.getType().isSame(Fluids.LAVA);
	}

	private static JsonArray unmet(LocalPlayer p, BlockState state, float blockSpeed, boolean requiresTool) {
		JsonArray out = new JsonArray();
		if (blockSpeed < 0)
			out.add("unbreakable");
		if (!requiresTool)
			return out;
		// A candidate list with no correct tool separates "level too low" from
		// "no tool at all" for the caller's upgrade decision.
		Inventory inv = p.getInventory();
		//? if >=1.21.5 {
		var items = inv.getNonEquipmentItems();
		//?} else
		/*var items = inv.items;*/
		boolean anyItem = false;
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.get(slot);
			if (stack.isEmpty())
				continue;
			anyItem = true;
			if (stack.isCorrectToolForDrops(state))
				return out;
		}
		out.add(anyItem ? "tool_level_too_low" : "no_tool");
		return out;
	}

	private static void unmetAdd(JsonObject o, String value) {
		JsonArray unmet = o.getAsJsonArray("unmet");
		if (unmet == null) {
			unmet = new JsonArray();
			o.add("unmet", unmet);
		}
		unmet.add(value);
	}

	private static String dimensionOf(LocalPlayer p) {
		//? if <1.21.11 {
		return p.level().dimension().location().toString();
		//?} else
		/*return p.level().dimension().identifier().toString();*/
	}
}
