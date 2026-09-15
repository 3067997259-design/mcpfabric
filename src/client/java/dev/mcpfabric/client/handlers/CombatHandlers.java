package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;

/**
 * MC-4d ranged weapon control: start a per-tick bow/crossbow/trident task, read its
 * status, and cancel it.
 *
 * <p>The tick-by-tick work (equipping, aiming, charging, releasing, tracking the
 * projectile) runs inside {@link BotController}; these handlers only start it,
 * report it, and stop it. A start does its pre-flight check on the render thread
 * and returns the initial task state, so a missing weapon or arrow is reported
 * before any use begins.
 */
public final class CombatHandlers {
	private CombatHandlers() {}

	public static void register(RpcRouter router) {
		router.register("combat.start", ctx -> {
			requireControl();
			String weapon = ctx.getString("weapon");
			if (!weapon.equals("bow") && !weapon.equals("crossbow") && !weapon.equals("trident")) {
				throw RpcException.badRequest("Unknown weapon: " + weapon);
			}
			double tx = ctx.getDouble("targetX");
			double ty = ctx.getDouble("targetY");
			double tz = ctx.getDouble("targetZ");
			String targetUuid = ctx.optString("targetUuid", null);
			int maxShots = ctx.optInt("maxShots", 1);
			int chargeTicks = ctx.optInt("chargeTicks", weapon.equals("crossbow") ? 0 : 20);
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				return BotController.get().startCombat(weapon, tx, ty, tz, targetUuid, maxShots, chargeTicks, mc);
			});
		});

		router.register("combat.status", ctx -> ClientMc.call(() -> BotController.get().combatStatusJson()));

		router.register("combat.cancel", ctx -> {
			requireControl();
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				return BotController.get().cancelCombat(mc);
			});
		});
	}

	private static void requireControl() throws RpcException {
		if (!dev.mcpfabric.McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
