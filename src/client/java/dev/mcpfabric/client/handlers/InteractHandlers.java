package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Interaction: break/place blocks, use items, attack/use entities, drop held item. */
public final class InteractHandlers {
	private InteractHandlers() {}

	public static void register(RpcRouter router) {
		router.register("interact.breakBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = faceToward(pos, p.getEyePosition());
			String mode = ctx.optString("mode", "survival");
			JsonObject o = new JsonObject();
			if ("instant".equals(mode)) {
				boolean broke = gm.destroyBlock(pos);
				p.swing(InteractionHand.MAIN_HAND);
				o.addProperty("broke", broke);
				o.addProperty("mode", "instant");
			} else {
				gm.startDestroyBlock(pos, face);
				BotController.get().startMining(pos, face);
				p.swing(InteractionHand.MAIN_HAND);
				o.addProperty("started", true);
				o.addProperty("mode", "survival");
				o.addProperty("note", "Mining continues each tick; poll get_block to confirm it broke.");
			}
			return o;
		}));

		router.register("interact.placeBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			// MC-4f precise placement additions. Defaults keep the old behaviour:
			// no sneak, no yaw change, no block verification.
			boolean sneak = ctx.optBool("sneak", false);
			boolean hasYaw = ctx.has("yaw");
			float yaw = hasYaw ? (float) ctx.getDouble("yaw") : 0.0f;
			String expectBlockId = ctx.optString("expectBlockId", null);
			Vec3 hitLoc = new Vec3(
					pos.getX() + 0.5 + face.getStepX() * 0.5,
					pos.getY() + 0.5 + face.getStepY() * 0.5,
					pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);

			boolean previousSneak = p.isShiftKeyDown();
			boolean previousInputSneak = p.input.shiftKeyDown;
			float previousYaw = p.getYRot();
			float previousYawO = p.yRotO;
			float previousHeadYaw = p.getYHeadRot();
			if (sneak) {
				// NOTICE:
				// `setShiftKeyDown` affects the local entity flag; the input flag
				// and key mapping drive what actually reaches the server for the
				// "sneak to place instead of open" branch. All three are set.
				// Root cause: server-side GUI suppression reads the synced input,
				// not the client entity flag.
				// Removal condition: a live test confirms one flag is sufficient.
				p.setShiftKeyDown(true);
				p.input.shiftKeyDown = true;
				ClientMc.mc().options.keyShift.setDown(true);
			}
			if (hasYaw) {
				p.setYRot(yaw);
				p.yRotO = yaw;
				p.setYHeadRot(yaw);
			}
			InteractionResult result;
			try {
				result = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				p.swing(InteractionHand.MAIN_HAND);
			} finally {
				// Restore only what this call changed, so a plain place leaves the
				// player's real pose untouched.
				if (sneak) {
					p.setShiftKeyDown(previousSneak);
					p.input.shiftKeyDown = previousInputSneak;
					ClientMc.mc().options.keyShift.setDown(previousSneak);
				}
				if (hasYaw) {
					p.setYRot(previousYaw);
					p.yRotO = previousYawO;
					p.setYHeadRot(previousHeadYaw);
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			if (expectBlockId != null) {
				// A placement lands in the clicked face's neighbour, but clicking
				// a replaceable block (grass/snow) replaces that block itself.
				// Both positions are checked so neither case is a false negative.
				BlockPos adjacent = pos.relative(face);
				BlockPos actual = blockIdAt(p, adjacent).equals(expectBlockId) ? adjacent
						: blockIdAt(p, pos).equals(expectBlockId) ? pos : null;
				BlockPos report = actual != null ? actual : adjacent;
				o.addProperty("placed", actual != null);
				o.addProperty("blockId", blockIdAt(p, report));
				JsonObject position = new JsonObject();
				position.addProperty("x", report.getX());
				position.addProperty("y", report.getY());
				position.addProperty("z", report.getZ());
				o.add("position", position);
			}
			return o;
		}));

		router.register("interact.useItem", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			InteractionResult result = gm.useItem(p, InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("vehicle.boardNearest", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			double radius = ctx.optDouble("radius", 4.0);
			// MC-3c: an optional entity type filter lets the strider mover pick a
			// strider without boarding a nearer cart or boat.
			String type = ctx.optString("type", null);
			Entity target = null;
			double best = Double.MAX_VALUE;
			for (Entity entity : p.level().getEntities(p, p.getBoundingBox().inflate(radius))) {
				if (!(entity instanceof Boat) && !(entity instanceof AbstractMinecart)
						&& !(entity instanceof AbstractHorse) && !(entity instanceof Strider)) {
					continue;
				}
				if (type != null && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(type)) {
					continue;
				}
				double distance = entity.distanceToSqr(p);
				if (distance < best) {
					best = distance;
					target = entity;
				}
			}
			JsonObject o = new JsonObject();
			if (target == null) {
				o.addProperty("boarded", false);
				return o;
			}
			gm.interact(p, target, InteractionHand.MAIN_HAND);
			p.swing(InteractionHand.MAIN_HAND);
			o.addProperty("boarded", true);
			o.addProperty("uuid", target.getUUID().toString());
			o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
			return o;
		}));

		router.register("interact.attackEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			gm.attack(p, e);
			p.swing(InteractionHand.MAIN_HAND);
			return Json.ok("attacked " + e.getName().getString());
		}));

		router.register("interact.useEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			// B-08: a thrown potion is thrown along the look direction, not at the
			// entity. Aim at the target and use the item when the held stack is a
			// throwable, so a splash or lingering potion lands on the target.
			ItemStack held = p.getMainHandItem();
			if (held.getItem() instanceof ThrowablePotionItem) {
				double dx = e.getX() - p.getX();
				double dy = (e.getY() + e.getBbHeight() * 0.5) - p.getEyeY();
				double dz = e.getZ() - p.getZ();
				double horizontal = Math.sqrt(dx * dx + dz * dz);
				p.setYRot((float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
				p.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
				InteractionResult result = gm.useItem(p, InteractionHand.MAIN_HAND);
				JsonObject o = new JsonObject();
				o.addProperty("result", String.valueOf(result));
				o.addProperty("thrown", true);
				return o;
			}
			//? if <26.1 {
			InteractionResult result = gm.interact(p, e, InteractionHand.MAIN_HAND);
			//?} else
			/*InteractionResult result = gm.interact(p, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);*/
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			return o;
		}));

		router.register("interact.dropItem", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			boolean whole = ctx.optBool("wholeStack", false);
			p.drop(whole);
			return Json.ok(whole ? "dropped stack" : "dropped one");
		}));
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static Entity findEntity(String uuidStr) throws RpcException {
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + uuidStr);
		}
		for (Entity e : ClientMc.level().entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		throw RpcException.notFound("No visible entity with uuid " + uuid);
	}

	private static Direction parseFace(String name) {
		Direction d = Direction.byName(name.toLowerCase());
		return d == null ? Direction.UP : d;
	}

	/** Registry id of the block at a position, or an empty string when unknown. */
	private static String blockIdAt(LocalPlayer p, BlockPos pos) {
		var key = BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(pos).getBlock());
		return key == null ? "" : key.toString();
	}

	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		if (az >= ax && az >= ay) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
		return dy > 0 ? Direction.UP : Direction.DOWN;
	}
}
