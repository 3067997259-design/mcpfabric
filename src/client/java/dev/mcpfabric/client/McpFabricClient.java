package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.handlers.ClientChatHandlers;
import dev.mcpfabric.client.handlers.CombatHandlers;
import dev.mcpfabric.client.handlers.ContentHandlers;
import dev.mcpfabric.client.handlers.ControlHandlers;
import dev.mcpfabric.client.handlers.CraftHandlers;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.handlers.InventoryHandlers;
import dev.mcpfabric.client.handlers.LocalPlayerHandlers;
import dev.mcpfabric.client.handlers.MenuHandlers;
import dev.mcpfabric.client.handlers.MineHandlers;
import dev.mcpfabric.client.handlers.MovementHandlers;
import dev.mcpfabric.client.handlers.NavHandlers;
import dev.mcpfabric.client.handlers.PlayerLifeHandlers;
import dev.mcpfabric.client.handlers.VisionHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint. Registers all client-only handlers into the shared router started by
 * {@link McpFabric} and drives the {@link BotController} once per client tick.
 */
public class McpFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RpcRouter router = McpFabric.router();
		if (router == null) {
			McpFabric.LOGGER.error("[mcpfabric] router not initialized; client handlers unavailable");
			return;
		}

		LocalPlayerHandlers.register(router);
		ControlHandlers.register(router);
		InteractHandlers.register(router);
		MineHandlers.register(router);
		CraftHandlers.register(router);
		InventoryHandlers.register(router);
		MenuHandlers.register(router);
		ContentHandlers.register(router);
		PlayerLifeHandlers.register(router);
		CombatHandlers.register(router);
		MovementHandlers.register(router);
		VisionHandlers.register(router);
		NavHandlers.register(router);
		ClientChatHandlers.register(router); // client variant of chat.send (speaks as local player)
		ClientEvents.register(McpFabric.events());
		ClientControlGuard.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> BotController.get().onClientTick(client));

		McpFabric.LOGGER.info("[mcpfabric] client handlers registered");
	}
}
