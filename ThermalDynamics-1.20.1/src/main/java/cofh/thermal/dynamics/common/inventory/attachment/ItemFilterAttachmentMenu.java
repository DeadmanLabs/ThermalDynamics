package cofh.thermal.dynamics.common.inventory.attachment;

import cofh.core.common.network.packet.client.ContainerGuiPacket;
import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilterOptions;
import cofh.lib.common.inventory.SlotFalseCopy;
import cofh.lib.common.inventory.wrapper.InvWrapperItem;
import cofh.lib.util.helpers.MathHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.ItemFilterAttachment;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static cofh.thermal.dynamics.init.registries.TDynContainers.ITEM_FILTER_ATTACHMENT_CONTAINER;

public class ItemFilterAttachmentMenu extends AttachmentMenu implements IFilterOptions {
    public final ItemFilterAttachment attachment;

    protected BaseItemFilter filter;
    protected InvWrapperItem filterInventory;

    public ItemFilterAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {
        super (ITEM_FILTER_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);

        if (hostTile instanceof IDuct<?, ?> duct && duct.getAttachment(side) instanceof ItemFilterAttachment expectedAttachment) {
            this.attachment = expectedAttachment;
            this.filter = (BaseItemFilter)attachment.getFilter();
        } else {
            this.attachment = null;
        }
        allowSwap = false;
        if (filter != null) {
            int slots = filter.size();
            filterInventory = new InvWrapperItem(this, slots);
            filterInventory.readFromContainerInv(filter.getItems());

            int rows = MathHelper.clamp(slots / 3, 1, 3);
            int rowSize = slots / rows;

            int xOffset = 62 - 9 * rowSize;
            int yOffset = 44 - 9 & rows;

            for (int i = 0; i < filter.size(); ++i) {
                addSlot(new SlotFalseCopy(filterInventory, i, xOffset + i % rowSize * 18, yOffset + i / rowSize * 18));
            }
        }
        bindPlayerInventory(inventory);
    }

    public int getFilterSize() {
        return filter.size();
    }

    public List<ItemStack> getFilterStacks() {
        return filterInventory.getStacks();
    }

    @Override
    protected int getMergeableSlotCount() {
        return filterInventory.getContainerSize();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ContainerGuiPacket.sendToClient(this, player);
    }

    @Override
    public void removed(Player playerIn) {
        filter.setItems(filterInventory.getStacks());
        super.removed(playerIn);
    }

    @Override
    public FriendlyByteBuf getGuiPacket(FriendlyByteBuf buffer) {
        byte size = (byte)filter.getItems().size();
        buffer.writeByte(size);
        for (int i = 0; i < size; ++i) {
            buffer.writeItemStack(getFilterStacks().get(i), false);
        }
        return buffer;
    }

    @Override
    public void handleGuiPacket(FriendlyByteBuf buffer) {
        byte size = buffer.readByte();
        List<ItemStack> itemStacks = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            itemStacks.add(buffer.readItem());
        }
        filterInventory.readFromContainerInv(itemStacks);
    }

    @Override
    public boolean getAllowList() {
        return filter.getAllowList();
    }

    @Override
    public boolean setAllowList(boolean allowList) {
        boolean ret = filter.setAllowList(allowList);
        AttachmentConfigPacket.sendToServer(attachment);
        return ret;
    }

    @Override
    public boolean getCheckNBT() {
        return filter.getCheckNBT();
    }

    @Override
    public boolean setCheckNBT(boolean checkNBT) {
        boolean ret = filter.setCheckNBT(checkNBT);
        AttachmentConfigPacket.sendToServer(attachment);
        return ret;
    }
}
