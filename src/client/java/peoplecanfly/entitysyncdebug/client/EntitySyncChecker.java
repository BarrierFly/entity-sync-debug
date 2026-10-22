package peoplecanfly.entitysyncdebug.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import peoplecanfly.entitysyncdebug.EntitySyncDebug;

public final class EntitySyncChecker {
	private static final int CHECK_INTERVAL_TICKS = 20;
	private static final int WORLD_JOIN_GRACE_TICKS = 60;
	private static final int MISSING_GRACE_TICKS = 40;
	private static final double RANGE_MARGIN_BLOCKS = 2.0;

	private static final Map<UUID, MissingState> MISSING = new HashMap<>();

	private static long ticks;
	private static int ticksInWorld;
	private static ClientWorld lastWorld;
	private static ClientPlayerEntity lastPlayer;

	private EntitySyncChecker() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(EntitySyncChecker::onEndClientTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> TrackedEntityRegistry.clear());
	}

	private static void onEndClientTick(MinecraftClient client) {
		ticks++;

		IntegratedServer server = client.getServer();
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;

		if (server == null || world == null || player == null) {
			resetSession();
			return;
		}

		if (world != lastWorld || player != lastPlayer) {
			lastWorld = world;
			lastPlayer = player;
			ticksInWorld = 0;
			MISSING.clear();
		} else {
			ticksInWorld++;
		}

		if (ticksInWorld < WORLD_JOIN_GRACE_TICKS || client.isPaused()) {
			return;
		}

		if (ticks % CHECK_INTERVAL_TICKS == 0) {
			requestSnapshot(client, server, world, player);
		}
	}

	private static void resetSession() {
		lastWorld = null;
		lastPlayer = null;
		ticksInWorld = 0;
		MISSING.clear();
	}

	private static void requestSnapshot(MinecraftClient client, IntegratedServer server, ClientWorld clientWorld, ClientPlayerEntity clientPlayer) {
		UUID playerUuid = clientPlayer.getUuid();
		RegistryKey<World> dimension = clientWorld.getRegistryKey();
		String dimensionId = dimension.getValue().toString();

		server.execute(() -> {
			ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);

			if (serverPlayer == null || !serverPlayer.getEntityWorld().getRegistryKey().equals(dimension)) {
				return;
			}

			ServerWorld serverWorld = serverPlayer.getEntityWorld();
			int viewDistance = Math.max(2, Math.min(serverPlayer.getViewDistance(), server.getPlayerManager().getViewDistance()));
			double maxHorizontalDistance = viewDistance * 16.0 + RANGE_MARGIN_BLOCKS;
			Vec3d playerPos = serverPlayer.getEntityPos();
			long serverTick = server.getTicks();
			List<TrackedEntitySnapshot> snapshots = new ArrayList<>();

			for (UUID entityUuid : TrackedEntityRegistry.snapshot(playerUuid)) {
				Entity entity = serverWorld.getEntity(entityUuid);

				if (entity == null || entity.isRemoved() || entity == serverPlayer) {
					continue;
				}

				Vec3d delta = entity.getEntityPos().subtract(playerPos);
				double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

				if (horizontalDistance > maxHorizontalDistance) {
					continue;
				}

				Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
				snapshots.add(new TrackedEntitySnapshot(
						entity.getUuid(),
						typeId == null ? "unknown" : typeId.toString(),
						entity.getName().getString(),
						entity.getId(),
						entity.getEntityPos(),
						entity.getChunkPos().x,
						entity.getChunkPos().z,
						horizontalDistance,
						delta.length(),
						entity.getVehicle() == null ? null : entity.getVehicle().getUuid(),
						entity.getType().getMaxTrackDistance(),
						viewDistance,
						serverTick,
						dimensionId
				));
			}

			client.execute(() -> handleSnapshot(client, clientWorld, snapshots));
		});
	}

	private static void handleSnapshot(MinecraftClient client, ClientWorld expectedWorld, List<TrackedEntitySnapshot> tracked) {
		if (client.world != expectedWorld || client.player == null) {
			return;
		}

		Set<UUID> trackedIds = new HashSet<>(tracked.size());

		for (TrackedEntitySnapshot snapshot : tracked) {
			trackedIds.add(snapshot.uuid());
		}

		MISSING.keySet().removeIf(uuid -> !trackedIds.contains(uuid));

		long now = ticks;
		List<MissingState> newlyReported = new ArrayList<>();

		for (TrackedEntitySnapshot snapshot : tracked) {
			Entity clientEntity = expectedWorld.getEntity(snapshot.uuid());

			if (clientEntity != null && !clientEntity.isRemoved()) {
				MissingState recovered = MISSING.remove(snapshot.uuid());

				if (recovered != null && recovered.reported) {
					EntitySyncDebug.LOGGER.info(
							"Client entity is present again: {} ({}, uuid {}) after {} ticks",
							snapshot.typeId(), snapshot.name(), snapshot.uuid(), now - recovered.firstMissingTick
					);
				}

				continue;
			}

			boolean chunkLoaded = expectedWorld.getChunkManager()
					.getChunk(snapshot.chunkX(), snapshot.chunkZ(), ChunkStatus.FULL, false) != null;
			MissingState state = MISSING.get(snapshot.uuid());

			if (state == null) {
				state = new MissingState(now);
				MISSING.put(snapshot.uuid(), state);
			}

			state.snapshot = snapshot;
			state.clientChunkLoaded = chunkLoaded;

			if (!state.reported && now - state.firstMissingTick >= MISSING_GRACE_TICKS) {
				state.reported = true;
				newlyReported.add(state);
			}
		}

		if (newlyReported.isEmpty()) {
			return;
		}

		long missingCount = MISSING.values().stream().filter(state -> state.reported).count();
		EntitySyncDebug.LOGGER.warn(
				"Entity sync check: {} newly missing, {} currently missing, {} server-tracked entities in {}",
				newlyReported.size(), missingCount, tracked.size(), expectedWorld.getRegistryKey().getValue()
		);

		for (MissingState state : newlyReported) {
			logMissing(state, client);
		}

		client.player.sendMessage(
				Text.translatable("entity-sync-debug.missing_entities", missingCount).formatted(Formatting.RED),
				false
		);
	}

	private static void logMissing(MissingState state, MinecraftClient client) {
		TrackedEntitySnapshot snapshot = state.snapshot;
		String clientPlayerPos = client.player == null ? "unknown" : formatPos(client.player.getEntityPos());

		EntitySyncDebug.LOGGER.warn(String.format(Locale.ROOT, """
						Missing server-tracked entity on the client:
						  world: %s
						  entity: %s (%s) [server entity id %d, uuid %s]
						  server position: %s (chunk %d, %d)
						  client player position: %s
						  distance from player: %.2f blocks horizontal, %.2f blocks 3D
						  view distance: %d chunks; entity tracking range: %d chunks
						  vehicle uuid: %s
						  client chunk loaded: %s
						  server tick: %d; absent for at least %d ticks
						""",
				snapshot.dimensionId(),
				snapshot.typeId(), snapshot.name(), snapshot.serverEntityId(), snapshot.uuid(),
				formatPos(snapshot.serverPos()), snapshot.chunkX(), snapshot.chunkZ(),
				clientPlayerPos,
				snapshot.horizontalDistance(), snapshot.distance(),
				snapshot.viewDistanceChunks(), snapshot.trackRangeChunks(),
				snapshot.vehicleUuid() == null ? "none" : snapshot.vehicleUuid().toString(),
				state.clientChunkLoaded,
				snapshot.serverTick(), ticks - state.firstMissingTick
		));
	}

	private static String formatPos(Vec3d pos) {
		return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z);
	}

	private static final class MissingState {
		private final long firstMissingTick;
		private TrackedEntitySnapshot snapshot;
		private boolean clientChunkLoaded;
		private boolean reported;

		private MissingState(long firstMissingTick) {
			this.firstMissingTick = firstMissingTick;
		}
	}
}
