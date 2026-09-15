package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
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

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
