package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Shared item-stack JSON.
 *
 * <p>The player reads, the inventory reads and the menu snapshot must all describe a stack the
 * same way, so the model never sees two shapes for one item. The shape is: empty flag, or
 * id/count/name plus durability, enchantments, food and crossbow charge when present.
 */
public final class ItemJson {
	private ItemJson() {}

	@Nullable
	public static JsonObject of(ItemStack stack) {
		JsonObject o = new JsonObject();
		if (stack.isEmpty()) {
			o.addProperty("empty", true);
			return o;
		}
		o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		o.addProperty("name", stack.getHoverName().getString());
		if (stack.isDamageableItem()) {
			o.addProperty("damage", stack.getDamageValue());
			o.addProperty("maxDamage", stack.getMaxDamage());
		}
		// MC-4a: augment the snapshot so the model can reason about weapon
		// enchantments, consumables, and a loaded crossbow before acting.
		var enchantments = stack.getEnchantments();
		if (!enchantments.isEmpty()) {
			JsonObject ench = new JsonObject();
			for (var entry : enchantments.entrySet()) {
				// Enchantment is data-driven, so the id comes from the holder key
				// instead of a static BuiltInRegistries lookup (which does not exist).
				var key = entry.getKey().unwrapKey().orElse(null);
				if (key != null) ench.addProperty(key.location().toString(), entry.getIntValue());
			}
			o.add("enchantments", ench);
		}
		if (stack.get(DataComponents.FOOD) != null) {
			o.addProperty("food", true);
		}
		// A loaded crossbow keeps its projectiles in a component; the charged
		// flag and the projectile ids separate "loaded" from "empty" so the
		// model does not start a reload that would discard the loaded bolt.
		var charged = stack.get(DataComponents.CHARGED_PROJECTILES);
		if (charged != null) {
			JsonArray projectiles = new JsonArray();
			for (ItemStack projectile : charged.getItems()) {
				if (!projectile.isEmpty()) projectiles.add(BuiltInRegistries.ITEM.getKey(projectile.getItem()).toString());
			}
			// A component that exists but holds no projectile is the empty
			// post-shot state, not a loaded crossbow.
			if (projectiles.size() > 0) {
				o.addProperty("charged", true);
				o.add("chargedProjectiles", projectiles);
			}
		}
		return o;
	}
}
