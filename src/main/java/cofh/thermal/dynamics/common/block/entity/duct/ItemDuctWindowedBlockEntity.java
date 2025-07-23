package cofh.thermal.dynamics.common.block.entity.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY;

public class ItemDuctWindowedBlockEntity extends ItemDuctBlockEntity {

    public ItemDuctWindowedBlockEntity(BlockPos pos, BlockState state) {
        super(ITEM_DUCT_WINDOWED_BLOCK_ENTITY.get(), pos, state, true);
    }

    @Override
    public boolean isWindowed() {
        return true;
    }
}