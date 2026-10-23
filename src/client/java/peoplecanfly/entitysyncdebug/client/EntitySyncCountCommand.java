package peoplecanfly.entitysyncdebug.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class EntitySyncCountCommand {
	private static final int MIN_VIEW_DISTANCE_CHUNKS = 2;

	private EntitySyncCountCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("entitysyncdebug")
						.then(ClientCommandManager.literal("counts")
								.executes(context -> execute(context.getSource())))));
	}

	private static int execute(FabricClientCommandSource source) {
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
			Map<String, Integer> serverCounts = countEntities(serverWorld.iterateEntities(), center, radiusSquared);

			client.execute(() -> {
				if (client.world != clientWorld || client.player == null) {
					source.sendError(Text.translatable("entity-sync-debug.counts.unavailable"));
					return;
				}

				Map<String, Integer> clientCounts = countEntities(clientWorld.getEntities(), center, radiusSquared);
				sendCounts(source, dimension, viewDistanceChunks, serverCounts, clientCounts);
			});
		});

		return 1;
	}

	private static Map<String, Integer> countEntities(Iterable<? extends Entity> entities, Vec3d center, double radiusSquared) {
		Map<String, Integer> counts = new HashMap<>();

		for (Entity entity : entities) {
			Vec3d delta = entity.getEntityPos().subtract(center);

			if (delta.x * delta.x + delta.z * delta.z > radiusSquared) {
				continue;
			}

			Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
			counts.merge(typeId == null ? "unknown" : typeId.toString(), 1, Integer::sum);
		}

		return counts;
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
}
