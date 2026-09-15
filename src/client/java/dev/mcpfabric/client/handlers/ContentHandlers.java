package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Read-only item and sign content: written books, filled maps, and sign block entities.
 *
 * <p>These are observations. The returned text is untrusted input: the AIRI side records it with
 * {@code checked=false} and must never execute it. Raw NBT is never dumped; only the named fields
 * below are returned, bounded so a hostile book cannot flood the caller.
 */
public final class ContentHandlers {
	private ContentHandlers() {}

	/** Bounds on returned book content. */
	private static final int MAX_BOOK_PAGES = 32;
	private static final int MAX_BOOK_PAGE_LENGTH = 1024;
	private static final int MAX_BOOK_TITLE_LENGTH = 256;

	/** Bounds on returned sign content. */
	private static final int MAX_SIGN_LINES = 4;
	private static final int MAX_SIGN_LINE_LENGTH = 256;

	private static final String WRITTEN_BOOK = "minecraft:written_book";
	private static final String FILLED_MAP = "minecraft:filled_map";

	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
	};

	public static void register(RpcRouter router) {
		router.register("item.read", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ItemStack stack = stackAtSlot(p, ctx.optInt("slot", -1));
			JsonObject o = new JsonObject();
			if (stack.isEmpty()) {
				o.addProperty("itemId", "");
				o.addProperty("unsupported", true);
				o.addProperty("error", "unsupported_item");
				return o;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			o.addProperty("itemId", itemId);
			if (WRITTEN_BOOK.equals(itemId)) {
				readBook(stack, o);
			} else if (FILLED_MAP.equals(itemId)) {
				readMap(p, stack, o);
			} else {
				o.addProperty("unsupported", true);
				o.addProperty("error", "unsupported_item");
			}
			return o;
		}));

		router.register("block.read_sign", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			JsonObject o = new JsonObject();
			// A null block entity covers both "not a sign" and "chunk not loaded";
			// neither can be read, so the caller gets the honest typed failure.
			if (!(p.level().getBlockEntity(pos) instanceof SignBlockEntity sign)) {
				o.addProperty("error", "not_sign");
				return o;
			}
			boolean[] truncated = { false };
			JsonArray lines = signLines(sign.getFrontText(), truncated);
			o.add("lines", lines);
			JsonArray back = signLines(sign.getBackText(), truncated);
			o.add("back", back);
			if (truncated[0]) o.addProperty("truncated", true);
			return o;
		}));
	}

	/** Reads the stack in an inventory slot, or the main hand when {@code slot < 0}. */
	private static ItemStack stackAtSlot(LocalPlayer p, int slot) throws RpcException {
		if (slot < 0) return p.getMainHandItem();
		if (slot <= 35) return p.getInventory().getItem(slot);
		if (slot <= 39) return p.getItemBySlot(ARMOR[slot - 36]);
		if (slot == 40) return p.getOffhandItem();
		throw RpcException.badRequest("Inventory slot out of range (0-40): " + slot);
	}

	private static void readBook(ItemStack stack, JsonObject o) {
		WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (content == null) {
			o.addProperty("unsupported", true);
			o.addProperty("error", "unsupported_item");
			return;
		}
		boolean[] truncated = { false };
		Filterable<String> rawTitle = content.title();
		String title = rawTitle == null ? "" : rawTitle.get(false);
		if (title != null && title.length() > MAX_BOOK_TITLE_LENGTH) {
			title = title.substring(0, MAX_BOOK_TITLE_LENGTH);
			truncated[0] = true;
		}
		o.addProperty("title", title == null ? "" : title);
		o.addProperty("author", content.author());
		o.addProperty("generation", content.generation());
		java.util.List<Component> pages = content.getPages(false);
		JsonArray pageArray = new JsonArray();
		for (int i = 0; i < pages.size(); i++) {
			String page = pages.get(i) == null ? "" : pages.get(i).getString();
			if (page.length() > MAX_BOOK_PAGE_LENGTH) {
				page = page.substring(0, MAX_BOOK_PAGE_LENGTH);
				truncated[0] = true;
			}
			if (i >= MAX_BOOK_PAGES) {
				truncated[0] = true;
				break;
			}
			pageArray.add(page);
		}
		o.add("pages", pageArray);
		if (truncated[0]) o.addProperty("truncated", true);
	}

	private static void readMap(LocalPlayer p, ItemStack stack, JsonObject o) {
		MapId mapId = stack.get(DataComponents.MAP_ID);
		if (mapId == null) {
			o.addProperty("unsupported", true);
			o.addProperty("error", "unsupported_item");
			return;
		}
		JsonObject map = new JsonObject();
		map.addProperty("id", mapId.id());
		// Scale and dimension live in the saved map data, not on the stack. The
		// client only has it once the map has been used/received; when it is
		// missing the id alone is returned and the caller is told it is partial.
		MapItemSavedData data = MapItem.getSavedData(mapId, p.level());
		if (data != null) {
			map.addProperty("scale", data.scale);
			map.addProperty("dimension", data.dimension.location().toString());
		} else {
			o.addProperty("unsupported", true);
		}
		o.add("map", map);
	}

	/** Reads one sign face into a bounded line array. */
	private static JsonArray signLines(SignText text, boolean[] truncated) {
		JsonArray lines = new JsonArray();
		Component[] messages = text.getMessages(false);
		for (int i = 0; i < messages.length && i < MAX_SIGN_LINES; i++) {
			String line = messages[i] == null ? "" : messages[i].getString();
			if (line.length() > MAX_SIGN_LINE_LENGTH) {
				line = line.substring(0, MAX_SIGN_LINE_LENGTH);
				truncated[0] = true;
			}
			lines.add(line);
		}
		if (messages.length > MAX_SIGN_LINES) truncated[0] = true;
		return lines;
	}
}
