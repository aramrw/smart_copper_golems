package me.aram.smartcoppergolems.fabric;

import me.aram.smartcoppergolems.SmartCopperGolems;
import net.fabricmc.api.ModInitializer;

public final class SmartCopperGolemsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        SmartCopperGolems.init();
    }
}
