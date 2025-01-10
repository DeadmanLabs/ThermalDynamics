package cofh.thermal.dynamics.common.grid.item;

import cofh.core.util.helpers.ItemHelper;
import cofh.lib.util.TimeTracker;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.Grid;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemGrid extends Grid<ItemGrid, ItemGridNode> implements IItemHandler {

    protected static final int NODE_CAPACITY = 100;

    protected final ItemGridStorage storage = new ItemGridStorage(NODE_CAPACITY);
    protected LazyOptional<?> itemCap = LazyOptional.empty();

    protected ItemStack renderItem = ItemStack.EMPTY;
    protected ItemStack prevRenderItem = ItemStack.EMPTY;
    protected TimeTracker timeTracker = new TimeTracker();
    protected boolean wasFilled;
    protected boolean needsUpdate;

    protected ItemGridNode[] distArray = new ItemGridNode[0];
    protected int distIndex = 0;

    public ItemGrid(UUID id, Level world) {

        super(ITEM_GRID.get(), id, world);
    }

    @Override
    public ItemGridNode newNode() {
        return new ItemGridNode(this);
    }

    @Override
    public void tick() {
        storage.tick();

        if (distArray.length != getNodes().size()) {
            distArray = getNodes().values().toArray(new ItemGridNode[0]);
        }
        int curIndex = distIndex;

        if (distIndex >= distArray.length) {
            distIndex = 0;
        }
        for (int i = distIndex; i < distArray.length; ++i) {
            rrPreNodeTick(i);
        }
        for (int i = 0; i < distIndex; ++i) {
            rrPreNodeTick(i);
        }
        renderUpdate();

        for (int i = distIndex; i < distArray.length; ++i) {
            if (rrNodeTick(curIndex, i)) {
                storage.postTick();
                return;
            }
        }
        for (int i = 0; i < distIndex; ++i) {
            if (rrNodeTick(curIndex, i)) {
                storage.postTick();
                return;
            }
        }
        ++distIndex;
        storage.postTick();
    }

    private void rrPreNodeTick(int i) {
        if (distArray[i].isLoaded()) {
            distArray[i].attachmentTick();
        }
    }

    private boolean rrNodeTick(int curIndex, int i) {
        if (!distArray[i].isLoaded()) {
            return false;
        }
        distArray[i].distributionTick();
        if (getItem().isEmpty()) {
            distIndex = i + 1;
            if (curIndex == distIndex) {
                --distIndex;
            }
            return true;
        }
        return false;
    }

    private void renderUpdate() {
        prevRenderItem = renderItem;
        renderItem = new ItemStack(getItem(), amount); //amount might be not needed

        if (!ItemHelper.)
    }

    @Override
    public void onModified() {

    }

    @Override
    public void onMerge(ItemGrid from) {

    }

    @Override
    public void onSplit(List<ItemGrid> others) {

    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        storage.write(tag);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        super.deserializeNBT(nbt);
        storage.deserializeNBT(nbt);
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {
        if (GridHelper.getGridHost(tile) != null) {
            return false;
        }
        if (dir != null) {
            return tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir).isPresent();
        }
        return false;
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (!itemCap.isPresent()) {
                itemCap = LazyOptional.of(() -> storage);
            }
            return itemCap.cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public void refreshCapabilities() {
        itemCap.invalidate();
    }

    //@formatter:off
    public int getCapacity() { return storage.getCapacity(); }
    public ItemStack getItem() { return storage.getItem(); }
    public ItemStack getRenderItem() { return renderItem; }
    public int getItemAmount() { return storage.getItem().getAmount(); }
    public void setBaseCapacity(int baseCapacity) { storage.setBaseCapacity(baseCapacity); }
    public void setCapacity(int capacity) { storage.setCapacity(capacity); }
    public void setItem(ItemStack item) { storage.setItem(item); }

    @Override public int getStorages() { return storage.getStorages(); }
    @Override public ItemStack getItemInStorage(int storage) { return storage.getItemInStorage(storage); }
    @Override public int fill(ItemStack resource, ItemAction action) { return storage.fill(resource, action); }
    @Override public ItemStack drain (ItemStack resource, ItemAction action) { return storage.drain(resource, action); }
    @Override public ItemStack drain (int maxDrain, ItemAction action) { return storage.drain(maxDrain, action); }
    @Override public int getStorageCapacity(int storage) { return storage.getStorageCapacity(storage); }
    @Override public boolean isItemValid(int storage, @Nonnull ItemStack stack) { return storage.isItemValid(storage, stack); } 
    //@formatter:on
}