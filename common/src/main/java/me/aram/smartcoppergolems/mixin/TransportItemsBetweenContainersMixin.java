package me.aram.smartcoppergolems.mixin;

import me.aram.smartcoppergolems.data.ChestSnapshot;
import me.aram.smartcoppergolems.data.CopperGolemSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.ContainerInteractionState;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
    @Nullable
    private ContainerInteractionState interactionState;

    @Shadow
    protected abstract AABB getTargetSearchArea(PathfinderMob mob);

    @Shadow
    protected abstract int getHorizontalSearchDistance(PathfinderMob mob);

    @Shadow
    @Nullable
    protected abstract TransportItemTarget isTargetValidToPick(
        PathfinderMob body, Level level, BlockEntity blockEntity, Set<GlobalPos> visitedPositions, Set<GlobalPos> unreachablePositions, AABB targetBlockSearchArea
    );

    @Shadow
    protected abstract void markVisitedBlockPosAsUnreachable(PathfinderMob body, Level level, BlockPos target);

    @Shadow
    protected abstract boolean isWantedBlock(PathfinderMob mob, BlockState block);

    @Shadow
    protected abstract boolean isContainerLocked(TransportItemTarget transportItemTarget);

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

                        ChestSnapshot snapshot = savedData.getSnapshot(pos);
                        if (snapshot == null && chestBlockEntity.getBlockState().is(BlockTags.COPPER_CHESTS)) {
                            snapshot = CopperGolemSavedData.updateChestFromLevel(level, pos, chestBlockEntity.getBlockState(), chestBlockEntity);
                        }

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

            TransportItemTarget bestEmptyTarget = null;
            double closestEmptyDist = Double.MAX_VALUE;

            TransportItemTarget fallbackEmptyTarget = null;
            double closestFallbackEmptyDist = Double.MAX_VALUE;

            TransportItemTarget bestUnknownTarget = null;
            double closestUnknownDist = Double.MAX_VALUE;

            for (ChunkPos chunkPos : list) {
                LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
                if (levelChunk == null) continue;

                for (BlockEntity potentialTarget : levelChunk.getBlockEntities().values()) {
                    if (potentialTarget instanceof ChestBlockEntity chestBlockEntity) {
                        BlockPos pos = chestBlockEntity.getBlockPos();
                        ChestSnapshot snapshot = savedData.getSnapshot(pos);
                        if (snapshot == null) {
                            snapshot = CopperGolemSavedData.updateChestFromLevel(level, pos, chestBlockEntity.getBlockState(), chestBlockEntity);
                        }

                        // If known to contain items, but does NOT contain the held item:
                        // Vanilla golems NEVER deposit into a non-empty chest that doesn't match!
                        // Skip immediately so we never check or loop between non-matching chests!
                        if (snapshot != null && !snapshot.isEmpty() && !snapshot.matchesItem(heldItem)) {
                            continue;
                        }

                        // If known to match, but has no space to accept more: skip!
                        if (snapshot != null && snapshot.matchesItem(heldItem) && !snapshot.canAcceptItem(heldItem)) {
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
                        } else if (snapshot != null && snapshot.isEmpty()) {
                            // Tier 2: Completely empty chest (can start a new item category or receive unknown/exhaust items)
                            TransportItemTarget targetValid = this.isTargetValidToPick(
                                body, level, chestBlockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea
                            );
                            if (targetValid != null) {
                                if (distance < closestEmptyDist) {
                                    bestEmptyTarget = targetValid;
                                    closestEmptyDist = distance;
                                }
                            } else {
                                // Fallback: If empty chest was marked visited or unreachable, keep track of it
                                TransportItemTarget possible = TransportItemTarget.tryCreatePossibleTarget(chestBlockEntity, level);
                                if (possible != null
                                    && targetBlockSearchArea.contains(pos.getX(), pos.getY(), pos.getZ())
                                    && this.isWantedBlock(body, possible.state())
                                    && !this.isContainerLocked(possible)) {
                                    if (distance < closestFallbackEmptyDist) {
                                        fallbackEmptyTarget = possible;
                                        closestFallbackEmptyDist = distance;
                                    }
                                }
                            }
                        } else if (snapshot == null) {
                            // Tier 3: Unknown chest (natural exploration to discover unique items)
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
            } else if (bestEmptyTarget != null) {
                selected = bestEmptyTarget;
            } else if (fallbackEmptyTarget != null) {
                // If golem has an unmatched "idk" item and the empty chest was marked visited or unreachable,
                // unblock it so the golem ALWAYS forwards to the nearest empty chest and never gets stuck!
                Set<GlobalPos> newVisited = new java.util.HashSet<>(visitedPositions);
                newVisited.remove(new GlobalPos(level.dimension(), fallbackEmptyTarget.pos()));
                body.getBrain().setMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS, newVisited);

                Set<GlobalPos> newUnreachable = new java.util.HashSet<>(unreachablePositions);
                newUnreachable.remove(new GlobalPos(level.dimension(), fallbackEmptyTarget.pos()));
                body.getBrain().setMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS, newUnreachable);

                selected = fallbackEmptyTarget;
            } else if (bestUnknownTarget != null) {
                selected = bestUnknownTarget;
            }

            if (selected != null) {
                body.getBrain().eraseMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
            }

            cir.setReturnValue(Optional.ofNullable(selected));
        }
    }

    @Inject(method = "startOnReachedTargetInteraction", at = @At("TAIL"))
    private void smartcoppergolems$onStartInteraction(
        TransportItemTarget target, PathfinderMob body, CallbackInfo ci
    ) {
        if (body instanceof CopperGolem && body.level() instanceof ServerLevel serverLevel) {
            // Read all unique items in the chest immediately upon opening
            CopperGolemSavedData.updateChestFromLevel(serverLevel, target.pos(), target.state(), target.blockEntity());
        }
    }

    @Inject(method = "onReachedTarget", at = @At("TAIL"))
    private void smartcoppergolems$onReachedTargetTail(
        TransportItemTarget target, Level level, PathfinderMob body, CallbackInfo ci
    ) {
        if (body instanceof CopperGolem && level instanceof ServerLevel serverLevel && this.ticksSinceReachingTarget >= 60) {
            // Re-read chest contents after transaction
            CopperGolemSavedData.updateChestFromLevel(serverLevel, target.pos(), target.state(), target.blockEntity());

            // Loop breaking & failure protection:
            if (this.interactionState == TransportItemsBetweenContainers.ContainerInteractionState.PLACE_NO_ITEM) {
                ChestSnapshot snap = CopperGolemSavedData.get(serverLevel).getSnapshot(target.pos());
                // Only blacklist non-matching chests, NEVER blacklist an empty chest!
                if (snap == null || !snap.isEmpty()) {
                    this.markVisitedBlockPosAsUnreachable(body, level, target.pos());
                }
            } else if (this.interactionState == TransportItemsBetweenContainers.ContainerInteractionState.PICKUP_NO_ITEM) {
                // If pickup failed because copper chest is empty, mark it empty and unreachable
                CopperGolemSavedData.get(serverLevel).markEmpty(target.pos());
                this.markVisitedBlockPosAsUnreachable(body, level, target.pos());
            }
        }
    }

    @Inject(method = "enterCooldownAfterNoMatchingTargetFound", at = @At("TAIL"))
    private void smartcoppergolems$onEnterCooldown(PathfinderMob body, CallbackInfo ci) {
        if (body instanceof CopperGolem && !body.getMainHandItem().isEmpty()) {
            // If holding an item, reduce cooldown from 140 ticks (7s) to 20 ticks (1s)
            // so the golem quickly delivers the item as soon as an empty chest is available
            body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 20);
        }
    }

    @Inject(method = "addItemsToContainer", at = @At("HEAD"), cancellable = true)
    private static void smartcoppergolems$smartAddItemsToContainer(
        PathfinderMob body, Container container, CallbackInfoReturnable<ItemStack> cir
    ) {
        if (!(body instanceof CopperGolem)) {
            return;
        }

        ItemStack held = body.getMainHandItem();
        if (held.isEmpty()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        int containerSize = container.getContainerSize();
        int maxAllowed = container.getMaxStackSize(held);

        // PASS 1: Stack into existing matching items first!
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack slotStack = container.getItem(slot);
            if (!slotStack.isEmpty() && smartcoppergolems$canItemsStack(slotStack, held)) {
                int maxSlotStack = Math.min(slotStack.getMaxStackSize(), maxAllowed);
                int space = maxSlotStack - slotStack.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, held.getCount());
                    slotStack.grow(toAdd);
                    held.shrink(toAdd);
                    container.setItem(slot, slotStack);
                    if (held.isEmpty()) {
                        cir.setReturnValue(ItemStack.EMPTY);
                        return;
                    }
                }
            }
        }

        // PASS 2: Place any remaining into the first available empty slot
        for (int slot = 0; slot < containerSize; slot++) {
            ItemStack slotStack = container.getItem(slot);
            if (slotStack.isEmpty() && container.canPlaceItem(slot, held)) {
                int maxSlotStack = Math.min(held.getMaxStackSize(), maxAllowed);
                int toAdd = Math.min(maxSlotStack, held.getCount());
                ItemStack placeStack = held.split(toAdd);
                container.setItem(slot, placeStack);
                if (held.isEmpty()) {
                    cir.setReturnValue(ItemStack.EMPTY);
                    return;
                }
            }
        }

        cir.setReturnValue(held);
    }

    @Unique
    private static boolean smartcoppergolems$canItemsStack(ItemStack a, ItemStack b) {
        if (!a.is(b.getItem())) {
            return false;
        }
        if (ItemStack.isSameItemSameComponents(a, b)) {
            return true;
        }
        // Identical simple items (not damaged, not enchanted, not custom-named)
        return !a.isDamageableItem() && !b.isDamageableItem()
            && !a.isEnchanted() && !b.isEnchanted()
            && !a.has(DataComponents.CUSTOM_NAME) && !b.has(DataComponents.CUSTOM_NAME);
    }
}

