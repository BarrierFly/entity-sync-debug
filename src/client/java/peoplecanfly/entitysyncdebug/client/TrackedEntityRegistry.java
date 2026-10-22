package peoplecanfly.entitysyncdebug.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Entities for which the server has started entity tracking for a given player.
 */
public final class TrackedEntityRegistry {
	private static final Map<UUID, Set<UUID>> TRACKED_ENTITIES = new HashMap<>();

	private TrackedEntityRegistry() {
	}

	public static synchronized void startTracking(ServerPlayerEntity player, Entity entity) {
		TRACKED_ENTITIES.computeIfAbsent(player.getUuid(), uuid -> new HashSet<>()).add(entity.getUuid());
	}

	public static synchronized void stopTracking(ServerPlayerEntity player, UUID entityUuid) {
		Set<UUID> entities = TRACKED_ENTITIES.get(player.getUuid());

		if (entities == null) {
			return;
		}

		entities.remove(entityUuid);

		if (entities.isEmpty()) {
			TRACKED_ENTITIES.remove(player.getUuid());
		}
	}

	public static synchronized List<UUID> snapshot(UUID playerUuid) {
		Set<UUID> entities = TRACKED_ENTITIES.get(playerUuid);
		return entities == null ? List.of() : new ArrayList<>(entities);
	}

	public static synchronized void clear() {
		TRACKED_ENTITIES.clear();
	}
}
