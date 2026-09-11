package me.aram.smartcoppergolems.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public record ChestSnapshot(
    boolean isEmpty,
    boolean hasEmptySlot,
    Set<Item> containedItems,
    Set<Item> itemsWithSpace
) {
    public static final Codec<ChestSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.fieldOf("is_empty").forGetter(ChestSnapshot::isEmpty),
        Codec.BOOL.fieldOf("has_empty_slot").forGetter(ChestSnapshot::hasEmptySlot),
        BuiltInRegistries.ITEM.byNameCodec().listOf().xmap(
            list -> (Set<Item>) new HashSet<>(list),
            set -> new ArrayList<>(set)
        ).fieldOf("contained_items").forGetter(ChestSnapshot::containedItems),
        BuiltInRegistries.ITEM.byNameCodec().listOf().xmap(
            list -> (Set<Item>) new HashSet<>(list),
            set -> new ArrayList<>(set)
        ).fieldOf("items_with_space").forGetter(ChestSnapshot::itemsWithSpace)
    ).apply(instance, ChestSnapshot::new));

    public static ChestSnapshot fromContainer(Container container) {
        boolean empty = container.isEmpty();
        boolean hasEmpty = false;
        Set<Item> contained = new HashSet<>();
        Set<Item> withSpace = new HashSet<>();

        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                hasEmpty = true;
            } else {
                contained.add(stack.getItem());
                if (stack.getCount() < stack.getMaxStackSize()) {
                    withSpace.add(stack.getItem());
                }
            }
        }

        return new ChestSnapshot(empty, hasEmpty, contained, withSpace);
    }

    public static ChestSnapshot emptySnapshot() {
        return new ChestSnapshot(true, true, Set.of(), Set.of());
    }

    public boolean canAcceptItem(ItemStack stack) {
        if (hasEmptySlot) {
            return true;
        }
        return itemsWithSpace.contains(stack.getItem());
    }

    public boolean matchesItem(ItemStack stack) {
        return containedItems.contains(stack.getItem());
    }
}
