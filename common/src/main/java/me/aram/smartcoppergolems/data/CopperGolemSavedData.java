package me.aram.smartcoppergolems.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemAi;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CopperGolemSavedData extends SavedData {
    public record ChestEntry(BlockPos pos, ChestSnapshot snapshot) {
        public static final Codec<ChestEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(ChestEntry::pos),
            ChestSnapshot.CODEC.fieldOf("snapshot").forGetter(ChestEntry::snapshot)
        ).apply(instance, ChestEntry::new));
    }

    public static final Codec<CopperGolemSavedData> CODEC = ChestEntry.CODEC.listOf().xmap(
        entries -> {
            CopperGolemSavedData data = new CopperGolemSavedData();
            for (ChestEntry entry : entries) {
                data.chests.put(entry.pos(), entry.snapshot());
            }
            return data;
        },
        data -> data.chests.entrySet().stream()
            .map(e -> new ChestEntry(e.getKey(), e.getValue()))
            .toList()
    );

    public static final SavedDataType<CopperGolemSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("smart_copper_golems", "chest_memory"),
        CopperGolemSavedData::new,
        CODEC,
        null
    );

    private final Map<BlockPos, ChestSnapshot> chests = new ConcurrentHashMap<>();

    public CopperGolemSavedData() {
    }

    public static CopperGolemSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public ChestSnapshot getSnapshot(BlockPos pos) {
        return chests.get(pos);
    }

    public boolean isKnownEmpty(BlockPos pos) {
        ChestSnapshot snapshot = chests.get(pos);
        return snapshot != null && snapshot.isEmpty();
    }

    public void updateSnapshot(BlockPos pos, ChestSnapshot snapshot) {
        ChestSnapshot old = chests.put(pos, snapshot);
        if (old == null || !snapshot.equals(old)) {
            this.setDirty();
        }
    }

    public void markEmpty(BlockPos pos) {
        ChestSnapshot old = chests.put(pos, ChestSnapshot.emptySnapshot());
        if (old == null || !old.isEmpty()) {
            this.setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (chests.remove(pos) != null) {
            this.setDirty();
        }
    }

    public static ChestSnapshot updateChestFromLevel(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container container = ChestBlock.getContainer(chestBlock, state, level, pos, false);
            if (container != null) {
                ChestSnapshot snapshot = ChestSnapshot.fromContainer(container);
                CopperGolemSavedData data = get(level);
                data.updateSnapshot(pos, snapshot);

                if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE) {
                    BlockPos connectedPos = ChestBlock.getConnectedBlockPos(pos, state);
                    if (connectedPos != null) {
                        data.updateSnapshot(connectedPos, snapshot);
                    }
                }
                return snapshot;
            }
        }
        return null;
    }

    public static void awakenNearbyEmptyHandedGolems(ServerLevel level, BlockPos pos) {
        AABB searchBox = new AABB(pos).inflate(32, 8, 32);
        List<CopperGolem> golems = level.getEntitiesOfClass(CopperGolem.class, searchBox);
        for (CopperGolem golem : golems) {
            if (golem.getMainHandItem().isEmpty()) {
                golem.getBrain().eraseMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
                golem.getBrain().eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS);
                golem.getBrain().eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS);
                CopperGolemAi.updateActivity(golem);
            }
        }
    }

    public static void awakenNearbyItemHoldingGolems(ServerLevel level, BlockPos pos) {
        AABB searchBox = new AABB(pos).inflate(32, 8, 32);
        List<CopperGolem> golems = level.getEntitiesOfClass(CopperGolem.class, searchBox);
        for (CopperGolem golem : golems) {
            if (!golem.getMainHandItem().isEmpty()) {
                golem.getBrain().eraseMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
                golem.getBrain().eraseMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS);
                golem.getBrain().eraseMemory(MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS);
                CopperGolemAi.updateActivity(golem);
            }
        }
    }
}
