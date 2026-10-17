package fuzs.netherchested.mixin;

import fuzs.netherchested.world.level.block.entity.NetherChestBlockEntity;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.animal.coppergolem.CopperGolemAi;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Vanilla's queuing predicate only knows how to look up the mobs currently using a
 * {@link ChestBlockEntity}, so a nether chest never makes a copper golem queue up. On any storage system where more
 * than one golem shares a chest that is the whole difference in behavior: golems pile up on the same nether chest at
 * the same time instead of waiting their turn, which shows up as golems shoving each other away from the chest and
 * failing to reach it at all in tight spots.
 */
@Mixin(CopperGolemAi.class)
abstract class CopperGolemAiMixin {

    /**
     * Replaces the predicate with a copy of vanilla's that handles the nether chest in exactly the same way, via
     * {@link net.minecraft.world.Container#getEntitiesWithContainerOpen()} on the chest's own container. The predicate
     * is only ever created once while the brain is being built, so there is nothing to it beyond handing out our own
     * instance.
     *
     * @see CopperGolemAi#shouldQueueForTarget()
     */
    @Inject(method = "shouldQueueForTarget", at = @At("HEAD"), cancellable = true)
    private static void shouldQueueForTarget(CallbackInfoReturnable<Predicate<TransportItemsBetweenContainers.TransportItemTarget>> callback) {
        callback.setReturnValue((TransportItemsBetweenContainers.TransportItemTarget target) -> {
            if (target.blockEntity() instanceof ChestBlockEntity chestBlockEntity) {
                return !chestBlockEntity.getEntitiesWithContainerOpen().isEmpty();
            } else if (target.blockEntity() instanceof NetherChestBlockEntity netherChestBlockEntity) {
                return !netherChestBlockEntity.getContainer().getEntitiesWithContainerOpen().isEmpty();
            } else {
                return false;
            }
        });
    }
}
