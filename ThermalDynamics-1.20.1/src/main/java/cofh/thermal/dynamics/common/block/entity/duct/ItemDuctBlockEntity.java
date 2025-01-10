package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.grid.ItemGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

public class ItemDuctBlockEntity extends DuctBlockEntity implements IDuct<ItemStackHandler, IItemHandler> {

    protected ItemGrid grid;

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
        this.grid = new ItemGrid(this);
    }

    @Override
    public ItemGrid getGrid() {
        return grid;
    }

    @Override
    public void setGrid(ItemGrid grid) {
        this.grid = grid;
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        grid.invalidate();
    }
}
