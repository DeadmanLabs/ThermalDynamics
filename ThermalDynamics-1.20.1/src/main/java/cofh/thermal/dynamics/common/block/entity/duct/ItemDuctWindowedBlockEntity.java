package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.core.common.network.packet.client.TileStatePacket;
import cofh.core.util.helpers.ItemHelper;
import cofh.core.util.helpers.RenderHelper;
import cofh.lib.api.block.entity.IPacketHandlerTile;
import cofh.thermal.dynamics.api.grid.IGridHostLuminous;
import cofh.thermal.dynamics.api.grid.IGridHostUpdateable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static cofh.lib.util.constants.NBTTags.TAG_RENDER_ITEM;
import static cofh.thermal.core.client.ThermalTextures.BLANK_TEXTURE;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY;

public class ItemDuctWindowedBlockEntity extends ItemDuctBlockEntity implements IGridHostUpdateable, IGridHostLuminous, IPacketHandlerTile {
    
    ItemStack renderItem = ItemStack.EMPTY;

    public ItemDuctWindowedBlockEntity(BlockPos pos, BlockState state) {
        super(ITEM_DUCT_WINDOWED_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void update() {
        TitleStatePacket.sendToClient(this);
    }

    @Override
    public int getLightValue() {
        return ItemHelper.luminosity(renderItem);
    }

    @Nonnull
    @Override
    public ModelData getModelData() {
        modelData.setFill(renderItem.isEmpty() ? BLANK_TEXTURE : RenderHelper.getItemTexture(renderItem).contents().name());
        modelData.setFillColor(ItemHelper.color(renderItem));
        return super.getModelData();
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        if (!renderItem.isEmpty()) {
            tag.put(TAG_RENDER_ITEM, renderItem.writeToNBT(new CompoundTag()));
        }
        super.saveAdditional(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        renderItem = ItemStack.loadItemStackFromNBT(tag.getCompound(TAG_RENDER_ITEM));
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public FriendlyByteBuf getStatePacket(FriendlyByteBuf buffer) {
        renderItem = getGrid().getRenderItem();
        buffer.writeItemStack(renderItem);
        super.getStatePacket(buffer);
        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {
        int prevLight = getLightValue();
        renderItem = buffer.readItemStack();
        if (prevLight != getLightValue()) {
            level.getChunkSource().getLightEngine().checkBlock(worldPosition);
        }
        Superclass.handleStatePacket(buffer);
    }
}
