package cofh.thermal.dynamics.common.block;

import cofh.thermal.dynamics.init.registries.TDynBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ItemDuctBlock extends DuctBlock {

    private final boolean windowed;

    public ItemDuctBlock(Properties builder, boolean windowed) {
        super(builder, windowed ? TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY : TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY);
        this.windowed = windowed;
    }


    public boolean isWindowed() {
        return windowed;
    }

}