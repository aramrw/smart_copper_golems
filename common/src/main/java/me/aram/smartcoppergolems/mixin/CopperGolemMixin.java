package me.aram.smartcoppergolems.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin {
    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void smartcoppergolems$unstickItemHoldingGolem(ServerLevel level, CallbackInfo ci) {
        CopperGolem golem = (CopperGolem) (Object) this;
        if (!golem.getMainHandItem().isEmpty()) {
            int cooldown = golem.getBrain().getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS).orElse(0);
            if (cooldown > 20) {
                golem.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 20);
            }
        }
    }
}
