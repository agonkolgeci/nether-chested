package fuzs.netherchested.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fuzs.limitlesscontainers.api.limitlesscontainers.v1.MultipliedContainer;
import fuzs.netherchested.init.ModRegistry;
import fuzs.netherchested.world.level.block.entity.NamedBlockEntity;
import fuzs.netherchested.world.level.block.entity.NetherChestBlockEntity;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lets copper golems drop off items in a nether chest, just like they do for vanilla chests. The nether chest is only
 * ever a drop-off target and never a pick-up source, again exactly like a vanilla chest.
 * <p>
 * This behavior rejects the nether chest in two independent places: the target search only ever considers
 * {@link ChestBlockEntity} instances, and the destination predicate tests for
 * {@link net.minecraft.world.level.block.Blocks#CHEST} and
 * {@link net.minecraft.world.level.block.Blocks#TRAPPED_CHEST} directly. On top of that, item insertion caps every
 * slot at the item's own max stack size, ignoring the nether chest's multiplier. Everything else (reachability,
 * queuing, opening the lid, moving the items) is container agnostic and works as-is, provided
 * {@link TransportItemTargetMixin} manages to hand out the container.
 */
@Mixin(TransportItemsBetweenContainers.class)
abstract class TransportItemsBetweenContainersMixin {
    @Shadow
    @Final
    private Predicate<BlockState> destinationBlockType;

    @Shadow
    private AABB getTargetSearchArea(PathfinderMob pathfinderMob) {
        throw new AssertionError();
    }

    @Shadow
    private int getHorizontalSearchDistance(PathfinderMob pathfinderMob) {
        throw new AssertionError();
    }

    @Shadow
    @Nullable
    private TransportItemsBetweenContainers.TransportItemTarget isTargetValidToPick(PathfinderMob pathfinderMob, Level level, BlockEntity blockEntity, Set<GlobalPos> visitedPositions, Set<GlobalPos> unreachablePositions, AABB searchArea) {
        throw new AssertionError();
    }

    @Shadow
    private static Set<GlobalPos> getVisitedPositions(PathfinderMob pathfinderMob) {
        throw new AssertionError();
    }

    @Shadow
    private static Set<GlobalPos> getUnreachablePositions(PathfinderMob pathfinderMob) {
        throw new AssertionError();
    }

    /**
     * Replaces the vanilla target search with a copy of it that additionally accepts nether chests, so that every
     * check a nether chest has to pass is the very check a vanilla chest has to pass.
     * <p>
     * Two deliberate departures from vanilla: a chest that cannot take the carried item in the first place is not a
     * candidate at all, instead of being walked up to only to be given up on again, and a nether chest wins over a
     * vanilla chest no matter which one is closer, as the nether chest is meant to be the actual storage a copper
     * golem feeds. Distance only decides between chests of the same kind, exactly like vanilla does.
     * <p>
     * Both are needed together: without the first one every nether chest in range outranks the vanilla chest that is
     * the only one able to take the item, and the mob burns a visited position on each of them until the tenth one
     * sends it into its idle cooldown, which clears the visited positions and starts the very same round over again.
     * <p>
     * This only ever runs for a mob that already carries an item and whose behavior accepts vanilla chests as a
     * drop-off target, leaving the vanilla search untouched for everyone else.
     */
    @Inject(method = "getTransportTarget", at = @At("HEAD"), cancellable = true)
    public void getTransportTarget(ServerLevel serverLevel, PathfinderMob pathfinderMob, CallbackInfoReturnable<Optional<TransportItemsBetweenContainers.TransportItemTarget>> callback) {
        if (this.isNetherChestWanted(pathfinderMob)) {
            callback.setReturnValue(this.findTransportTarget(serverLevel, pathfinderMob));
        }
    }

    /**
     * @see TransportItemsBetweenContainers#getTransportTarget(ServerLevel, PathfinderMob)
     */
    @Unique
    private Optional<TransportItemsBetweenContainers.TransportItemTarget> findTransportTarget(ServerLevel serverLevel, PathfinderMob pathfinderMob) {
        AABB searchArea = this.getTargetSearchArea(pathfinderMob);
        Set<GlobalPos> visitedPositions = getVisitedPositions(pathfinderMob);
        Set<GlobalPos> unreachablePositions = getUnreachablePositions(pathfinderMob);
        List<ChunkPos> chunkPositions = ChunkPos.rangeClosed(new ChunkPos(pathfinderMob.blockPosition()),
                Math.floorDiv(this.getHorizontalSearchDistance(pathfinderMob), 16) + 1).toList();
        TransportItemsBetweenContainers.TransportItemTarget nearestTarget = null;
        double nearestDistance = Float.MAX_VALUE;
        TransportItemsBetweenContainers.TransportItemTarget nearestNetherChestTarget = null;
        double nearestNetherChestDistance = Float.MAX_VALUE;

        for (ChunkPos chunkPos : chunkPositions) {
            LevelChunk levelChunk = serverLevel.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (levelChunk != null) {
                for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
                    boolean isNetherChest = blockEntity instanceof NetherChestBlockEntity;
                    if (blockEntity instanceof ChestBlockEntity || isNetherChest) {
                        double distance = blockEntity.getBlockPos().distToCenterSqr(pathfinderMob.position());
                        if (distance < (isNetherChest ? nearestNetherChestDistance : nearestDistance)) {
                            TransportItemsBetweenContainers.TransportItemTarget target = this.isTargetValidToPick(
                                    pathfinderMob,
                                    serverLevel,
                                    blockEntity,
                                    visitedPositions,
                                    unreachablePositions,
                                    searchArea);
                            if (target != null && canPlaceCarriedItem(pathfinderMob, target.container())) {
                                if (isNetherChest) {
                                    nearestNetherChestTarget = target;
                                    nearestNetherChestDistance = distance;
                                } else {
                                    nearestTarget = target;
                                    nearestDistance = distance;
                                }
                            }
                        }
                    }
                }
            }
        }

        return Optional.ofNullable(nearestNetherChestTarget != null ? nearestNetherChestTarget : nearestTarget);
    }

    /**
     * A copy of the behavior's own {@code matchesLeavingItemsRequirement}, which vanilla only runs once the mob has
     * reached the chest, run while the target is being picked instead.
     */
    @Unique
    private static boolean canPlaceCarriedItem(PathfinderMob pathfinderMob, Container container) {
        if (container.isEmpty()) {
            return true;
        } else {
            ItemStack carriedItemStack = pathfinderMob.getMainHandItem();
            for (ItemStack itemStack : container) {
                if (ItemStack.isSameItem(itemStack, carriedItemStack)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Vanilla caps every slot at the item's own max stack size, so a nether chest slot would never be topped up past a
     * regular stack, and golems would spread items across new slots instead.
     */
    @WrapOperation(method = "addItemsToContainer",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getMaxStackSize()I"))
    private static int addItemsToContainer(ItemStack itemStack, Operation<Integer> operation, @Local(argsOnly = true) Container container) {
        if (container instanceof MultipliedContainer multipliedContainer) {
            return multipliedContainer.getMaxStackSize(itemStack);
        } else {
            return operation.call(itemStack);
        }
    }

    @Inject(method = "isWantedBlock", at = @At("HEAD"), cancellable = true)
    public void isWantedBlock(PathfinderMob pathfinderMob, BlockState blockState, CallbackInfoReturnable<Boolean> callback) {
        if (blockState.is(ModRegistry.NETHER_CHEST_BLOCK.value()) && this.isNetherChestWanted(pathfinderMob)) {
            callback.setReturnValue(true);
        }
    }

    /**
     * Vanilla only ever finds the lock code on a
     * {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity}, which the nether chest is not.
     */
    @Inject(method = "isContainerLocked", at = @At("HEAD"), cancellable = true)
    public void isContainerLocked(TransportItemsBetweenContainers.TransportItemTarget target, CallbackInfoReturnable<Boolean> callback) {
        if (target.blockEntity() instanceof NamedBlockEntity namedBlockEntity) {
            callback.setReturnValue(namedBlockEntity.isLocked());
        }
    }

    /**
     * The mob must already be carrying an item, and this behavior must accept vanilla chests as a destination in the
     * first place, so that no other mob using this behavior is affected. Both checks are cheap enough to run before
     * any scanning happens.
     */
    @Unique
    private boolean isNetherChestWanted(PathfinderMob pathfinderMob) {
        return !pathfinderMob.getMainHandItem().isEmpty()
                && this.destinationBlockType.test(Blocks.CHEST.defaultBlockState());
    }
}
