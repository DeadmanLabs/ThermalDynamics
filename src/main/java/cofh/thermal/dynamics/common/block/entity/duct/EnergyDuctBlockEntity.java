package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.energy.EnergyGrid;
import cofh.thermal.dynamics.common.grid.energy.EnergyGridNode;
import cofh.thermal.lib.util.ThermalEnergyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ENERGY_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ENERGY_GRID;

public class EnergyDuctBlockEntity extends DuctBlockEntity<EnergyGrid, EnergyGridNode> {

    private static final Logger LOGGER = LogManager.getLogger();

    public EnergyDuctBlockEntity(BlockPos pos, BlockState state) {

        super(ENERGY_DUCT_BLOCK_ENTITY.get(), pos, state);
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
        
        // Check both thermal energy capability and standard Forge energy capability
        boolean hasThermalCapability = tile.getCapability(ThermalEnergyHelper.getBaseEnergySystem(), dir.getOpposite()).isPresent();
        boolean hasForgeCapability = tile.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite()).isPresent();
        boolean hasCapability = hasThermalCapability || hasForgeCapability;
        
        LOGGER.debug("EnergyDuct at {} checking connection to block at {} (direction: {}): tile={}, hasThermalCapability={}, hasForgeCapability={}, hasAnyEnergyCapability={}",
                getBlockPos(), getBlockPos().relative(dir), dir, 
                tile != null ? tile.getClass().getSimpleName() : "null", 
                hasThermalCapability, hasForgeCapability, hasCapability);
        
        return hasCapability;
    }

    @Override
    public IGridType<EnergyGrid> getGridType() {

        return ENERGY_GRID.get();
    }

}
