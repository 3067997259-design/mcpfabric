package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * MC-4e advanced movement: the riptide launch.
 *
 * <p>Riptide is player movement, not a projectile shot, so it has its own RPC
 * and task instead of the {@code combat.*} weapon contract. The tick-by-tick
 * charge/release/track work runs inside {@link BotController}; these handlers
 * only start it, report it, and abort it. A start checks the client-side
 * conditions (riptide trident, water/rain) before any use and returns the task
 * state, so an unmet condition is reported without a charge.
 */
public final class MovementHandlers {
	private MovementHandlers() {}

	public static void register(RpcRouter router) {
		// Step 3: per-tick one-block hop. The host plans the takeoff line and
		// the landing and this task executes them a tick at a time, because the
		// host's 150 ms sampling cannot time a takeoff or correct a landing.
		router.register("movement.jump", ctx -> {
			requireControl();
			long deadlineMs = ctx.optLong("deadlineMs", System.currentTimeMillis() + 8_000);
			String landingIntent = ctx.optString("landingIntent", "stop");
			BotController.JumpEdge edge = jumpEdgeOf(ctx, "");
			// Optional second edge: executed in the same task, so no host round
			// trip sits between the hops.
			BotController.JumpEdge next = ctx.has("nextTargetX") ? jumpEdgeOf(ctx, "next") : null;
			return ClientMc.call(() -> BotController.get().startJump(edge, next, landingIntent, deadlineMs));
		});

		router.register("movement.jumpStatus", ctx -> ClientMc.call(() -> BotController.get().jumpStatusJson()));

		router.register("movement.jumpCancel", ctx -> {
			requireControl();
			return ClientMc.call(() -> BotController.get().cancelJump());
		});

		router.register("movement.riptide", ctx -> {
			requireControl();
			double tx = ctx.getDouble("targetX");
			double ty = ctx.getDouble("targetY");
			double tz = ctx.getDouble("targetZ");
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				return BotController.get().startRiptide(tx, ty, tz, mc);
			});
		});

		router.register("movement.riptideStatus", ctx -> ClientMc.call(() -> {
			JsonObject o = BotController.get().riptideStatusJson();
			// The start check uses the combined isInWaterOrRain, but the server
			// settles riptide with its own rain state. Report the raw client
			// inputs so a client/server mismatch (review backlog 9-3) is
			// diagnosable: in water, in rain, and the interpolated rain level.
			LocalPlayer p = ClientMc.player();
			// Entity.isInRain is private in 1.21.1, so reproduce it from the
			// public Level.isRainingAt at the feet and at the bounding-box top,
			// exactly as vanilla does. Newer versions expose isInRain publicly;
			// this equivalent form compiles across the whole stonecutter range.
			BlockPos pos = p.blockPosition();
			boolean inRain = p.level().isRainingAt(pos)
					|| p.level().isRainingAt(BlockPos.containing(pos.getX(), p.getBoundingBox().maxY, pos.getZ()));
			o.addProperty("inWater", p.isInWater());
			o.addProperty("inRain", inRain);
			o.addProperty("rainLevel", p.level().getRainLevel(1.0f));
			return o;
		}));

		router.register("movement.riptideCancel", ctx -> {
			requireControl();
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				return BotController.get().cancelRiptide(mc);
			});
		});
	}

	/**
	 * Reads one jump edge from the request.
	 *
	 * @param prefix empty for the first edge, `next` for the queued one: the
	 * second edge carries the same fields with a `next` prefix.
	 */
	private static BotController.JumpEdge jumpEdgeOf(RpcContext ctx, String prefix) throws RpcException {
		boolean next = !prefix.isEmpty();
		BotController.JumpEdge edge = new BotController.JumpEdge();
		edge.edgeId = ctx.optString(next ? "nextEdgeId" : "edgeId", "");
		edge.fromX = ctx.optDouble("fromX", 0);
		edge.fromY = ctx.optDouble("fromY", 0);
		edge.fromZ = ctx.optDouble("fromZ", 0);
		edge.targetX = ctx.getDouble(next ? "nextTargetX" : "targetX");
		edge.targetY = ctx.getDouble(next ? "nextTargetY" : "targetY");
		edge.targetZ = ctx.getDouble(next ? "nextTargetZ" : "targetZ");
		edge.takeoffX = ctx.getDouble(next ? "nextTakeoffX" : "takeoffX");
		edge.takeoffZ = ctx.getDouble(next ? "nextTakeoffZ" : "takeoffZ");
		edge.dirX = ctx.getDouble(next ? "nextDirX" : "dirX");
		edge.dirZ = ctx.getDouble(next ? "nextDirZ" : "dirZ");
		edge.sprint = ctx.optBool("sprint", false);
		edge.brake = ctx.optBool("brake", false);
		edge.takeoffRadius = ctx.optDouble("takeoffRadius", 0.35);
		edge.landingRadius = ctx.optDouble("landingRadius", 0.7);
		return edge;
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
