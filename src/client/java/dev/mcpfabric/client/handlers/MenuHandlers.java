package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.Optional;

/**
 * Menu (container / workstation) control: open a container, read its snapshot, click slots, close
 * it, and place a 3x3 recipe into a crafting table.
 *
 * <p>Slot numbers returned by {@code menu.snapshot} and accepted by {@code menu.click} belong to a
 * specific {@code containerId}. A click with a stale id is rejected with {@code menu_mismatch}; the
 * AIRI side must never reuse a slot number after the menu changed.
 */
public final class MenuHandlers {
	private MenuHandlers() {}

	public static void register(RpcRouter router) {
		router.register("menu.open", ctx -> {
			requireControl();
			Minecraft mc = ClientMc.mc();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			// The use must run on the render thread; the open-screen packet arrives
			// on a later tick, so the wait happens on this HTTP worker thread.
			int beforeId = ClientMc.call(() -> ClientMc.player().containerMenu.containerId);
			ClientMc.call(() -> {
				LocalPlayer player = ClientMc.player();
				Vec3 hitLoc = new Vec3(
						pos.getX() + 0.5 + face.getStepX() * 0.5,
						pos.getY() + 0.5 + face.getStepY() * 0.5,
						pos.getZ() + 0.5 + face.getStepZ() * 0.5);
				BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
				ClientMc.gameMode().useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND);
				return Boolean.TRUE;
			});
			long deadline = System.currentTimeMillis() + 2000;
			while (System.currentTimeMillis() < deadline) {
				JsonObject menu = ClientMc.call(() -> {
					LocalPlayer player = ClientMc.player();
					AbstractContainerMenu current = player.containerMenu;
					if (current == player.inventoryMenu || current.containerId == beforeId) return null;
					return identityOf(current);
				});
				if (menu != null) return menu;
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("opened", false);
			o.addProperty("error", "no_menu");
			return o;
		});

		router.register("menu.snapshot", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			// The player inventory menu is "no container open", not a snapshot
			// target: reading it hits vanilla paths that do not exist for it.
			if (p.containerMenu == p.inventoryMenu) {
				JsonObject o = new JsonObject();
				o.addProperty("opened", false);
				o.addProperty("error", "no_menu");
				return o;
			}
			AbstractContainerMenu menu = p.containerMenu;
			JsonObject o = new JsonObject();
			o.addProperty("containerId", menu.containerId);
			o.addProperty("type", menuTypeId(menu));
			o.addProperty("slots", menu.slots.size());
			o.add("carried", ItemJson.of(menu.getCarried()));
			JsonArray slots = new JsonArray();
			for (int i = 0; i < menu.slots.size(); i++) slots.add(slotJson(menu, i));
			o.add("slots", slots);
			if (menu instanceof AbstractFurnaceMenu furnace) {
				// 1.21.1 exposes the flame/cook ratios but not the raw ContainerData
				// counters; the ratios are the public, version-stable reads.
				JsonObject f = new JsonObject();
				f.addProperty("lit", furnace.isLit());
				f.addProperty("litProgress", furnace.getLitProgress());
				f.addProperty("cookProgress", furnace.getBurnProgress());
				o.add("furnace", f);
			}
			return o;
		}));

		router.register("menu.click", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int containerId = ctx.getInt("containerId");
			int slot = ctx.getInt("slot");
			if (p.containerMenu.containerId != containerId) {
				JsonObject o = new JsonObject();
				o.addProperty("accepted", false);
				o.addProperty("error", "menu_mismatch");
				return o;
			}
			if (slot < 0 || slot >= p.containerMenu.slots.size()) {
				JsonObject o = new JsonObject();
				o.addProperty("accepted", false);
				o.addProperty("error", "slot_out_of_range");
				return o;
			}
			boolean quickMove = ctx.optBool("quickMove", false);
			click(gm, containerId, slot, quickMove, p);
			JsonObject o = new JsonObject();
			o.addProperty("accepted", true);
			o.addProperty("containerId", containerId);
			o.add("slot", slotJson(p.containerMenu, slot));
			o.add("carried", ItemJson.of(p.containerMenu.getCarried()));
			return o;
		}));

		router.register("menu.close", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			if (p.containerMenu == p.inventoryMenu) {
				o.addProperty("closed", false);
				o.addProperty("error", "no_menu");
				return o;
			}
			p.closeContainer();
			o.addProperty("closed", true);
			return o;
		}));

		router.register("menu.craft", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			if (!(p.containerMenu instanceof CraftingMenu menu)) {
				JsonObject o = new JsonObject();
				o.addProperty("placed", false);
				o.addProperty("error", "menu_not_crafting");
				return o;
			}
			ResourceLocation id = ResourceLocation.tryParse(ctx.getString("recipeId"));
			if (id == null) {
				JsonObject o = new JsonObject();
				o.addProperty("placed", false);
				o.addProperty("error", "bad_recipe_id");
				return o;
			}
			RecipeManager manager = p.level().getRecipeManager();
			Optional<RecipeHolder<?>> found = manager.byKey(id);
			if (found.isEmpty()) {
				JsonObject o = new JsonObject();
				o.addProperty("placed", false);
				o.addProperty("error", "unknown_recipe");
				return o;
			}
			RecipeHolder<?> holder = found.get();
			// Clear the 3x3 grid so an earlier craft cannot leave residue behind.
			for (int slot = 1; slot <= 9; slot++) {
				if (!menu.getSlot(slot).getItem().isEmpty()) {
					click(gm, menu.containerId, slot, true, p);
				}
			}
			gm.handlePlaceRecipe(menu.containerId, holder, false);
			ItemStack result = holder.value().getResultItem(p.registryAccess());
			JsonObject o = new JsonObject();
			o.addProperty("placed", true);
			JsonObject expected = new JsonObject();
			expected.addProperty("id", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
			expected.addProperty("count", result.getCount());
			o.add("expected", expected);
			return o;
		}));

		// MC-4f: generic workstation button. Enchanting level buttons, the
		// stonecutter recipe buttons, the loom pattern buttons and beacon effects
		// all settle through the server-side clickMenuButton, reached with the
		// vanilla button-click packet, so one primitive covers every station.
		router.register("menu.button", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			if (p.containerMenu == p.inventoryMenu) {
				JsonObject o = new JsonObject();
				o.addProperty("sent", false);
				o.addProperty("error", "no_menu");
				return o;
			}
			int id = ctx.getInt("id");
			AbstractContainerMenu menu = p.containerMenu;
			int containerId = menu.containerId;
			String type = menuTypeId(menu);
			// MC-4f live fix: the client menu's clickMenuButton only mutates the
			// local copy (enchanting showed accepted:true with no lapis or XP
			// consumed) and the next sync reverts it. Send the vanilla
			// button-click packet so the server settles the click on its own
			// menu. NOTICE: this lambda already runs on the render thread; a
			// nested ClientMc.call would be queued behind this same task and
			// deadlock until its timeout (the packet then fires only after the
			// error was returned).
			ClientMc.gameMode().handleInventoryButtonClick(containerId, id);
			JsonObject o = new JsonObject();
			o.addProperty("sent", true);
			o.addProperty("containerId", containerId);
			o.addProperty("type", type);
			return o;
		}));

		// Beacons do not use clickMenuButton: BeaconMenu never implements it, and
		// the chosen effects travel in ServerboundSetBeaconPacket as optional
		// primary/secondary effect holders, exactly as vanilla BeaconScreen sends
		// them. The generic menu.button path therefore cannot configure a beacon,
		// so it gets this dedicated primitive. Both effect ids must resolve in the
		// mob-effect registry; an unknown id is reported before any packet.
		router.register("menu.set_beacon_effects", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			if (!(p.containerMenu instanceof BeaconMenu menu)) {
				o.addProperty("sent", false);
				o.addProperty("error", "menu_not_beacon");
				return o;
			}
			ResourceLocation primaryId = ResourceLocation.tryParse(ctx.getString("primary"));
			Optional<Holder.Reference<MobEffect>> primaryRef =
					primaryId == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.getHolder(primaryId);
			if (primaryRef.isEmpty()) {
				o.addProperty("sent", false);
				o.addProperty("error", "no_effect");
				return o;
			}
			Optional<Holder<MobEffect>> secondary = Optional.empty();
			String secondaryName = ctx.optString("secondary", null);
			if (secondaryName != null) {
				ResourceLocation secondaryId = ResourceLocation.tryParse(secondaryName);
				Optional<Holder.Reference<MobEffect>> secondaryRef =
						secondaryId == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.getHolder(secondaryId);
				if (secondaryRef.isEmpty()) {
					o.addProperty("sent", false);
					o.addProperty("error", "no_effect");
					return o;
				}
				secondary = secondaryRef.map(h -> h);
			}
			// NOTICE: this lambda already runs on the render thread; a nested
			// ClientMc.call would be queued behind this same task and deadlock
			// until its timeout, so the send happens inline like menu.button.
			p.connection.send(new ServerboundSetBeaconPacket(primaryRef.map(h -> h), secondary));
			o.addProperty("sent", true);
			o.addProperty("containerId", menu.containerId);
			o.addProperty("type", menuTypeId(menu));
			return o;
		}));

		// MC-4f: villager trade selection. setSelectionHint tells the server which
		// offer is highlighted; tryMoveItems fills the input slots from the
		// inventory. The result is not taken here: the caller reads the menu.
		router.register("menu.select_trade", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			if (!(p.containerMenu instanceof MerchantMenu menu)) {
				o.addProperty("accepted", false);
				o.addProperty("error", "menu_not_trade");
				return o;
			}
			int index = ctx.getInt("index");
			MerchantOffers offers = menu.getOffers();
			if (index < 0 || index >= offers.size()) {
				o.addProperty("accepted", false);
				o.addProperty("error", "no_offer");
				return o;
			}
			menu.setSelectionHint(index);
			menu.tryMoveItems(index);
			// R8 live fix: setSelectionHint and tryMoveItems are client-local
			// predictions. The vanilla merchant screen also sends the select
			// packet, which is what moves the inputs on the server. Without it
			// the server menu stays empty and taking the result does nothing.
			p.connection.send(new ServerboundSelectTradePacket(index));
			o.addProperty("accepted", true);
			o.addProperty("containerId", menu.containerId);
			o.addProperty("type", menuTypeId(menu));
			o.add("offer", offerJson(offers.get(index)));
			return o;
		}));

		// MC-4f: anvil naming. setItemName is the menu's own local rename path:
		// it updates the result slot only. The vanilla anvil screen also sends
		// the rename packet (R8 live fix), which is what renames on the server.
		// The applied name is returned, already bounded to the vanilla limit.
		router.register("menu.set_name", ctx -> ClientMc.call(() -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			if (!(p.containerMenu instanceof AnvilMenu menu)) {
				o.addProperty("accepted", false);
				o.addProperty("error", "menu_not_anvil");
				return o;
			}
			String text = ctx.optString("text", "");
			if (text.length() > AnvilMenu.MAX_NAME_LENGTH) text = text.substring(0, AnvilMenu.MAX_NAME_LENGTH);
			boolean changed = menu.setItemName(text);
			// R8 live fix: the server needs the rename packet to apply the name
			// to its own anvil menu and the result stack.
			p.connection.send(new ServerboundRenameItemPacket(text));
			o.addProperty("accepted", true);
			o.addProperty("changed", changed);
			o.addProperty("name", text);
			o.addProperty("containerId", menu.containerId);
			o.addProperty("type", menuTypeId(menu));
			return o;
		}));
	}

	/** Summary of one merchant offer: the result stack and the readable inputs. */
	private static JsonObject offerJson(MerchantOffer offer) {
		JsonObject o = new JsonObject();
		o.add("result", ItemJson.of(offer.getResult()));
		JsonArray inputs = new JsonArray();
		ItemStack costA = offer.getCostA();
		if (!costA.isEmpty()) inputs.add(ItemJson.of(costA));
		ItemStack costB = offer.getCostB();
		if (!costB.isEmpty()) inputs.add(ItemJson.of(costB));
		o.add("inputs", inputs);
		o.addProperty("outOfStock", offer.isOutOfStock());
		return o;
	}

	private static JsonObject identityOf(AbstractContainerMenu menu) {
		JsonObject o = new JsonObject();
		o.addProperty("opened", true);
		o.addProperty("containerId", menu.containerId);
		o.addProperty("type", menuTypeId(menu));
		o.addProperty("slots", menu.slots.size());
		return o;
	}

	private static String menuTypeId(AbstractContainerMenu menu) {
		ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
		return key == null ? "unknown" : key.toString();
	}

	private static JsonObject slotJson(AbstractContainerMenu menu, int index) {
		Slot slot = menu.getSlot(index);
		JsonObject o = ItemJson.of(slot.getItem());
		o.addProperty("index", index);
		o.addProperty("container", slot.container.getClass().getSimpleName());
		// Player-inventory slots report their inventory index so the caller can
		// map a menu slot to hotbar/main/armor/offhand without guessing.
		if (slot.container instanceof Inventory) {
			o.addProperty("invSlot", slot.getContainerSlot());
		}
		return o;
	}

	/**
	 * Click a container slot. {@code handleInventoryMouseClick(..., ClickType, ...)} became
	 * {@code handleContainerInput(..., ContainerInput, ...)} in 26.1 (same constant names).
	 */
	private static void click(MultiPlayerGameMode gm, int containerId, int slot, boolean quickMove, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, 0, quickMove ? ClickType.QUICK_MOVE : ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, 0, quickMove ? net.minecraft.world.inventory.ContainerInput.QUICK_MOVE : net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static Direction parseFace(String name) {
		Direction d = Direction.byName(name.toLowerCase());
		return d == null ? Direction.UP : d;
	}
}
