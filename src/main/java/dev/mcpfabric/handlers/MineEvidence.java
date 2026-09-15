package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.Levels;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side break and drop evidence (CD-M2).
 *
 * <p>{@code PlayerBlockBreakEvents.AFTER} is the break fact. The drop list is
 * evaluated in the same server transaction with the real tool, so the source
 * quantity reflects the actual loot rules (fortune, silk touch). Newly loaded
 * {@link ItemEntity}s near the break are attached through
 * {@code ServerEntityEvents.ENTITY_LOAD}, which is the server hook for the
 * generated entities. A merged, split, stolen or unloaded entity is never
 * claimed by one player's break: the ledger keeps what it observed and the
 * consumer proves a lower bound.
 */
public final class MineEvidence {
	private MineEvidence() {}

	/** Item entities within this radius of the break are attributed to it. */
	private static final double DROP_RADIUS = 2.0;
	/** How long after the break new item entities are still attributed to it. */
	private static final long DROP_WINDOW_TICKS = 10L;
	/** Bounded history; the oldest record is dropped first. */
	private static final int MAX_RECORDS = 128;

	private static final Object LOCK = new Object();
	private static final List<Record> RECORDS = new ArrayList<>();

	private static final class Record {
		final UUID player;
		final String dimension;
		final int x;
		final int y;
		final int z;
		final String blockStateId;
		final long tick;
		final long windowEnd;
		final Map<String, Integer> drops = new HashMap<>();
		/** Item entity uuids per item id; a merged or stolen stack stays here. */
		final Map<String, Set<UUID>> entityUuidsByItem = new HashMap<>();

		Record(UUID player, String dimension, BlockPos pos, String blockStateId, long tick) {
			this.player = player;
			this.dimension = dimension;
			this.x = pos.getX();
			this.y = pos.getY();
			this.z = pos.getZ();
			this.blockStateId = blockStateId;
			this.tick = tick;
			this.windowEnd = tick + DROP_WINDOW_TICKS;
		}
	}

	/** Registers the break and item-entity listeners; call once from mod init. */
	public static void registerEvents() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel level))
				return;
			recordBreak(level, player, pos, state, blockEntity);
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity item)
				attachDrop(world, item);
		});
	}

	private static void recordBreak(ServerLevel level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
		Record record = new Record(
				player.getUUID(),
				Levels.dimensionId(level),
				pos,
				BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
				level.getGameTime());
		// The generated quantity is evaluated in the break transaction with the
		// tool the player actually used; the entity hook then confirms it.
		for (ItemStack drop : state.getBlock().getDrops(state, level, pos, blockEntity, player, player.getMainHandItem())) {
			if (drop.isEmpty())
				continue;
			String id = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
			record.drops.merge(id, drop.getCount(), Integer::sum);
		}
		synchronized (LOCK) {
			RECORDS.add(record);
			while (RECORDS.size() > MAX_RECORDS)
				RECORDS.remove(0);
		}
	}

	private static void attachDrop(ServerLevel world, ItemEntity entity) {
		BlockPos pos = entity.blockPosition();
		String id = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString();
		long tick = world.getGameTime();
		synchronized (LOCK) {
			// Newest first: the entity belongs to the most recent open window.
			for (int i = RECORDS.size() - 1; i >= 0; i--) {
				Record record = RECORDS.get(i);
				if (tick > record.windowEnd)
					break;
				if (!record.dimension.equals(Levels.dimensionId(world)))
					continue;
				if (Math.abs(pos.getX() - record.x) > DROP_RADIUS || Math.abs(pos.getY() - record.y) > DROP_RADIUS
						|| Math.abs(pos.getZ() - record.z) > DROP_RADIUS) {
					continue;
				}
				record.entityUuidsByItem.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(entity.getUUID());
				record.drops.putIfAbsent(id, 0);
				return;
			}
		}
	}

	/** Registers the read-only evidence query. */
	public static void registerRpc(RpcRouter router) {
		router.register("mine.breakEvidence", ctx -> {
			int x = (int) Math.floor(ctx.getDouble("x"));
			int y = (int) Math.floor(ctx.getDouble("y"));
			int z = (int) Math.floor(ctx.getDouble("z"));
			String dimension = ctx.optString("dimension", null);
			String playerUuid = ctx.optString("playerUuid", null);
			long startTick = ctx.optLong("startTick", Long.MIN_VALUE);

			JsonArray records = new JsonArray();
			synchronized (LOCK) {
				for (int i = RECORDS.size() - 1; i >= 0 && records.size() < 4; i--) {
					Record record = RECORDS.get(i);
					if (record.x != x || record.y != y || record.z != z)
						continue;
					if (dimension != null && !dimension.isBlank() && !record.dimension.equals(dimension))
						continue;
					if (playerUuid != null && !playerUuid.isBlank() && !record.player.toString().equalsIgnoreCase(playerUuid))
						continue;
					if (startTick != Long.MIN_VALUE && record.tick + 5 < startTick)
						continue;

					JsonObject entry = new JsonObject();
					entry.addProperty("dimension", record.dimension);
					entry.addProperty("playerUuid", record.player.toString());
					entry.addProperty("blockStateId", record.blockStateId);
					entry.addProperty("tick", record.tick);
					JsonArray drops = new JsonArray();
					for (Map.Entry<String, Integer> drop : record.drops.entrySet()) {
						JsonObject d = new JsonObject();
						d.addProperty("itemId", drop.getKey());
						d.addProperty("count", drop.getValue());
						JsonArray uuids = new JsonArray();
						for (UUID uuid : record.entityUuidsByItem.getOrDefault(drop.getKey(), Set.of()))
							uuids.add(uuid.toString());
						if (uuids.size() > 0)
							d.add("entityUuids", uuids);
						drops.add(d);
					}
					entry.add("drops", drops);
					records.add(entry);
				}
			}
			JsonObject o = new JsonObject();
			o.add("records", records);
			return o;
		});
	}
}
