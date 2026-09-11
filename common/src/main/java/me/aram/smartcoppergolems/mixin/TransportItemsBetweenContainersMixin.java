package me.aram.smartcoppergolems.mixin;

import me.aram.smartcoppergolems.data.ChestSnapshot;
import me.aram.smartcoppergolems.data.CopperGolemSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
    @Shadow
    private int ticksSinceReachingTarget;

    @Shadow
    protected abstract AABB getTargetSearchArea(PathfinderMob mob);

    @Shadow
    protected abstract int getHorizontalSearchDistance(PathfinderMob mob);

    @Shadow
    @Nullable
    protected abstract TransportItemTarget isTargetValidToPick(
        PathfinderMob body, Level level, BlockEntity blockEntity, Set<GlobalPos> visitedPositions, Set<GlobalPos> unreachablePositions, AABB targetBlockSearchArea
    );

    @Inject(method = "getTransportTarget", at = @At("HEAD"), cancellable = true)
    private void smartcoppergolems$getSmartTransportTarget(
        ServerLevel level,
        PathfinderMob body,
        CallbackInfoReturnable<Optional<TransportItemTarget>> cir
    ) {
        if (!(body instanceof CopperGolem)) {
            return;
        }

        CopperGolemSavedData savedData = CopperGolemSavedData.get(level);
        ItemStack heldItem = body.getMainHandItem();
        boolean pickingUp = heldItem.isEmpty();

        AABB targetBlockSearchArea = this.getTargetSearchArea(body);
        Set<GlobalPos> visitedPositions = body.getBrain().getMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS).orElse(Set.of());
        Set<GlobalPos> unreachablePositions = body.getBrain().getMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS).orElse(Set.of());
        List<ChunkPos> list = ChunkPos.rangeClosed(
            ChunkPos.containing(body.blockPosition()),
            Math.floorDiv(this.getHorizontalSearchDistance(body), 16) + 1
        ).toList();

        if (pickingUp) {
            // Empty-handed: Picking up from copper chests
            TransportItemTarget bestTarget = null;
            double closestDistance = Double.MAX_VALUE;

            for (ChunkPos chunkPos : list) {
                LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
                if (levelChunk == null) continue;

                for (BlockEntity potentialTarget : levelChunk.getBlockEntities().values()) {
                    if (potentialTarget instanceof ChestBlockEntity chestBlockEntity) {
                        BlockPos pos = chestBlockEntity.getBlockPos();

                        // Skip known empty copper chests to avoid useless pathing & sound spam!
                        if (savedData.isKnownEmpty(pos)) {
                            continue;
                        }

                        double distance = pos.distToCenterSqr(body.position());
                        if (distance < closestDistance) {
                            TransportItemTarget targetValid = this.isTargetValidToPick(
                                body, level, chestBlockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea
                            );
                            if (targetValid != null) {
                                bestTarget = targetValid;
                                closestDistance = distance;
                            }
                        }
                    }
                }
            }
            cir.setReturnValue(Optional.ofNullable(bestTarget));
        } else {
            // Holding an item: Depositing into chests
            TransportItemTarget bestMatchingTarget = null;
            double closestMatchingDist = Double.MAX_VALUE;

            TransportItemTarget bestEmptySlotTarget = null;
            double closestEmptySlotDist = Double.MAX_VALUE;

            TransportItemTarget bestUnknownTarget = null;
            double closestUnknownDist = Double.MAX_VALUE;

            for (ChunkPos chunkPos : list) {
                LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
                if (levelChunk == null) continue;

                for (BlockEntity potentialTarget : levelChunk.getBlockEntities().values()) {
                    if (potentialTarget instanceof ChestBlockEntity chestBlockEntity) {
                        BlockPos pos = chestBlockEntity.getBlockPos();
                        ChestSnapshot snapshot = savedData.getSnapshot(pos);

                        // If known to be completely full and cannot accept this item, skip!
                        if (snapshot != null && !snapshot.canAcceptItem(heldItem)) {
                            continue;
                        }

                        double distance = pos.distToCenterSqr(body.position());

                        if (snapshot != null && snapshot.matchesItem(heldItem) && snapshot.canAcceptItem(heldItem)) {
                            // Tier 1: Known matching item with space
                            if (distance < closestMatchingDist) {
                                TransportItemTarget targetValid = this.isTargetValidToPick(
                                    body, level, chestBlockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea
                                );
                                if (targetValid != null) {
                                    bestMatchingTarget = targetValid;
                                    closestMatchingDist = distance;
                                }
                            }
                        } else if (snapshot != null && snapshot.hasEmptySlot()) {
                            // Tier 2: Known to have an empty slot
                            if (distance < closestEmptySlotDist) {
                                TransportItemTarget targetValid = this.isTargetValidToPick(
                                    body, level, chestBlockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea
                                );
                                if (targetValid != null) {
                                    bestEmptySlotTarget = targetValid;
                                    closestEmptySlotDist = distance;
                                }
                            }
                        } else if (snapshot == null) {
                            // Tier 3: Unknown chest (natural exploration)
                            if (distance < closestUnknownDist) {
                                TransportItemTarget targetValid = this.isTargetValidToPick(
                                    body, level, chestBlockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea
                                );
                                if (targetValid != null) {
                                    bestUnknownTarget = targetValid;
                                    closestUnknownDist = distance;
                                }
                            }
                        }
                    }
                }
            }

            TransportItemTarget selected = null;
            if (bestMatchingTarget != null) {
                selected = bestMatchingTarget;
            } else if (bestEmptySlotTarget != null) {
                selected = bestEmptySlotTarget;
            } else if (bestUnknownTarget != null) {
                selected = bestUnknownTarget;
            }

            cir.setReturnValue(Optional.ofNullable(selected));
        }
    }

    @Inject(method = "startOnReachedTargetInteraction", at = @At("TAIL"))
    private void smartcoppergolems$onStartInteraction(
        TransportItemTarget target, PathfinderMob body, CallbackInfo ci
    ) {
        if (body instanceof CopperGolem && body.level() instanceof ServerLevel serverLevel) {
            CopperGolemSavedData.updateChestFromLevel(serverLevel, target.pos(), target.state(), target.blockEntity());
        }
    }

    @Inject(method = "onReachedTarget", at = @At("TAIL"))
    private void smartcoppergolems$onReachedTargetTail(
        TransportItemTarget target, Level level, PathfinderMob body, CallbackInfo ci
    ) {
        if (body instanceof CopperGolem && level instanceof ServerLevel serverLevel && this.ticksSinceReachingTarget >= 60) {
            CopperGolemSavedData.updateChestFromLevel(serverLevel, target.pos(), target.state(), target.blockEntity());
        }
    }
}
