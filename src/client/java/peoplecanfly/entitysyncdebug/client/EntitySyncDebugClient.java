package peoplecanfly.entitysyncdebug.client;

import net.fabricmc.api.ClientModInitializer;

import peoplecanfly.entitysyncdebug.EntitySyncDebug;

public class EntitySyncDebugClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntitySyncChecker.initialize();
		EntitySyncDebug.LOGGER.info("Entity sync debug initialized; singleplayer entity synchronization is checked once per second");
	}
}
