package cofh.thermal.dynamics.common.grid.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;

import static cofh.lib.util.constants.NBTTags.TAG_CAPACITY;
import static cofh.lib.util.constants.NBTTags.TAG_TRACK_OUT;

public final class ItemGridStorage implements IItemHandler, INBTSerializable<CompoundTag> {
    private int baseCapacity;
    private int capacity;

    private ItemStack item = ItemStack.EMPTY;

    private byte sampleTracker = 0;

    private final int[] samplesOut = new int[40];
    private int rollingOut = 0;
    private int averageOut = 0;

    public ItemGridStorage(int baseCapacity) {
        this.baseCapacity = baseCapacity;
    }

    public ItemGridStorage setBaseCapacity(int baseCapacity) {
        this.baseCapacity = Math.max(0, baseCapacity);
        return this;
    }

    public ItemGridStorage setCapacity(int capacity) {
        this.capacity = capacity;
        resetTrackers();
        return this;
    }

    public ItemGridStorage setItem(ItemStack item) {
        this.item = item.copy();
        return this;
    }

    public void resetTrackers() {
        sampleTracker = 0;
        rollingOut = 0;
        averageOut = 0;
    }

    public int getCapacity() {
        return capacity;
    }

    public ItemStack getItem() {
        return item;
    }

    public void tick() {
        samplesOut[sampleTracker] = item.getCount();
    }

    public void postTick() {
        samplesOut[sampleTracker] -= item.getCount();
        rollingOut += samplesOut[sampleTracker];
        averageOut = rollingOut / samplesOut.length;

        ++sampleTracker;
        if (sampleTracker >= samplesOut.length) {
            sampleTracker = 0;
            updateCapacity();
        }
        rollingOut -= samplesOut[sampleTracker];
        samplesOut[sampleTracker] = 0;
    }

    private void updateCapacity() {
        this.capacity = Math.max(baseCapacity, 4 * averageOut);
    }

    public ItemGridStorage read(CompoundTag nbt) {
        
    }

    public CompoundTag write(CompoundTag nbt) {
        
    }

    @Override
    public CompoundTag serializeNBT() {
        return write(new CompoundTag());
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        read(nbt);
    }

    @Override
    public int getStorage() {
        return 1;
    }

    
}

