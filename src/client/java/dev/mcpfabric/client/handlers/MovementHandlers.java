package dev.mcpfabric.client.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

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
			List<BotController.JumpEdge> edges = jumpEdgesOf(ctx.getString("edgesJson"));
			if (edges.isEmpty())
				throw RpcException.badRequest("edgesJson must hold at least one edge.");
			return ClientMc.call(() -> BotController.get().startJump(edges, landingIntent, deadlineMs));
		});

		router.register("movement.jumpAppend", ctx -> {
			requireControl();
			List<BotController.JumpEdge> edges = jumpEdgesOf(ctx.getString("edgesJson"));
			return ClientMc.call(() -> BotController.get().appendJump(edges));
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
	 * Parses a JSON array of jump edges.
	 *
	 * A climbing chain is one list, so the wire shape is an array rather than
	 * repeated prefixed parameters; the host owns the planner and serializes it.
	 */
	private static List<BotController.JumpEdge> jumpEdgesOf(String json) {
		List<BotController.JumpEdge> edges = new ArrayList<>();
		for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
			JsonObject object = element.getAsJsonObject();
			BotController.JumpEdge edge = new BotController.JumpEdge();
			edge.edgeId = optString(object, "edgeId", "");
			edge.fromX = optDouble(object, "fromX", 0);
			edge.fromY = optDouble(object, "fromY", 0);
			edge.fromZ = optDouble(object, "fromZ", 0);
			edge.targetX = optDouble(object, "targetX", 0);
			edge.targetY = optDouble(object, "targetY", 0);
			edge.targetZ = optDouble(object, "targetZ", 0);
			edge.takeoffX = optDouble(object, "takeoffX", 0);
			edge.takeoffZ = optDouble(object, "takeoffZ", 0);
			edge.dirX = optDouble(object, "dirX", 0);
			edge.dirZ = optDouble(object, "dirZ", 0);
			edge.sprint = object.has("sprint") && object.get("sprint").getAsBoolean();
			edge.brake = object.has("brake") && object.get("brake").getAsBoolean();
			edge.takeoffRadius = optDouble(object, "takeoffRadius", 0.35);
			edge.landingRadius = optDouble(object, "landingRadius", 0.7);
			edges.add(edge);
		}
		return edges;
	}

	private static String optString(JsonObject object, String key, String fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
	}

	private static double optDouble(JsonObject object, String key, double fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
