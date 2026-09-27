package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.flight.FlightController;
import dev.mcpfabric.client.reflex.ReflexController;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * P1 cleanup guard: releases every control surface when the world, the
 * connection, the player, or the bridge heartbeat disappears.
 *
 * Cleanup is self-contained on the game side: it never waits for AIRI to send
 * a stop request. Movement intent clears immediately; key releases and item
 * stops are applied on the next client tick (within two game ticks).
 */
public final class ClientControlGuard {
	private ClientControlGuard() {}

	private static boolean wasDead;
	private static boolean hadLevel;
	private static long lastSeenControlRequestAt;

	/**
	 * Clears every control surface and revokes input ownership.
	 *
	 * CD-0 §3.1: revoking makes a later request from the same session fail, so a
	 * cancelled drive cannot be revived by a stray control call.
	 */
	private static void clearControls(String reason) {
		BotController.get().clearAll(reason);
		// R2b: a lifecycle stop ends the flight channel too. Without this the
		// cancelled launch macro and the still-accepted session would outlive
		// the input revocation.
		FlightController.get().externalStop(reason);
		ControlOwnership.revoke();
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// A new connection is the reconnect signal: arm the controller,
			// require a new control session, and refresh the heartbeat baseline.
			wasDead = false;
			ControlOwnership.reset();
			lastSeenControlRequestAt = System.currentTimeMillis();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			clearControls("disconnected");
			hadLevel = false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(ClientControlGuard::onTick);
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer p = mc.player;

		// Returning to the title screen fires no disconnect event.
		if (hadLevel && mc.level == null) {
			clearControls("world_exit");
			hadLevel = false;
		}
		else if (mc.level != null) {
			hadLevel = true;
		}

		// One cleanup per death transition.
		boolean dead = p != null && (p.isDeadOrDying() || p.getHealth() <= 0.0F);
		if (dead && !wasDead) {
			clearControls("death");
		}
		wasDead = dead;

		// Survival reflexes (mc-0d) run before the BotController applies input
		// state this tick, so an escape/reaction takes effect immediately.
		ReflexController.get().onClientTick(mc);

		// Bridge heartbeat: no control request for too long while controls are
		// held. CD-0 §3.1: only control methods renew this clock, so a plain
		// observation cannot keep a cancelled or stale drive alive.
		long timeout = McpFabric.config().heartbeatTimeoutMs;
		if (timeout <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		long lastRequest = McpFabric.router() != null ? McpFabric.router().lastControlRequestAt() : now;
		if (lastRequest != lastSeenControlRequestAt) {
			lastSeenControlRequestAt = lastRequest;
		}
		else if (BotController.get().isDriving()
				&& !FlightController.get().hasLiveChannel()
				&& now - lastSeenControlRequestAt > timeout) {
			// A live flight channel is exempt: it is a finite autonomous window
			// that ends on its own deadline, death, disconnect, dimension
			// change or revoke. The controller enforces the deadline later in
			// this same tick, so the end reason stays `deadline`.
			clearControls("bridge_timeout");
			// Avoid clearing on every silent tick.
			lastSeenControlRequestAt = now;
		}
	}
}
