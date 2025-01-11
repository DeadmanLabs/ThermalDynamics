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
        setItem(ItemStack.of(nbt));
        this.baseCapacity = nbt.getInt(TAG_CAPACITY);

        this.averageOut = nbt.getInt(TAG_TRACK_OUT);

        updateCapacity();
        return this;
    }

    public CompoundTag write(CompoundTag nbt) {
        item.save(nbt);
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
    public int getSlots() {
        return 1;
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        return item;
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isItemValid(slot, stack)) {
            return stack;
        }
        if (simulate) {
            if (item.isEmpty()) {
                return stack.getCount() <= capacity ? ItemStack.EMPTY : new ItemStack(stack.getItem(), stack.getCount() - capacity);
            }
            if (!ItemStack.isSameItem(item, stack)) {
                return stack;
            }
            int space = capacity - item.getCount();
            return stack.getCount() <= space ? ItemStack.EMPTY : new ItemStack(stack.getItem(), stack.getCount() - space);
        }
        if (item.isEmpty()) {
            item = new ItemStack(stack.getItem(), Math.min(capacity, stack.getCount()));
            return stack.getCount() <= capacity ? ItemStack.EMPTY : new ItemStack(stack.getItem(), stack.getCount() - capacity);
        }
        if (!ItemStack.isSameItem(item, stack)) {
            return stack;
        }
        int space = capacity - item.getCount();
        int toInsert = Math.min(space, stack.getCount());
        item.grow(toInsert);
        return stack.getCount() <= toInsert ? ItemStack.EMPTY : new ItemStack(stack.getItem(), stack.getCount() - toInsert);
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int toExtract = Math.min(amount, item.getCount());
        ItemStack extracted = new ItemStack(item.getItem(), toExtract);
        if (!simulate) {
            item.shrink(toExtract);
            if (item.isEmpty()) {
                item = ItemStack.EMPTY;
            }
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        return capacity;
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return true;
    }
}
