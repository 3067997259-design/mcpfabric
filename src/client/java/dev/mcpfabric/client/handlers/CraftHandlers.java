package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.Optional;

/**
 * Client-side crafting: place a known recipe into the player's 2x2 grid and
 * quick-move the result into the inventory.
 *
 * v1 supports only shaped/shapeless recipes that fit the player grid; recipes
 * that need a crafting table return zero crafts with `missing=true` (honest
 * failure, never a fabricated success).
 */
public final class CraftHandlers {
	private CraftHandlers() {}

	public static void register(RpcRouter router) {
		router.register("craft.byRecipe", ctx -> ClientMc.call(() -> {
			requireControl();
			Minecraft mc = ClientMc.mc();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();

			ResourceLocation id = ResourceLocation.tryParse(ctx.getString("recipeId"));
			if (id == null) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "bad_recipe_id");
				return o;
			}
			ClientPacketListener conn = mc.getConnection();
			if (conn == null) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "not_connected");
				return o;
			}
			RecipeManager manager = conn.getRecipeManager();
			Optional<RecipeHolder<?>> found = manager.byKey(id);
			if (found.isEmpty()) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "unknown_recipe");
				return o;
			}
			RecipeHolder<?> holder = found.get();
			Recipe<?> recipe = holder.value();
			if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "unsupported_recipe_type");
				return o;
			}
			// v1 guard: only recipes that fit the player's 2x2 grid. A 3-wide
			// pattern would be partially placed and leave residue in the grid.
			if (recipe instanceof ShapedRecipe shaped
					&& (shaped.getWidth() > 2 || shaped.getHeight() > 2)) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "recipe_needs_crafting_table");
				return o;
			}
			if (recipe instanceof ShapelessRecipe shapeless && shapeless.getIngredients().size() > 4) {
				o.addProperty("crafted", 0);
				o.addProperty("error", "recipe_needs_crafting_table");
				return o;
			}

			ItemStack result = recipe.getResultItem(p.registryAccess());
			String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();

			// Two-beat protocol, because the integrated server settles a
			// placement on a later tick. Claim first: cleaning the grid before
			// a claim can return ingredients the server already consumed.
			ItemStack filled = p.inventoryMenu.getSlot(0).getItem();
			if (!filled.isEmpty()) {
				JsonObject claimed = new JsonObject();
				claimed.addProperty("claimed", true);
				JsonObject output = new JsonObject();
				output.addProperty("id", BuiltInRegistries.ITEM.getKey(filled.getItem()).toString());
				output.addProperty("count", filled.getCount());
				claimed.add("output", output);
				gm.handleInventoryMouseClick(p.inventoryMenu.containerId, 0, 0, ClickType.QUICK_MOVE, p);
				return claimed;
			}

			// No pending result: return leftovers from an earlier attempt so
			// they do not block placement, then start the next placement.
			for (int slot = 1; slot <= 4; slot++) {
				if (!p.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
					gm.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 0, ClickType.QUICK_MOVE, p);
				}
			}

			gm.handlePlaceRecipe(p.inventoryMenu.containerId, holder, false);
			JsonObject placed = new JsonObject();
			placed.addProperty("placed", true);
			JsonObject expected = new JsonObject();
			expected.addProperty("id", resultId);
			expected.addProperty("count", result.getCount());
			placed.add("expected", expected);
			return placed;
		}));
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
