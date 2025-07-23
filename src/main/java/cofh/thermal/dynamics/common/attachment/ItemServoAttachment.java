package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Predicate;

import static cofh.lib.util.constants.NBTTags.TAG_AMOUNT;
import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;

public class ItemServoAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.servo");

    public static final int TRANSFER = 8; // Items per operation
    public static final int MAX_TRANSFER = 64; // Max items in burst

    protected final IDuct<?, ?> duct;
    protected final Direction side;

    public int amountTransfer = TRANSFER;

    protected BaseItemFilter filter = new BaseItemFilter(15);
    protected RedstoneControlLogic rsControl = new RedstoneControlLogic(this);

    // Overflow storage for items that couldn't be delivered
    protected ItemStackHandler overflowStorage = new ItemStackHandler(9); // 3x3 grid

    protected LazyOptional<IItemHandler> internalGridCap = LazyOptional.empty();
    protected LazyOptional<IItemHandler> gridCap = LazyOptional.empty();
    protected LazyOptional<IItemHandler> externalCap = LazyOptional.empty();

    public ItemServoAttachment(IDuct<?, ?> duct, Direction side) {
        this.duct = duct;
        this.side = side;
    }

    public int getTransfer() {
        return TRANSFER;
    }

    public ItemStackHandler getOverflowStorage() {
        return overflowStorage;
    }

    @Override
    public IDuct<?, ?> duct() {
        return duct;
    }

    @Override
    public Direction side() {
        return side;
    }

    @Override
    public void invalidate() {
        gridCap.invalidate();
        externalCap.invalidate();
    }

    @Override
    public IAttachment read(CompoundTag nbt) {
        if (nbt.isEmpty()) {
            return this;
        }
        amountTransfer = nbt.getInt(TAG_AMOUNT);

        filter.read(nbt);
        rsControl.read(nbt);

        // Read overflow storage
        if (nbt.contains("OverflowStorage")) {
            overflowStorage.deserializeNBT(nbt.getCompound("OverflowStorage"));
        }

        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {
        nbt.putString(TAG_TYPE, SERVO);
        nbt.putInt(TAG_AMOUNT, amountTransfer);

        filter.write(nbt);
        rsControl.write(nbt);

        // Write overflow storage
        nbt.put("OverflowStorage", overflowStorage.serializeNBT());

        return nbt;
    }

    @Override
    public void tick() {
        if (!rsControl.getState()) {
            return;
        }
        
        // Extract items from connected inventory and route through grid
        extractAndRouteItems();
    }
    
    private void extractAndRouteItems() {
        // Get connected external inventory
        LazyOptional<IItemHandler> extCap = getExternalCapability();
        if (!extCap.isPresent()) return;
        
        IItemHandler externalHandler = extCap.orElse(null);
        if (externalHandler == null) return;
        
        // Get grid for routing
        if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) return;
        
        amountTransfer += TRANSFER;
        amountTransfer = Math.min(amountTransfer, MAX_TRANSFER);
        
        // Try to extract items from external inventory
        for (int slot = 0; slot < externalHandler.getSlots() && amountTransfer > 0; slot++) {
            ItemStack extractable = externalHandler.extractItem(slot, Math.min(amountTransfer, 64), true);
            if (extractable.isEmpty() || !filter.valid(extractable)) continue;
            
            // Find best destination for this item
            cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = findBestDestination(itemGrid, extractable);
            if (pathInfo == null) continue; // No valid destination
            
            // Check if destination has capacity (including items in transit)
            BlockPos destPos = pathInfo.path.get(pathInfo.path.size() - 1);
            int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, extractable);
            if (availableCapacity <= 0) continue; // No capacity available
            
            // Extract only what can fit
            int toExtract = Math.min(extractable.getCount(), Math.min(amountTransfer, availableCapacity));
            ItemStack extracted = externalHandler.extractItem(slot, toExtract, false);
            if (extracted.isEmpty()) continue;
            
            // Route item through grid
            itemGrid.insertItem(extracted, pos(), side, destPos, pathInfo.side, pathInfo.path);
            amountTransfer -= extracted.getCount();
        }
    }
    
    private LazyOptional<IItemHandler> getExternalCapability() {
        if (externalCap.isPresent()) return externalCap;
        
        // Find connected tile
        BlockEntity tile = world().getBlockEntity(pos().relative(side));
        if (tile == null) return LazyOptional.empty();
        
        return tile.getCapability(ForgeCapabilities.ITEM_HANDLER, side.getOpposite());
    }
    
    private cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo findBestDestination(cofh.thermal.dynamics.common.grid.item.ItemGrid grid, ItemStack stack) {
        // Get our grid node
        cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = grid.getNodes().get(pos());
        if (ourNode == null) return null;
        
        // Use the node's pathfinding to find destinations
        return ourNode.findBestDestination(stack);
    }


    /**
     * Store an overflow item in the servo's buffer (infinite storage)
     */
    public void storeOverflowItem(ItemStack stack) {
        if (stack.isEmpty()) return;

        // Try to store in existing slots first
        for (int slot = 0; slot < overflowStorage.getSlots(); slot++) {
            stack = overflowStorage.insertItem(slot, stack, false);
            if (stack.isEmpty()) {
                return; // Successfully stored
            }
        }
        
        // If storage is full, expand it by creating additional virtual storage
        // For simplicity, we'll just force it into the last slot (infinite storage)
        if (!stack.isEmpty()) {
            int lastSlot = overflowStorage.getSlots() - 1;
            ItemStack existing = overflowStorage.getStackInSlot(lastSlot);
            if (existing.isEmpty()) {
                overflowStorage.setStackInSlot(lastSlot, stack);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                existing.grow(stack.getCount());
            } else {
                // Force storage in last slot regardless of item type (overflow behavior)
                overflowStorage.setStackInSlot(lastSlot, stack);
            }
        }
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(ITEMS.get(ID_SERVO_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {
        return rsControl.getState() ? SERVO_ATTACHMENT_ACTIVE_LOC : SERVO_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {
        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {
        return new ItemServoAttachmentMenu(i, player.level, pos(), side, inventory, player);
    }

    @Override
    public <T> LazyOptional<T> wrapGridCapability(@Nonnull Capability<T> cap, @Nonnull LazyOptional<T> gridLazOpt) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (gridCap.isPresent()) {
                return gridCap.cast();
            }
            Optional<T> gridOpt = gridLazOpt.resolve();
            if (gridOpt.isPresent() && gridOpt.get() instanceof IItemHandler handler) {
                gridCap = LazyOptional.of(() -> new WrappedGridItemHandler(handler));
                gridLazOpt.addListener(e -> gridCap.invalidate());
                return gridCap.cast();
            }
        }
        return gridLazOpt;
    }

    @Override
    public <T> LazyOptional<T> wrapExternalCapability(@Nonnull Capability<T> cap, @Nonnull LazyOptional<T> extLazOpt) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (externalCap.isPresent()) {
                return externalCap.cast();
            }
            Optional<T> extOpt = extLazOpt.resolve();
            if (extOpt.isPresent() && extOpt.get() instanceof IItemHandler handler) {
                externalCap = LazyOptional.of(() -> new WrappedExternalItemHandler(handler, e -> rsControl.getState() && filter.valid(e)));
                extLazOpt.addListener(e -> externalCap.invalidate());
                return externalCap.cast();
            }
        }
        return extLazOpt;
    }

    // region IFilterableAttachment
    @Override
    public IFilter getFilter() {
        return filter;
    }
    // endregion

    // region IPacketHandlerAttachment
    @Override
    public FriendlyByteBuf getConfigPacket(FriendlyByteBuf buffer) {
        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());
        return buffer;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {
        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
    }

    @Override
    public FriendlyByteBuf getControlPacket(FriendlyByteBuf buffer) {
        rsControl.writeToBuffer(buffer);
        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());
        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {
        rsControl.readFromBuffer(buffer);
        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
    }
    // endregion

    // region IRedstoneControllableAttachment
    @Override
    public RedstoneControlLogic redstoneControl() {
        return rsControl;
    }
    // endregion

    // region IConveyableData
    @Override
    public void readConveyableData(Player player, CompoundTag tag) {
        rsControl.readSettings(tag);
        filter.read(tag);
        onControlUpdate();
    }

    @Override
    public void writeConveyableData(Player player, CompoundTag tag) {
        rsControl.writeSettings(tag);
        filter.write(tag);
    }
    // endregion

    // region GRID WRAPPER CLASS
    private static class WrappedGridItemHandler implements IItemHandler {
        protected IItemHandler wrappedHandler;

        public WrappedGridItemHandler(IItemHandler wrappedHandler) {
            this.wrappedHandler = wrappedHandler;
        }

        @Override
        public int getSlots() {
            return wrappedHandler.getSlots();
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return wrappedHandler.getStackInSlot(slot);
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack; // Grid cannot accept items from servo
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY; // Servo cannot extract from grid
        }

        @Override
        public int getSlotLimit(int slot) {
            return wrappedHandler.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return false;
        }
    }
    // endregion

    // region EXTERNAL WRAPPER CLASS
    private static class WrappedExternalItemHandler implements IItemHandler {
        protected IItemHandler wrappedHandler;
        protected Predicate<ItemStack> validator;

        public WrappedExternalItemHandler(IItemHandler wrappedHandler, Predicate<ItemStack> validator) {
            this.wrappedHandler = wrappedHandler;
            this.validator = validator;
        }

        @Override
        public int getSlots() {
            return wrappedHandler.getSlots();
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return wrappedHandler.getStackInSlot(slot);
        }

        @Override
        public int getSlotLimit(int slot) {
            return wrappedHandler.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return validator.test(stack) && wrappedHandler.isItemValid(slot, stack);
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack; // External cannot insert into servo
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack extracted = wrappedHandler.extractItem(slot, amount, true);
            return validator.test(extracted) ? wrappedHandler.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }
    }
    // endregion
}