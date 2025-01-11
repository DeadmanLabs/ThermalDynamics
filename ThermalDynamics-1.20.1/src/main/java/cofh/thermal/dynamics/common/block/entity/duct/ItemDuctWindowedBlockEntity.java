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

import static cofh.lib.util.constants.NBTTags.TAG_RENDER_FLUID;
import static cofh.thermal.core.client.ThermalTextures.BLANK_TEXTURE;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY;

public class ItemDuctWindowedBlockEntity extends ItemDuctBlockEntity implements IGridHostUpdateable, IGridHostLuminous, IPacketHandlerTile {
    
    ItemStack renderItem = ItemStack.EMPTY;

    public ItemDuctWindowedBlockEntity(BlockPos pos, BlockState state) {
        super(ITEM_DUCT_WINDOWED_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void update() {
        TileStatePacket.sendToClient(this);
    }

    @Override
    public int getLightValue() {
        return 0; // Placeholder for item luminosity
    }

    @Nonnull
    @Override
    public ModelData getModelData() {
        modelData.setFill(renderItem.isEmpty() ? BLANK_TEXTURE : BLANK_TEXTURE); //for debug purposes
        modelData.setFillColor(0xFFFFFF); // Placeholder for item color
        return super.getModelData();
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        if (!renderItem.isEmpty()) {
            tag.put(TAG_RENDER_FLUID, new CompoundTag()); // Placeholder for item NBT
        }
        super.saveAdditional(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        renderItem = ItemStack.EMPTY; // Placeholder for item stack loading
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
        buffer.writeItemStack(renderItem, false); // Correct method for writing item stack
        super.getStatePacket(buffer);
        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {
        int prevLight = getLightValue();
        renderItem = ItemStack.EMPTY; // Placeholder for reading item stack
        if (prevLight != getLightValue()) {
            level.getChunkSource().getLightEngine().checkBlock(worldPosition);
        }
        super.handleStatePacket(buffer);
    }
}
