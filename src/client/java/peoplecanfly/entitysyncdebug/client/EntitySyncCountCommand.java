package peoplecanfly.entitysyncdebug.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import peoplecanfly.entitysyncdebug.EntitySyncDebug;

public final class EntitySyncCountCommand {
	private static final int MIN_VIEW_DISTANCE_CHUNKS = 2;
	private static final int DUMP_VERIFY_DELAY_TICKS = 40;

	private static final List<PendingDump> PENDING_DUMPS = new ArrayList<>();
	private static long clientTicks;

	private EntitySyncCountCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("entitysyncdebug")
						.then(ClientCommandManager.literal("counts")
								.executes(context -> execute(context.getSource(), false))
								.then(ClientCommandManager.literal("dump")
										.executes(context -> execute(context.getSource(), true))))));
		ClientTickEvents.END_CLIENT_TICK.register(EntitySyncCountCommand::onClientTick);
	}

	private static int execute(FabricClientCommandSource source, boolean dump) {
		MinecraftClient client = source.getClient();
		IntegratedServer server = client.getServer();
		ClientWorld clientWorld = client.world;
		ClientPlayerEntity clientPlayer = client.player;

		if (server == null || clientWorld == null || clientPlayer == null) {
			source.sendError(Text.translatable("entity-sync-debug.counts.singleplayer_only"));
			return 0;
		}

		UUID playerUuid = clientPlayer.getUuid();
		RegistryKey<World> dimension = clientWorld.getRegistryKey();
		Vec3d center = clientPlayer.getEntityPos();

		server.execute(() -> {
			ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);

			if (serverPlayer == null || !serverPlayer.getEntityWorld().getRegistryKey().equals(dimension)) {
				client.execute(() -> source.sendError(Text.translatable("entity-sync-debug.counts.unavailable")));
				return;
			}

			ServerWorld serverWorld = serverPlayer.getEntityWorld();
			int viewDistanceChunks = Math.max(
					MIN_VIEW_DISTANCE_CHUNKS,
					Math.min(serverPlayer.getViewDistance(), server.getPlayerManager().getViewDistance())
			);
			double radius = viewDistanceChunks * 16.0;
			double radiusSquared = radius * radius;
			ServerSnapshot snapshot = collectServerEntities(serverWorld, center, radiusSquared);

			client.execute(() -> {
				if (client.world != clientWorld || client.player == null) {
					source.sendError(Text.translatable("entity-sync-debug.counts.unavailable"));
					return;
				}

				Map<String, Integer> clientCounts = countEntities(clientWorld.getEntities(), center, radiusSquared);
				sendCounts(source, dimension, viewDistanceChunks, snapshot.counts(), clientCounts);

				if (!dump) {
					return;
				}

				List<ServerEntityInfo> missing = new ArrayList<>();

				for (ServerEntityInfo info : snapshot.entities()) {
					Entity clientEntity = clientWorld.getEntity(info.uuid());

					if (clientEntity == null || clientEntity.isRemoved()) {
						missing.add(info);
					}
				}

				if (missing.isEmpty()) {
					source.sendFeedback(Text.translatable("entity-sync-debug.counts.dump.none").formatted(Formatting.GRAY));
					return;
				}

				source.sendFeedback(Text.translatable("entity-sync-debug.counts.dump.start", missing.size()).formatted(Formatting.GOLD));
				EntitySyncDebug.LOGGER.info(
						"Force-syncing {} entities missing on the client in {}",
						missing.size(), dimension.getValue()
				);
				dumpMissing(client, server, playerUuid, dimension, clientWorld, missing);
			});
		});

		return 1;
	}

	private static ServerSnapshot collectServerEntities(ServerWorld world, Vec3d center, double radiusSquared) {
		Map<String, Integer> counts = new HashMap<>();
		List<ServerEntityInfo> entities = new ArrayList<>();

		for (Entity entity : world.iterateEntities()) {
			if (!isInRange(entity, center, radiusSquared)) {
				continue;
			}

			String typeId = entityTypeId(entity);
			counts.merge(typeId, 1, Integer::sum);
			entities.add(new ServerEntityInfo(
					entity.getUuid(),
					typeId,
					entity.getId(),
					entity.getEntityPos(),
					entity.getChunkPos().x,
					entity.getChunkPos().z
			));
		}

		return new ServerSnapshot(counts, entities);
	}

	private static Map<String, Integer> countEntities(Iterable<? extends Entity> entities, Vec3d center, double radiusSquared) {
		Map<String, Integer> counts = new HashMap<>();

		for (Entity entity : entities) {
			if (!isInRange(entity, center, radiusSquared)) {
				continue;
			}

			counts.merge(entityTypeId(entity), 1, Integer::sum);
		}

		return counts;
	}

	private static boolean isInRange(Entity entity, Vec3d center, double radiusSquared) {
		Vec3d delta = entity.getEntityPos().subtract(center);
		return delta.x * delta.x + delta.z * delta.z <= radiusSquared;
	}

	private static String entityTypeId(Entity entity) {
		Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
		return typeId == null ? "unknown" : typeId.toString();
	}

	private static void sendCounts(FabricClientCommandSource source, RegistryKey<World> dimension, int viewDistanceChunks,
			Map<String, Integer> serverCounts, Map<String, Integer> clientCounts) {
		Set<String> types = new TreeSet<>();
		types.addAll(serverCounts.keySet());
		types.addAll(clientCounts.keySet());

		MutableText report = Text.translatable(
				"entity-sync-debug.counts.header",
				dimension.getValue().toString(),
				viewDistanceChunks * 16,
				viewDistanceChunks
		).formatted(Formatting.GOLD);

		int serverTotal = 0;
		int clientTotal = 0;

		for (String type : types) {
			int serverCount = serverCounts.getOrDefault(type, 0);
			int clientCount = clientCounts.getOrDefault(type, 0);
			serverTotal += serverCount;
			clientTotal += clientCount;

			MutableText line;

			if (serverCount > clientCount) {
				line = Text.translatable("entity-sync-debug.counts.missing", type, serverCount, clientCount, serverCount - clientCount)
						.formatted(Formatting.RED);
			} else if (clientCount > serverCount) {
				line = Text.translatable("entity-sync-debug.counts.extra", type, serverCount, clientCount, clientCount - serverCount)
						.formatted(Formatting.AQUA);
			} else {
				line = Text.translatable("entity-sync-debug.counts.match", type, serverCount, clientCount)
						.formatted(Formatting.GRAY);
			}

			report.append(Text.literal("\n")).append(line);
		}

		if (types.isEmpty()) {
			report.append(Text.literal("\n")).append(Text.translatable("entity-sync-debug.counts.empty").formatted(Formatting.GRAY));
		}

		report.append(Text.literal("\n")).append(formatTotal(serverTotal, clientTotal));
		source.sendFeedback(report);
	}

	private static MutableText formatTotal(int serverCount, int clientCount) {
		if (serverCount > clientCount) {
			return Text.translatable("entity-sync-debug.counts.total_missing", serverCount, clientCount, serverCount - clientCount)
					.formatted(Formatting.RED);
		}

		if (clientCount > serverCount) {
			return Text.translatable("entity-sync-debug.counts.total_extra", serverCount, clientCount, clientCount - serverCount)
					.formatted(Formatting.AQUA);
		}

		return Text.translatable("entity-sync-debug.counts.total_match", serverCount, clientCount).formatted(Formatting.GRAY);
	}

	private static void dumpMissing(MinecraftClient client, IntegratedServer server, UUID playerUuid,
			RegistryKey<World> dimension, ClientWorld expectedWorld, List<ServerEntityInfo> missing) {
		server.execute(() -> {
			try {
				ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);

				if (serverPlayer == null || !serverPlayer.getEntityWorld().getRegistryKey().equals(dimension)) {
					client.execute(() -> {
						EntitySyncDebug.LOGGER.warn("Entity dump aborted: integrated server player or world is no longer available");
						sendChat(client, Text.translatable("entity-sync-debug.counts.dump.unavailable").formatted(Formatting.RED));
					});
					return;
				}

				ServerWorld serverWorld = serverPlayer.getEntityWorld();
				EntityTrackerEntry.TrackerPacketSender packetSender = createDirectPacketSender(serverPlayer);
				List<ServerEntityInfo> sent = new ArrayList<>();
				List<DumpFailure> failures = new ArrayList<>();

				for (ServerEntityInfo info : missing) {
					Entity entity = serverWorld.getEntity(info.uuid());

					if (entity == null || entity.isRemoved()) {
						DumpFailure failure = new DumpFailure(info, "entity no longer exists on the server", null);
						failures.add(failure);
						logDumpFailure(failure);
						continue;
					}

					try {
						EntityTrackerEntry entry = new EntityTrackerEntry(
								serverWorld,
								entity,
								entity.getType().getTrackTickInterval(),
								entity.getType().alwaysUpdateVelocity(),
								packetSender
						);
						entry.sendPackets(serverPlayer, packet -> serverPlayer.networkHandler.sendPacket(packet));
						sent.add(info);
					} catch (Exception exception) {
						DumpFailure failure = new DumpFailure(info, "exception while sending spawn packets", exception);
						failures.add(failure);
						logDumpFailure(failure);
					}
				}

				List<ServerEntityInfo> sentCopy = List.copyOf(sent);
				List<DumpFailure> failuresCopy = List.copyOf(failures);
				client.execute(() -> PENDING_DUMPS.add(new PendingDump(
						clientTicks + DUMP_VERIFY_DELAY_TICKS,
						expectedWorld,
						dimension,
						playerUuid,
						sentCopy,
						failuresCopy
				)));
			} catch (Exception exception) {
				EntitySyncDebug.LOGGER.error("Entity dump failed on the server thread", exception);
				client.execute(() -> sendChat(client, Text.translatable("entity-sync-debug.counts.dump.failed").formatted(Formatting.RED)));
			}
		});
	}

	private static EntityTrackerEntry.TrackerPacketSender createDirectPacketSender(ServerPlayerEntity player) {
		return new EntityTrackerEntry.TrackerPacketSender() {
			@Override
			public void sendToListeners(Packet<? super ClientPlayPacketListener> packet) {
				player.networkHandler.sendPacket(packet);
			}

			@Override
			public void sendToSelfAndListeners(Packet<? super ClientPlayPacketListener> packet) {
				player.networkHandler.sendPacket(packet);
			}

			@Override
			public void sendToListenersIf(Packet<? super ClientPlayPacketListener> packet, Predicate<ServerPlayerEntity> predicate) {
				if (predicate.test(player)) {
					player.networkHandler.sendPacket(packet);
				}
			}
		};
	}

	private static void onClientTick(MinecraftClient client) {
		clientTicks++;

		if (PENDING_DUMPS.isEmpty()) {
			return;
		}

		Iterator<PendingDump> iterator = PENDING_DUMPS.iterator();

		while (iterator.hasNext()) {
			PendingDump dump = iterator.next();

			if (clientTicks >= dump.verifyAtTick) {
				iterator.remove();
				beginDumpVerification(client, dump);
			}
		}
	}

	private static void beginDumpVerification(MinecraftClient client, PendingDump dump) {
		if (client.world != dump.expectedWorld || client.player == null) {
			EntitySyncDebug.LOGGER.warn("Dump verification skipped: client world changed or player is unavailable");
			sendChat(client, Text.translatable("entity-sync-debug.counts.dump.world_changed").formatted(Formatting.RED));
			return;
		}

		IntegratedServer server = client.getServer();

		if (server == null) {
			EntitySyncDebug.LOGGER.warn("Dump verification skipped: integrated server is unavailable");
			sendChat(client, Text.translatable("entity-sync-debug.counts.dump.world_changed").formatted(Formatting.RED));
			return;
		}

		server.execute(() -> {
			ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(dump.playerUuid);

			if (serverPlayer == null || !serverPlayer.getEntityWorld().getRegistryKey().equals(dump.dimension)) {
				client.execute(() -> {
					EntitySyncDebug.LOGGER.warn("Dump verification skipped: integrated server player or world is no longer available");
					sendChat(client, Text.translatable("entity-sync-debug.counts.dump.world_changed").formatted(Formatting.RED));
				});
				return;
			}

			ServerWorld serverWorld = serverPlayer.getEntityWorld();
			List<ServerEntityInfo> stillOnServer = new ArrayList<>();
			int vanished = 0;

			for (ServerEntityInfo info : dump.sent) {
				Entity entity = serverWorld.getEntity(info.uuid());

				if (entity == null || entity.isRemoved()) {
					vanished++;
				} else {
					stillOnServer.add(info);
				}
			}

			int vanishedCount = vanished;
			client.execute(() -> verifyDumpOnClient(client, dump, stillOnServer, vanishedCount));
		});
	}

	private static void verifyDumpOnClient(MinecraftClient client, PendingDump dump,
			List<ServerEntityInfo> stillOnServer, int vanished) {
		if (client.world != dump.expectedWorld || client.player == null) {
			EntitySyncDebug.LOGGER.warn("Dump verification result discarded: client world changed or player is unavailable");
			sendChat(client, Text.translatable("entity-sync-debug.counts.dump.world_changed").formatted(Formatting.RED));
			return;
		}

		int verified = 0;
		List<ServerEntityInfo> stillMissing = new ArrayList<>();
		List<ServerEntityInfo> typeMismatches = new ArrayList<>();

		for (ServerEntityInfo info : stillOnServer) {
			Entity clientEntity = dump.expectedWorld.getEntity(info.uuid());

			if (clientEntity == null || clientEntity.isRemoved()) {
				stillMissing.add(info);
				continue;
			}

			String clientTypeId = entityTypeId(clientEntity);

			if (!clientTypeId.equals(info.typeId())) {
				typeMismatches.add(info);
				continue;
			}

			verified++;
		}

		for (ServerEntityInfo info : stillMissing) {
			boolean chunkLoaded = dump.expectedWorld.getChunkManager()
					.getChunk(info.chunkX(), info.chunkZ(), ChunkStatus.FULL, false) != null;
			EntitySyncDebug.LOGGER.warn(
					"Dump anomaly: entity {} ({}, server id {}) at {} (chunk {}, {}) is still missing on the client {} ticks after forced sync; client chunk loaded: {}",
					info.typeId(), info.uuid(), info.serverEntityId(), formatPos(info.pos()),
					info.chunkX(), info.chunkZ(), DUMP_VERIFY_DELAY_TICKS, chunkLoaded
			);
		}

		for (ServerEntityInfo info : typeMismatches) {
			Entity clientEntity = dump.expectedWorld.getEntity(info.uuid());
			String clientTypeId = clientEntity == null ? "unknown" : entityTypeId(clientEntity);
			EntitySyncDebug.LOGGER.warn(
					"Dump anomaly: entity {} ({}, server id {}) has type {} on the client after forced sync",
					info.typeId(), info.uuid(), info.serverEntityId(), clientTypeId
			);
		}

		int requested = dump.sent.size() + dump.sendFailures.size();
		int anomalies = dump.sendFailures.size() + stillMissing.size() + typeMismatches.size();

		if (anomalies == 0) {
			EntitySyncDebug.LOGGER.info(
					"Entity dump complete: {} requested, {} verified, {} no longer on the server",
					requested, verified, vanished
			);
			sendChat(client, Text.translatable("entity-sync-debug.counts.dump.success", verified, requested).formatted(Formatting.GREEN));
		} else {
			EntitySyncDebug.LOGGER.warn(
					"Entity dump finished with {} anomalies: {} send failures, {} still missing, {} type mismatches; {} verified, {} no longer on the server",
					anomalies, dump.sendFailures.size(), stillMissing.size(), typeMismatches.size(), verified, vanished
			);
			sendChat(client, Text.translatable("entity-sync-debug.counts.dump.anomalies", anomalies, verified).formatted(Formatting.RED));
		}
	}

	private static void logDumpFailure(DumpFailure failure) {
		ServerEntityInfo info = failure.info();
		String message = String.format(
				Locale.ROOT,
				"Failed to force-sync entity %s (%s, server id %d) at %s: %s",
				info.typeId(), info.uuid(), info.serverEntityId(), formatPos(info.pos()), failure.reason()
		);

		if (failure.error() == null) {
			EntitySyncDebug.LOGGER.warn(message);
		} else {
			EntitySyncDebug.LOGGER.error(message, failure.error());
		}
	}

	private static void sendChat(MinecraftClient client, Text message) {
		if (client.player != null) {
			client.player.sendMessage(message, false);
		}
	}

	private static String formatPos(Vec3d pos) {
		return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z);
	}

	private record ServerSnapshot(Map<String, Integer> counts, List<ServerEntityInfo> entities) {
	}

	private record ServerEntityInfo(UUID uuid, String typeId, int serverEntityId, Vec3d pos, int chunkX, int chunkZ) {
	}

	private record DumpFailure(ServerEntityInfo info, String reason, Exception error) {
	}

	private static final class PendingDump {
		private final long verifyAtTick;
		private final ClientWorld expectedWorld;
		private final RegistryKey<World> dimension;
		private final UUID playerUuid;
		private final List<ServerEntityInfo> sent;
		private final List<DumpFailure> sendFailures;

		private PendingDump(long verifyAtTick, ClientWorld expectedWorld, RegistryKey<World> dimension,
				UUID playerUuid, List<ServerEntityInfo> sent, List<DumpFailure> sendFailures) {
			this.verifyAtTick = verifyAtTick;
			this.expectedWorld = expectedWorld;
			this.dimension = dimension;
			this.playerUuid = playerUuid;
			this.sent = sent;
			this.sendFailures = sendFailures;
		}
	}
}
