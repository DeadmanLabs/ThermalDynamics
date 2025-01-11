package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Predicate;

import static cofh.lib.util.constants.NBTTags.TAG_MODE;
import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.*;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;

public class ItemServoAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final int ITEM_TRANSFER_RATE = 10; // Example transfer rate for items

    public int getTransfer() {
        return ITEM_TRANSFER_RATE;
    }

    public enum ServoMode {
        BIDIRECTIONAL, TO_EXTERNAL_ONLY, TO_GRID_ONLY;

        public static final ServoMode[] VALUES = values();
    }

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.item_servo");

    protected final IDuct<?, ?> duct;
    protected final Direction side;

    protected ServoMode mode = ServoMode.BIDIRECTIONAL;

    protected BaseItemFilter filter = new BaseItemFilter(15);
    protected RedstoneControlLogic rsControl = new RedstoneControlLogic(this);

    protected LazyOptional<IItemHandler> gridCap = LazyOptional.empty();
    protected LazyOptional<IItemHandler> externalCap = LazyOptional.empty();

    public ItemServoAttachment(IDuct<?, ?> duct, Direction side) {

        this.duct = duct;
        this.side = side;
    }

    public ServoMode getServoMode() {

        return mode;
    }

    public void setServoMode(ServoMode mode) {

        this.mode = mode;
        AttachmentConfigPacket.sendToServer(this);
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
        mode = ServoMode.VALUES[nbt.getByte(TAG_MODE)];

        filter.read(nbt);
        rsControl.read(nbt);

        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        nbt.putString(TAG_TYPE, SERVO);
        nbt.putByte(TAG_MODE, (byte) mode.ordinal());

        filter.write(nbt);
        rsControl.write(nbt);

        return nbt;
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
                gridCap = LazyOptional.of(() -> new WrappedGridItemHandler(handler, e -> rsControl.getState() && filter.valid(e) || !rsControl.getState()));
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
                externalCap = LazyOptional.of(() -> new WrappedExternalItemHandler(handler, e -> rsControl.getState() && filter.valid(e) || !rsControl.getState()));
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

        buffer.writeByte(mode.ordinal());

        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());

        return buffer;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {

        ServoMode prevMode = mode;
        mode = ServoMode.VALUES[buffer.readByte()];

        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());

        if (mode != prevMode) {
            onControlUpdate();
        }
    }

    @Override
    public FriendlyByteBuf getControlPacket(FriendlyByteBuf buffer) {

        buffer.writeByte(mode.ordinal());
        rsControl.writeToBuffer(buffer);

        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());

        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {

        mode = ServoMode.VALUES[buffer.readByte()];
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

        mode = ServoMode.VALUES[tag.getByte("ServoAttachmentMode")];
        rsControl.readSettings(tag);
        filter.read(tag);

        onControlUpdate();
    }

    @Override
    public void writeConveyableData(Player player, CompoundTag tag) {

        tag.putByte("ServoAttachmentMode", (byte) mode.ordinal());
        rsControl.writeSettings(tag);
        filter.write(tag);
    }
    // endregion

    // region GRID WRAPPER CLASS
    private class WrappedGridItemHandler implements IItemHandler {

        protected IItemHandler wrappedHandler;

        protected Predicate<ItemStack> validator;

        public WrappedGridItemHandler(IItemHandler wrappedHandler, Predicate<ItemStack> validator) {

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
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {

            if (mode == ServoMode.TO_EXTERNAL_ONLY) {
                return false;
            }
            return validator.test(stack) && wrappedHandler.isItemValid(slot, stack);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {

            if (mode == ServoMode.TO_EXTERNAL_ONLY) {
                return stack;
            }
            return validator.test(stack) ? wrappedHandler.insertItem(slot, stack, simulate) : stack;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            if (mode == ServoMode.TO_GRID_ONLY) {
                return ItemStack.EMPTY;
            }
            return validator.test(wrappedHandler.extractItem(slot, amount, true)) ? wrappedHandler.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return wrappedHandler.getSlotLimit(slot);
        }
    }
    // endregion

    // region EXTERNAL WRAPPER CLASS
    private class WrappedExternalItemHandler implements IItemHandler {

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
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {

            if (mode == ServoMode.TO_GRID_ONLY) {
                return false;
            }
            return validator.test(stack) && wrappedHandler.isItemValid(slot, stack);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {

            if (mode == ServoMode.TO_GRID_ONLY) {
                return stack;
            }
            return validator.test(stack) ? wrappedHandler.insertItem(slot, stack, simulate) : stack;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            if (mode == ServoMode.TO_EXTERNAL_ONLY) {
                return ItemStack.EMPTY;
            }
            return validator.test(wrappedHandler.extractItem(slot, amount, true)) ? wrappedHandler.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return wrappedHandler.getSlotLimit(slot);
        }
    }
    // endregion
}
