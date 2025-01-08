package cofh.thermal.dynamics.common.item;

import cofh.core.common.item.BlockItemCoFH;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridContainer;
import cofh.thermal.dynamics.api.helper.GridHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Created by covers1624 on 27/11/22.
 */
public class DuctBlockItem extends BlockItemCoFH {

    public DuctBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext ctx, BlockState state) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        boolean placed = level.setBlock(pos, state, Block.UPDATE_ALL_IMMEDIATE & ~Block.UPDATE_CLIENTS);
        if (!placed || level.isClientSide()) {
            return placed;
        }

        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof IDuct<?, ?> host) {
            IGridContainer gridContainer = IGridContainer.getGrid(level);
            if (gridContainer != null) {
                // Use the custom context to get the hit result
                CustomBlockPlaceContext customContext = new CustomBlockPlaceContext(ctx);
                BlockHitResult hitResult = customContext.getHitResultPublic();
                gridContainer.onDuctPlaced(host, computeConnectionPreference(level, hitResult));
            }
        }

        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        return true;
    }

    private Direction computeConnectionPreference(Level level, BlockHitResult hitResult) {
        if (hitResult.getType() == HitResult.Type.MISS) {
            return null;
        }
        return GridHelper.getGridHost(level, hitResult.getBlockPos()) != null ? hitResult.getDirection().getOpposite() : null;
    }

    private static class CustomBlockPlaceContext extends BlockPlaceContext {

        public CustomBlockPlaceContext(UseOnContext context) {
            super(context);
        }

        public BlockHitResult getHitResultPublic() {
            return super.getHitResult(); // Access protected method via subclass
        }
    }
}