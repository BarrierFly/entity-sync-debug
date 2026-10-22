package peoplecanfly.entitysyncdebug.client;

import java.util.UUID;

import net.minecraft.util.math.Vec3d;

record TrackedEntitySnapshot(
		UUID uuid,
		String typeId,
		String name,
		int serverEntityId,
		Vec3d serverPos,
		int chunkX,
		int chunkZ,
		double horizontalDistance,
		double distance,
		UUID vehicleUuid,
		int trackRangeChunks,
		int viewDistanceChunks,
		long serverTick,
		String dimensionId
) {
}
