package me.aram.smartcoppergolems.neoforge;

import me.aram.smartcoppergolems.SmartCopperGolems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(SmartCopperGolems.MOD_ID)
public final class SmartCopperGolemsNeoForge {
    public SmartCopperGolemsNeoForge(IEventBus modBus) {
        SmartCopperGolems.init();
    }
}
