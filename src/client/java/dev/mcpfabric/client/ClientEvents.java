package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.events.EventBus;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Client-side event listeners that feed the shared {@link EventBus}. */
public final class ClientEvents {
	private ClientEvents() {}

	public static void register(EventBus events) {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", message.getString());
			d.addProperty("overlay", overlay);
			events.emit("system_message", d);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", message.getString());
			if (sender != null) {
				// MC-3c X-10: the game host drops its own chat echo by uuid.
				//? if <1.21.9 {
				d.addProperty("sender", sender.getName());
				d.addProperty("uuid", sender.getId().toString());
				//?} else
				/*d.addProperty("sender", sender.name());
				d.addProperty("uuid", sender.id().toString());*/
			}
			events.emit("chat", d);
		});
	}
}
