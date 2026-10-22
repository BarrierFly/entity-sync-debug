package peoplecanfly.entitysyncdebug.client.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import peoplecanfly.entitysyncdebug.client.TrackedEntityRegistry;

@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin {
	@Shadow
	@Final
	private Entity entity;

	@Inject(method = "startTracking", at = @At("HEAD"))
	private void entitySyncDebug$onStartTracking(ServerPlayerEntity player, CallbackInfo ci) {
		TrackedEntityRegistry.startTracking(player, this.entity);
	}

	@Inject(method = "stopTracking", at = @At("HEAD"))
	private void entitySyncDebug$onStopTracking(ServerPlayerEntity player, CallbackInfo ci) {
		TrackedEntityRegistry.stopTracking(player, this.entity.getUuid());
	}
}
