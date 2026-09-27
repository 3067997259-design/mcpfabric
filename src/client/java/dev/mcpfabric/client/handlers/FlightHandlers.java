package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.flight.FlightController;
import net.minecraft.client.Minecraft;

/**
 * R2a flight session RPC: same-tick observation, the channel contract
 * (submit/status/revoke), the ignition dedup primitive, and the trajectory
 * batch. The handlers only parse and delegate; the contracts live in
 * {@link FlightController} and the per-tick driving is R2b.
 */
public final class FlightHandlers {
	private FlightHandlers() {}

	/** Same player-control gate the legacy movement drives use. */
	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl)
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
	}

	public static void register(RpcRouter router) {
		router.register("flight.observe", ctx -> {
			Minecraft mc = Minecraft.getInstance();
			return ClientMc.call(() -> FlightController.get().observeJson(mc));
		});

		router.register("flight.submit", ctx -> {
			requireControl();
			String sessionId = ctx.getString("sessionId");
			String controlSessionId = ctx.optString("controlSessionId", "");
			long generation = ctx.optLong("generation", 0);
			long revision = ctx.optLong("revision", 0);
			long deadlineMs = ctx.optLong("deadlineMs", System.currentTimeMillis() + 120_000);
			String dimension = ctx.optString("dimension", "");
			JsonObject channel = ctx.has("channel") && ctx.params().get("channel").isJsonObject()
					? ctx.params().getAsJsonObject("channel")
					: null;
			return ClientMc.call(() -> FlightController.get().submitChannel(sessionId, controlSessionId, generation, revision, deadlineMs, dimension, channel));
		});

		router.register("flight.status", ctx -> {
			long sinceTick = ctx.optLong("sinceTick", 0);
			return ClientMc.call(() -> FlightController.get().statusJson(sinceTick));
		});

		router.register("flight.revoke", ctx -> {
			requireControl();
			String sessionId = ctx.getString("sessionId");
			return ClientMc.call(() -> FlightController.get().revokeChannel(sessionId));
		});

		router.register("flight.boost", ctx -> {
			requireControl();
			String opId = ctx.getString("opId");
			return ClientMc.call(() -> FlightController.get().igniteFirework(opId));
		});
	}
}
