package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * MC-4c player-life actions: sleep in a bed, read the respawn point, and respawn
 * after death.
 *
 * <p>Sleep and respawn are server-relayed client actions. The handlers poll the
 * client-observed state for a bounded window and report what they saw; a timeout
 * is reported honestly instead of being treated as success.
 */
public final class PlayerLifeHandlers {
	private PlayerLifeHandlers() {}

	public static void register(RpcRouter router) {
		router.register("player.sleep", ctx -> {
			requireControl();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			JsonObject out = new JsonObject();
			out.add("bedPosition", blockPosJson(pos));

			BlockState state = ClientMc.call(() -> ClientMc.level().getBlockState(pos));
			if (!(state.getBlock() instanceof BedBlock)) {
				out.addProperty("sleeping", false);
				out.addProperty("sleepTimer", 0);
				out.addProperty("error", "no_bed");
				return out;
			}

			// Right-click the bed through the vanilla block-use path (the same
			// path that opens doors), then poll the client sleep state.
			String failure = ClientMc.call(() -> {
				LocalPlayer player = ClientMc.player();
				aimAt(player, pos);
				Vec3 hitLoc = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				BlockHitResult hit = new BlockHitResult(hitLoc, Direction.UP, pos, false);
				InteractionResult result = ClientMc.gameMode().useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND);
				return result.consumesAction() ? null : "interaction_failed";
			});
			if (failure != null) {
				out.addProperty("sleeping", false);
				out.addProperty("sleepTimer", 0);
				out.addProperty("error", failure);
				return out;
			}

			long deadline = System.currentTimeMillis() + 3000;
			boolean sleeping = false;
			while (System.currentTimeMillis() < deadline) {
				sleeping = ClientMc.call(() -> ClientMc.player().isSleeping());
				if (sleeping)
					break;
				sleepQuietly(50);
			}
			sleeping = ClientMc.call(() -> ClientMc.player().isSleeping());
			out.addProperty("sleeping", sleeping);
			out.addProperty("sleepTimer", ClientMc.call(() -> ClientMc.player().getSleepTimer()));
			if (!sleeping) {
				// 1.21.1 does not expose the vanilla failure reason on the client;
				// report the timeout only, never a fabricated cause.
				out.addProperty("error", "not_sleeping");
			}
			return out;
		});

		router.register("player.getSpawn", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			// 1.21.1 exposes the respawn point on ServerPlayer only. An
			// integrated server is reachable locally; a dedicated server is
			// not, so the read says it cannot know instead of claiming "none".
			BlockPos spawn = null;
			ResourceKey<Level> dimension = null;
			var server = ClientMc.mc().getSingleplayerServer();
			if (server != null) {
				var serverPlayer = server.getPlayerList().getPlayer(p.getUUID());
				if (serverPlayer != null) {
					spawn = serverPlayer.getRespawnPosition();
					dimension = serverPlayer.getRespawnDimension();
				}
			} else {
				o.addProperty("unsupported", "dedicated_server");
			}
			o.addProperty("respawning", spawn != null);
			if (spawn != null) {
				o.add("position", blockPosJson(spawn));
			}
			if (dimension != null) {
				o.addProperty("dimension", dimension.location().toString());
			}
			o.addProperty("sleeping", p.isSleeping());
			o.addProperty("sleepTimer", p.getSleepTimer());
			return o;
		}));

		router.register("player.respawn", ctx -> {
			requireControl();
			boolean dead = ClientMc.call(() -> ClientMc.player().isDeadOrDying());
			JsonObject o = new JsonObject();
			if (!dead) {
				o.addProperty("respawned", false);
				o.addProperty("error", "not_dead");
				return o;
			}
			ClientMc.call(() -> {
				ClientMc.player().respawn();
				return Boolean.TRUE;
			});
			long deadline = System.currentTimeMillis() + 5000;
			boolean alive = false;
			while (System.currentTimeMillis() < deadline) {
				alive = ClientMc.call(() -> !ClientMc.player().isDeadOrDying());
				if (alive)
					break;
				sleepQuietly(50);
			}
			o.addProperty("respawned", alive);
			if (alive) {
				double[] position = ClientMc.call(() -> new double[]{
						ClientMc.player().getX(),
						ClientMc.player().getY(),
						ClientMc.player().getZ(),
				});
				JsonObject pos = new JsonObject();
				pos.addProperty("x", position[0]);
				pos.addProperty("y", position[1]);
				pos.addProperty("z", position[2]);
				o.add("position", pos);
				o.addProperty("dimension",
						ClientMc.call(() -> ClientMc.player().level().dimension().location().toString()));
			} else {
				o.addProperty("error", "respawn_timeout");
			}
			return o;
		});
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static void aimAt(LocalPlayer p, BlockPos pos) {
		double dx = pos.getX() + 0.5 - p.getX();
		double dy = pos.getY() + 0.5 - p.getEyeY();
		double dz = pos.getZ() + 0.5 - p.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = Mth.clamp((float) (-(Mth.atan2(dy, horizontal) * (180.0 / Math.PI))), -90.0F, 90.0F);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static JsonObject blockPosJson(BlockPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		return o;
	}

	/** Bounded poll sleep; restores the interrupt flag and stops on interruption. */
	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted while polling player state", e);
		}
	}
}
