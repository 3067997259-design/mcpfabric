package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Read-only state of the local player: position/vitals, inventory, equipment, effects. */
public final class LocalPlayerHandlers {
	private LocalPlayerHandlers() {}

	public static void register(RpcRouter router) {
		router.register("player.getState", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			JsonObject motion = new JsonObject();
			motion.addProperty("x", p.getDeltaMovement().x);
			motion.addProperty("y", p.getDeltaMovement().y);
			motion.addProperty("z", p.getDeltaMovement().z);
			o.add("motion", motion);
			o.addProperty("health", p.getHealth());
			o.addProperty("maxHealth", p.getMaxHealth());
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("saturation", p.getFoodData().getSaturationLevel());
			o.addProperty("air", p.getAirSupply());
			o.addProperty("maxAir", p.getMaxAirSupply());
			o.addProperty("xpLevel", p.experienceLevel);
			o.addProperty("xpProgress", p.experienceProgress);
			o.addProperty("onGround", p.onGround());
			o.addProperty("inWater", p.isInWater());
			o.addProperty("sprinting", p.isSprinting());
			o.addProperty("sneaking", p.isShiftKeyDown());
			o.addProperty("usingItem", p.isUsingItem());
			// MC-4a: use lifecycle. `usingTicks` is the charge progress the
			// remote layer polls while holding a bow/trident/food.
			o.addProperty("usingTicks", p.getTicksUsingItem());
			o.addProperty("usingHand", p.getUsedItemHand().name().toLowerCase());
			ItemStack useItem = p.getUseItem();
			o.addProperty("usingItemId", useItem.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(useItem.getItem()).toString());
			// MC-3c: flight state for the elytra mover (deploy, cruise, landing).
			o.addProperty("fallFlying", p.isFallFlying());
			o.addProperty("selectedSlot", selectedSlot(p.getInventory()));
			//? if <1.21.11 {
				o.addProperty("dimension", p.level().dimension().location().toString());
				//?} else
				/*o.addProperty("dimension", p.level().dimension().identifier().toString());*/
			var gm = ClientMc.mc().gameMode;
			o.addProperty("gameMode", gm != null ? gm.getPlayerMode().getName() : "unknown");
			// MC-3c X-10: the game host needs the local player identity to drop
			// its own chat echo. UUID is stable across mappings; the profile
			// name follows the 1.21.9 record accessor.
			o.addProperty("uuid", p.getUUID().toString());
			//? if <1.21.9 {
			o.addProperty("name", p.getGameProfile().getName());
			//?} else
			/*o.addProperty("name", p.getGameProfile().name());*/
			return o;
		}));

		router.register("player.getVehicle", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			var vehicle = p.getVehicle();
			JsonObject o = new JsonObject();
			if (vehicle == null) {
				o.addProperty("riding", false);
			} else {
				o.addProperty("riding", true);
				o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
				o.addProperty("uuid", vehicle.getUUID().toString());
			}
			return o;
		}));

		router.register("player.getInventory", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			Inventory inv = p.getInventory();
			//? if >=1.21.5 {
			var items = inv.getNonEquipmentItems(); // 0-8 hotbar, 9-35 main
			//?} else
			/*var items = inv.items;*/
			JsonObject o = new JsonObject();
			o.addProperty("selectedSlot", selectedSlot(inv));

			JsonArray hotbar = new JsonArray();
			for (int i = 0; i <= 8 && i < items.size(); i++) addItem(hotbar, i, items.get(i));
			o.add("hotbar", hotbar);

			JsonArray main = new JsonArray();
			for (int i = 9; i <= 35 && i < items.size(); i++) addItem(main, i, items.get(i));
			o.add("main", main);

			JsonArray armor = new JsonArray();
			for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack st = p.getItemBySlot(slot);
				if (!st.isEmpty()) {
					JsonObject j = ItemJson.of(st);
					j.addProperty("slot", slot.getName());
					armor.add(j);
				}
			}
			o.add("armor", armor);
			o.add("offhand", ItemJson.of(p.getOffhandItem()));
			return o;
		}));

		router.register("player.getEquipment", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			o.add("mainHand", ItemJson.of(p.getMainHandItem()));
			o.add("offHand", ItemJson.of(p.getOffhandItem()));
			o.add("helmet", ItemJson.of(p.getItemBySlot(EquipmentSlot.HEAD)));
			o.add("chest", ItemJson.of(p.getItemBySlot(EquipmentSlot.CHEST)));
			o.add("legs", ItemJson.of(p.getItemBySlot(EquipmentSlot.LEGS)));
			o.add("boots", ItemJson.of(p.getItemBySlot(EquipmentSlot.FEET)));
			return o;
		}));

		router.register("player.getStatusEffects", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonArray effects = new JsonArray();
			for (MobEffectInstance inst : p.getActiveEffects()) {
				JsonObject e = new JsonObject();
				e.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString());
				e.addProperty("amplifier", inst.getAmplifier());
				e.addProperty("durationTicks", inst.getDuration());
				e.addProperty("ambient", inst.isAmbient());
				e.addProperty("visible", inst.isVisible());
				effects.add(e);
			}
			JsonObject o = new JsonObject();
			o.add("effects", effects);
			return o;
		}));
	}

	/** The selected hotbar slot. The accessor replaced the public {@code selected} field in 1.21.5. */
	private static int selectedSlot(Inventory inv) {
		//? if >=1.21.5 {
		return inv.getSelectedSlot();
		//?} else
		/*return inv.selected;*/
	}

	private static void addItem(JsonArray arr, int slot, ItemStack stack) {
		if (stack.isEmpty()) return;
		JsonObject o = ItemJson.of(stack);
		o.addProperty("slot", slot);
		arr.add(o);
	}
}
