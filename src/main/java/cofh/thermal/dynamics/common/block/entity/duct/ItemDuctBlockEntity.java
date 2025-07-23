package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemGridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemDuctBlockEntity extends DuctBlockEntity<ItemGrid, ItemGridNode> {

    private static final Logger LOGGER = LogManager.getLogger();
    private final boolean windowed;

    public ItemDuctBlockEntity(BlockPos pos, BlockState state, boolean windowed) {
        super(windowed ? ITEM_DUCT_WINDOWED_BLOCK_ENTITY.get() : ITEM_DUCT_BLOCK_ENTITY.get(), pos, state);
        this.windowed = windowed;
    }

    protected ItemDuctBlockEntity(BlockEntityType<?> tileEntityType, BlockPos pos, BlockState state, boolean windowed) {
        super(tileEntityType, pos, state);
        this.windowed = windowed;
    }

    @Override
    protected boolean canConnectToBlock(Direction dir) {
        if (!connections[dir.ordinal()].allowBlockConnection()) {
            return false;
        }
        BlockEntity tile = level.getBlockEntity(getBlockPos().relative(dir));
        if (tile == null || GridHelper.getGridHost(tile) != null) {
            return false;
        }
        
        boolean hasCapability = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite()).isPresent();
        
        LOGGER.debug("ItemDuct at {} checking connection to block at {} (direction: {}): tile={}, hasItemCapability={}",
                getBlockPos(), getBlockPos().relative(dir), dir, 
                tile != null ? tile.getClass().getSimpleName() : "null", hasCapability);
        
        return hasCapability;
    }

    @Override
    public IGridType<ItemGrid> getGridType() {
        return ITEM_GRID.get();
    }

    public boolean isWindowed() {
        return windowed;
    }

}