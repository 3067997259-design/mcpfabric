package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.ControlOwnership;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Movement and look control for the local player. */
public final class ControlHandlers {
	private ControlHandlers() {}

	public static void register(RpcRouter router) {
		router.register("control.setInput", ctx -> {
			// CD-0 §3.1: a revoked session or a stale sequence must not set input
			// again. Unscoped callers without a session id still work.
			if (!ControlOwnership.accept(ctx.optString("controlSessionId", null), ctx.optLong("sequence", 0L))) {
				throw new RpcException("stale_control_session",
						"Control session is revoked or the sequence is stale.", null);
			}
			BotController.get().setMovement(
					ctx.optBoolean("forward"),
					ctx.optBoolean("back"),
					ctx.optBoolean("left"),
					ctx.optBoolean("right"),
					ctx.optBoolean("jump"),
					ctx.optBoolean("sneak"),
					ctx.optBoolean("sprint"));
			return Json.ok("input updated");
		});

		router.register("control.stop", ctx -> {
			// A stop revokes input ownership so the session cannot be revived.
			ControlOwnership.revoke();
			BotController.get().stopAllMovement();
			return Json.ok("stopped");
		});

		router.register("control.jumpOnce", ctx -> {
			BotController.get().jumpOnce();
			return Json.ok("jump");
		});

		router.register("control.look", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			float yaw = p.getYRot();
			float pitch = p.getXRot();
			if (ctx.has("yaw")) yaw = (float) ctx.getDouble("yaw");
			if (ctx.has("pitch")) pitch = (float) ctx.getDouble("pitch");
			if (ctx.has("deltaYaw")) yaw += (float) ctx.getDouble("deltaYaw");
			if (ctx.has("deltaPitch")) pitch += (float) ctx.getDouble("deltaPitch");
			pitch = Mth.clamp(pitch, -90.0F, 90.0F);
			applyLook(p, yaw, pitch);
			return look(p);
		}));

		router.register("control.lookAt", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			double dx = ctx.getDouble("x") - p.getX();
			double dy = ctx.getDouble("y") - p.getEyeY();
			double dz = ctx.getDouble("z") - p.getZ();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
			applyLook(p, yaw, Mth.clamp(pitch, -90.0F, 90.0F));
			return look(p);
		}));

		router.register("control.startUsing", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			BotController.get().setUseHeld(true);
			ClientMc.mc().options.keyUse.setDown(true);
			// Key state alone never raises a click, so the first use must be
			// explicit; the same pattern the eat reflex uses.
			ClientMc.gameMode().useItem(p, InteractionHand.MAIN_HAND);
			return usingState(p);
		}));

		router.register("control.releaseUsing", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientMc.mc().options.keyUse.setDown(false);
			BotController.get().setUseHeld(false);
			// Normal release: `releaseUsingItem` fires chargeables
			// (bow/crossbow/trident) and finishes food use.
			ClientMc.gameMode().releaseUsingItem(p);
			return usingState(p);
		}));

		router.register("control.stopUsing", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientMc.mc().options.keyUse.setDown(false);
			BotController.get().setUseHeld(false);
			// Abort: `stopUsingItem` clears the use without firing chargeables.
			p.stopUsingItem();
			return usingState(p);
		}));
	}

	/** The live use state, shared by start/release/stop so each path reports the same shape. */
	private static JsonObject usingState(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("using", p.isUsingItem());
		o.addProperty("usingTicks", p.getTicksUsingItem());
		o.addProperty("usingHand", p.getUsedItemHand().name().toLowerCase());
		ItemStack use = p.getUseItem();
		o.addProperty("usingItemId", use.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(use.getItem()).toString());
		return o;
	}

	private static void applyLook(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static JsonObject look(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		return o;
	}
}
