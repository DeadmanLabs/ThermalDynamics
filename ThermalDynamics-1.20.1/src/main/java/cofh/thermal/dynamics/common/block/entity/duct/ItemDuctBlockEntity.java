package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.core.util.helpers.ItemHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemGridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemDuctBlockEntity extends DuctBlockEntity<ItemGrid, ItemGridNode> {
    
    public ItemDuctBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {
        super(ITEM_DUCT_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected boolean canConnectToBlock(Direction dir) {
        if (!connections[dir.ordinal()].allowBlockConnection()) {
            return false;
        }
        BlockEntity tile = Level.getBlockEntity(getBlockPos().relative(dir));
        if (tile == null || GridHelper.getGridHost(tile) != null) {
            return false;
        }
        return tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite()).isPresent();
    }

    @Override
    public IGridType<ItemGrid> getGridType() {
        return ITEM_GRID.get();
    }

    @Override
    public boolean canConnectTo(IDuct<?, ?> other, Direction dir) {
        if (!level.isClientSide && other.getGrid() instanceof ItemGrid otherGrid) {
            ItemStack myItem = getGrid().getItem();
            ItemStack otherItem = otherGrid.getItem();
            if (!myItem.isEmpty() && !otherItem.isEmpty() && !ItemHelper.itemsEqual(myItem, otherItem)) {
                return false;
            }
        }
        return Superclass.canConnectTo(other, dir);
    }
}
