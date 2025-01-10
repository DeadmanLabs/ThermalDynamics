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
        setItem(ItemStack.loadItemStackFromNBT(nbt));
        this.baseCapacity = nbt.getInt(TAG_CAPACITY);

        this.averageOut = nbt.getInt(TAG_TRACK_OUT);

        updateCapacity();
        return this;
    }

    public CompoundTag write(CompoundTag nbt) {
        item.writeToNBT(nbt);
        nbt.putInt(TAG_CAPACITY, baseCapacity);

        nbt.putInt(TAG_TRACK_OUT, averageOut);
        return nbt;
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

    @Nonnull
    @Override
    public ItemStack getItemsInStorage(int storage) {
        return item;
    }

    @Override
    public int fill(ItemStack resource, ItemAction action) {
        if (resource.isEmpty() || !isItemValid(0, resource)) {
            return 0;
        }
        if (action.simulate()) {
            if (item.isEmpty()) {
                return Math.min(capacity, resource.getCount());
            }
            if (!item.isItemEqual(resource)) {
                return 0;
            }
            return Math.min(capacity - item.getCount(), resource.getCount());
        }
        if (item.isEmpty()) {
            setItem(new ItemStack(resource, Math.min(capacity, resource.getCount())));
            return item.getCount();
        }
        if (!item.isItemEqual(resource)) {
            return 0;
        }
        if (item.getCount() >= capacity) {
            return 0;
        }
        int filled = capacity - item.getCount();
        if (resource.getAmount() < filled) {
            item.grow(resource.getCount());
            filled = resource.getCount();
        } else {
            item.setCount(capacity);
        }
        return filled;
    }

    @Nonnull
    @Override
    public ItemStack drain(ItemStack resource, ItemAction action) {
        if (resource.isEmpty() || !resource.isItemEqual(item)) {
            return ItemStack.EMPTY;
        }
        return drain(resource.getCount(), action);
    }

    @Nonnull
    @Override
    public ItemStack drain(int maxDrain, ItemAction action) {
        if (maxDrain <= 0 || item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int drained = maxDrain;
        if (item.getCount() < drained) {
            drained = item.getCount();
        }
        ItemStack stack = new ItemStack(item, drained);
        if (action.execute()) {
            item.shrink(drained);
            if (item.isEmpty()) {
                setItem(ItemStack.EMPTY);
            }
        }
        return stack;
    }

    @Override
    public int getStorageCapacity(int storage) {
        return capacity;
    }

    @Override
    public boolean isItemValid(int storage, @Nonnull ItemStack stack) {
        return true;
    }
}

