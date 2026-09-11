package me.aram.smartcoppergolems.mixin;

import me.aram.smartcoppergolems.data.ChestSnapshot;
import me.aram.smartcoppergolems.data.CopperGolemSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Shadow
    @Nullable
    protected Level level;

    @Shadow
    protected BlockPos worldPosition;

    @Shadow
    public abstract BlockState getBlockState();

    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void smartcoppergolems$onSetChanged(CallbackInfo ci) {
        if ((Object) this instanceof ChestBlockEntity && this.level instanceof ServerLevel serverLevel) {
            BlockState state = this.getBlockState();
            ChestSnapshot snapshot = CopperGolemSavedData.updateChestFromLevel(
                serverLevel, this.worldPosition, state, (BlockEntity) (Object) this
            );
            if (snapshot != null) {
                if (state.is(BlockTags.COPPER_CHESTS)) {
                    if (!snapshot.isEmpty()) {
                        CopperGolemSavedData.awakenNearbyEmptyHandedGolems(serverLevel, this.worldPosition);
                    }
                } else {
                    if (snapshot.hasEmptySlot() || !snapshot.itemsWithSpace().isEmpty()) {
                        CopperGolemSavedData.awakenNearbyItemHoldingGolems(serverLevel, this.worldPosition);
                    }
                }
            }
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void smartcoppergolems$onSetRemoved(CallbackInfo ci) {
        if ((Object) this instanceof ChestBlockEntity && this.level instanceof ServerLevel serverLevel) {
            CopperGolemSavedData.get(serverLevel).remove(this.worldPosition);
        }
    }
}
