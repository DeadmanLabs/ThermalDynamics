package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
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
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_OVERFLOW_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;

public class ItemServoAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.item_servo");

    public static final int DEFAULT_TRANSFER = 1; // Default items per operation  
    public static final int MIN_TRANSFER = 1; // Minimum items per operation
    public static final int MAX_TRANSFER = 8; // Maximum items per operation for regular servo

    protected final IDuct<?, ?> duct;
    protected final Direction side;

    public int amountTransfer = DEFAULT_TRANSFER;

    // Extraction timing - base interval for normal ducts (200 ticks = 10 seconds = 6 extractions/min)
    // Impulse ducts use 1/4 of this (50 ticks = 2.5 seconds = 24 extractions/min)
    protected int extractionCooldown = 0;
    protected static final int BASE_EXTRACTION_INTERVAL = 200; // 10 seconds at 20 TPS (6/min)

    protected BaseItemFilter filter = new BaseItemFilter(15);
    protected RedstoneControlLogic rsControl = new RedstoneControlLogic(this);

    // Overflow storage for items that couldn't be delivered
    protected ItemStackHandler overflowStorage = new ItemStackHandler(9); // 3x3 grid

    // Flag indicating items are being backflowed to this servo
    protected boolean hasBackflow = false;

    protected LazyOptional<IItemHandler> internalGridCap = LazyOptional.empty();
    protected LazyOptional<IItemHandler> gridCap = LazyOptional.empty();
    protected LazyOptional<IItemHandler> externalCap = LazyOptional.empty();

    public ItemServoAttachment(IDuct<?, ?> duct, Direction side) {
        this.duct = duct;
        this.side = side;
    }

    public int getTransfer() {
        return amountTransfer;
    }
    
    public void setTransfer(int amount) {
        amountTransfer = Math.max(MIN_TRANSFER, Math.min(MAX_TRANSFER, amount));
    }
    
    public void incrementTransfer() {
        setTransfer(amountTransfer + 1);
    }
    
    public void decrementTransfer() {
        setTransfer(amountTransfer - 1);
    }
    
    public int getMinTransfer() {
        return MIN_TRANSFER;
    }
    
    public int getMaxTransfer() {
        return MAX_TRANSFER;
    }

    /**
     * Get the extraction interval based on the attached duct type.
     * Impulse ducts provide 4x faster extraction rate.
     * @return Ticks between extractions (200 for normal, 50 for impulse)
     */
    protected int getExtractionInterval() {
        // Check if attached to impulse duct
        if (duct instanceof ItemDuctBlockEntity itemDuct && itemDuct.isImpulse()) {
            return BASE_EXTRACTION_INTERVAL / 4;  // 50 ticks for impulse (24/min)
        }
        return BASE_EXTRACTION_INTERVAL;  // 200 ticks for normal (6/min)
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
        extractionCooldown = nbt.getInt("ExtractionCooldown");

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
        nbt.putInt("ExtractionCooldown", extractionCooldown);

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

        // Try to reinject overflow items first (priority over new extractions)
        if (isOverflowing()) {
            tryReinjectOverflow();
            return; // Block new extractions until overflow is cleared
        }

        // Decrement extraction cooldown
        if (extractionCooldown > 0) {
            extractionCooldown--;
            return;
        }

        // Extract items from connected inventory and route through grid
        extractAndRouteItems();

        // Set cooldown for next extraction (varies by duct type)
        extractionCooldown = getExtractionInterval();
    }

    /**
     * Try to reinject overflow items back into the grid when a valid path exists.
     */
    private void tryReinjectOverflow() {
        try {
            // Get grid for routing
            if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) {
                return;
            }

            // Get our grid node
            cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = itemGrid.getNodes().get(pos());
            if (ourNode == null) {
                return;
            }

            // Try to reinject items from overflow storage
            for (int slot = 0; slot < overflowStorage.getSlots(); slot++) {
                ItemStack stack = overflowStorage.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                // Find a valid destination for this item
                cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult destination = ourNode.findBestDestination(stack);
                if (destination == null) {
                    continue; // No valid path yet - keep in overflow
                }

                BlockPos destPos = destination.destination;
                cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = destination.pathInfo;

                // Check if destination has capacity
                int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, stack);
                if (availableCapacity <= 0) {
                    continue;
                }

                // Extract only what can fit
                int toExtract = Math.min(stack.getCount(), availableCapacity);
                ItemStack extracted = overflowStorage.extractItem(slot, toExtract, false);

                if (!extracted.isEmpty()) {
                    // Route item through grid
                    itemGrid.insertItem(extracted, pos(), side(), destPos, pathInfo.side, pathInfo.path);

                    // Clear backflow flag if we successfully reinjected
                    if (!isOverflowing()) {
                        clearBackflow();
                    }

                    // Only process one item per tick to avoid flooding the network
                    return;
                }
            }
        } catch (Exception e) {
            // Silent failure for performance
        }
    }
    
    private void extractAndRouteItems() {
        try {
            // Get connected external inventory
            LazyOptional<IItemHandler> extCap = getExternalCapability();
            if (!extCap.isPresent()) {
                return;
            }
            
            IItemHandler externalHandler = extCap.orElse(null);
            if (externalHandler == null) {
                return;
            }
            
            // Get grid for routing
            if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) {
                return;
            }
            
            // Get our grid node
            cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = itemGrid.getNodes().get(pos());
            if (ourNode == null) {
                return;
            }
            
            int remainingToExtract = amountTransfer;
            
            // Try to extract items from external inventory
            for (int slot = 0; slot < externalHandler.getSlots() && remainingToExtract > 0; slot++) {
                try {
                    ItemStack slotStack = externalHandler.getStackInSlot(slot);
                    if (slotStack.isEmpty()) {
                        continue;
                    }
                    
                    // Test extraction first
                    ItemStack extractable = externalHandler.extractItem(slot, Math.min(remainingToExtract, 64), true);
                    if (extractable.isEmpty()) {
                        continue;
                    }
                    
                    if (!filter.valid(extractable)) {
                        continue;
                    }
                    
                    // Find best destination for this item
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult destination = ourNode.findBestDestination(extractable);
                    if (destination == null) {
                        continue;
                    }
                    
                    BlockPos destPos = destination.destination;
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = destination.pathInfo;
                    
                    // Check if destination has capacity (including items in transit)
                    int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, extractable);
                    if (availableCapacity <= 0) {
                        continue;
                    }
                    
                    // Extract only what can fit and what we still need to extract
                    int toExtract = Math.min(extractable.getCount(), Math.min(remainingToExtract, availableCapacity));
                    
                    ItemStack extracted = externalHandler.extractItem(slot, toExtract, false);
                    if (extracted.isEmpty()) {
                        continue;
                    }
                    
                    // Route item through grid
                    itemGrid.insertItem(extracted, pos(), side(), destPos, pathInfo.side, pathInfo.path);
                    remainingToExtract -= extracted.getCount();
                    
                } catch (Exception e) {
                    // Silent failure for performance
                }
            }
            
        } catch (Exception e) {
            // Silent failure for performance
        }
    }
    
    private LazyOptional<IItemHandler> getExternalCapability() {
        // Always get the raw capability directly from the tile, don't use cached wrapped version
        BlockEntity tile = world().getBlockEntity(pos().relative(side));
        if (tile == null) {
            return LazyOptional.empty();
        }
        
        return tile.getCapability(ForgeCapabilities.ITEM_HANDLER, side.getOpposite());
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

    /**
     * Called when items are being backflowed to this servo due to path breakage.
     */
    public void notifyBackflow() {
        hasBackflow = true;
    }

    /**
     * Check if this servo is overflowing (has items in overflow storage).
     * Used for visual indication (red servo).
     */
    public boolean isOverflowing() {
        for (int slot = 0; slot < overflowStorage.getSlots(); slot++) {
            if (!overflowStorage.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this servo has items being backflowed to it.
     */
    public boolean hasBackflow() {
        return hasBackflow;
    }

    /**
     * Clear the backflow flag (called when backflow is complete).
     */
    public void clearBackflow() {
        hasBackflow = false;
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(ITEMS.get(ID_SERVO_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {
        // Show overflow texture (red) when servo has overflow items
        if (isOverflowing()) {
            return SERVO_ATTACHMENT_OVERFLOW_LOC;
        }
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
        buffer.writeInt(amountTransfer);
        return buffer;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {
        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
        setTransfer(buffer.readInt());
    }

    @Override
    public FriendlyByteBuf getControlPacket(FriendlyByteBuf buffer) {
        rsControl.writeToBuffer(buffer);
        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());
        buffer.writeInt(amountTransfer);
        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {
        rsControl.readFromBuffer(buffer);
        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
        setTransfer(buffer.readInt());
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