package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.core.common.network.packet.client.TileStatePacket;
import cofh.lib.api.block.entity.IPacketHandlerTile;
import cofh.thermal.dynamics.api.grid.IGridHostUpdateable;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemGridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_WINDOWED_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemDuctBlockEntity extends DuctBlockEntity<ItemGrid, ItemGridNode> implements IGridHostUpdateable, IPacketHandlerTile {

    private static final Logger LOGGER = LogManager.getLogger();
    private final boolean windowed;
    
    // Client-side item transit data for rendering
    protected List<ItemTransitData> renderItemsInTransit = new ArrayList<>();

    public ItemDuctBlockEntity(BlockPos pos, BlockState state, boolean windowed) {
        super(windowed ? ITEM_DUCT_WINDOWED_BLOCK_ENTITY.get() : ITEM_DUCT_BLOCK_ENTITY.get(), pos, state);
        this.windowed = windowed;
    }

    protected ItemDuctBlockEntity(BlockEntityType<?> tileEntityType, BlockPos pos, BlockState state, boolean windowed) {
        super(tileEntityType, pos, state);
        this.windowed = windowed;
    }

    public boolean isWindowed() {
        return windowed;
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
    
    public List<ItemTransitData> getRenderItemsInTransit() {
        return renderItemsInTransit;
    }
    
    @Override
    public void update() {
        // Only send packets if we're properly initialized
        if (level != null && !level.isClientSide && hasLevel()) {
            System.out.println("SERVER: ItemDuctBlockEntity.update() called at " + worldPosition + " windowed=" + windowed);
            // Update render items before sending packet
            if (windowed) {
                updateRenderItems();
                System.out.println("SERVER: updateRenderItems() completed, sending " + renderItemsInTransit.size() + " items to client");
            }
            TileStatePacket.sendToClient(this);
        }
    }
    
    public void updateRenderItems() {
        System.out.println("SERVER: updateRenderItems() called at " + worldPosition + " windowed=" + windowed);
        
        if (level == null || level.isClientSide) {
            System.out.println("SERVER: Early return - level=" + level + " isClientSide=" + (level != null ? level.isClientSide : "null"));
            return;
        }
        
        // Only update windowed ducts
        if (!windowed) {
            System.out.println("SERVER: Early return - not windowed");
            return;
        }
        
        // Collect items in transit that should be rendered in this duct
        List<ItemTransitData> newRenderItems = new ArrayList<>();
        
        ItemGrid grid = getGrid();
        System.out.println("SERVER: Got grid: " + grid);
        
        if (grid != null) {
            Collection<ItemGrid.ItemInTransit> itemsInTransit = grid.getItemsInTransit();
            System.out.println("SERVER: Grid has " + itemsInTransit.size() + " items in transit");
            
            for (ItemGrid.ItemInTransit item : itemsInTransit) {
                System.out.println("SERVER: Processing item " + item.stack.getItem() + " origin=" + item.origin + " dest=" + item.destination + " path=" + item.path);
                
                // Check if this duct is part of the item's travel path
                boolean isOnPath = isDuctOnItemPath(item);
                System.out.println("SERVER: isDuctOnItemPath(" + worldPosition + ") = " + isOnPath);
                
                if (isOnPath) {
                    // Calculate where the item should be rendered relative to this duct
                    Vec3 renderPosition = calculateItemRenderPositionForDuct(item);
                    System.out.println("SERVER: calculateItemRenderPositionForDuct returned: " + renderPosition);
                    
                    if (renderPosition != null) {
                        newRenderItems.add(new ItemTransitData(item.stack, renderPosition));
                        System.out.println("SERVER: Added item to render list at position " + renderPosition);
                    } else {
                        System.out.println("SERVER: Render position was null, not adding item");
                    }
                } else {
                    System.out.println("SERVER: Duct not on item path, skipping");
                }
            }
        } else {
            System.out.println("SERVER: Grid is null!");
        }
        
        System.out.println("SERVER: Final render items count: " + newRenderItems.size());
        renderItemsInTransit = newRenderItems;
    }
    
    private Vec3 calculateItemPosition(ItemGrid.ItemInTransit item) {
        double totalDistance = item.getTotalDistance();
        double currentDistance = item.distanceTraveled;
        double progress = Math.min(1.0, currentDistance / totalDistance);
        
        if (item.path.size() <= 1) {
            // Direct connection - interpolate between origin and destination
            Vec3 origin = Vec3.atCenterOf(item.origin);
            Vec3 destination = Vec3.atCenterOf(item.destination);
            return origin.lerp(destination, progress);
        }
        
        // Multi-segment path - calculate position along the full path
        return calculatePositionAlongPath(item, progress);
    }
    
    private Vec3 calculatePositionAlongPath(ItemGrid.ItemInTransit item, double progress) {
        // Calculate cumulative distances for each path segment
        double totalDistance = item.getTotalDistance();
        double targetDistance = progress * totalDistance;
        
        Vec3 currentPos = Vec3.atCenterOf(item.origin);
        double accumulatedDistance = 0;
        
        // Create list of all waypoints: origin -> path nodes -> destination
        List<Vec3> waypoints = new ArrayList<>();
        waypoints.add(Vec3.atCenterOf(item.origin));
        for (BlockPos pathPos : item.path) {
            waypoints.add(Vec3.atCenterOf(pathPos));
        }
        waypoints.add(Vec3.atCenterOf(item.destination));
        
        // Find which segment the item is currently on
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 segmentStart = waypoints.get(i);
            Vec3 segmentEnd = waypoints.get(i + 1);
            double segmentDistance = segmentStart.distanceTo(segmentEnd);
            
            if (targetDistance <= accumulatedDistance + segmentDistance) {
                // Item is on this segment
                double segmentProgress = segmentDistance > 0 ? (targetDistance - accumulatedDistance) / segmentDistance : 0;
                return segmentStart.lerp(segmentEnd, Math.min(1.0, segmentProgress));
            }
            
            accumulatedDistance += segmentDistance;
        }
        
        // Fallback to destination
        return Vec3.atCenterOf(item.destination);
    }
    
    /**
     * Check if this duct should render the item - simplified approach
     */
    private boolean isDuctOnItemPath(ItemGrid.ItemInTransit item) {
        // Check if this duct is anywhere in the item's path
        boolean isOrigin = worldPosition.equals(item.origin);
        boolean isDestination = worldPosition.equals(item.destination);
        boolean isInPath = false;
        
        for (BlockPos pathPos : item.path) {
            if (worldPosition.equals(pathPos)) {
                isInPath = true;
                break;
            }
        }
        
        return isOrigin || isDestination || isInPath;
    }
    
    /**
     * Calculate where to render the item within this specific duct based on the item's progress
     */
    private Vec3 calculateItemRenderPositionForDuct(ItemGrid.ItemInTransit item) {
        // Get the item's current global position
        Vec3 globalItemPos = calculateItemPosition(item);
        
        // Create the full path including origin and destination
        List<BlockPos> fullPath = new ArrayList<>();
        fullPath.add(item.origin);
        fullPath.addAll(item.path);
        fullPath.add(item.destination);
        
        // Find which segment the item is currently on
        double totalDistanceCovered = 0;
        double targetDistance = item.distanceTraveled;
        
        for (int i = 0; i < fullPath.size() - 1; i++) {
            BlockPos segmentStart = fullPath.get(i);
            BlockPos segmentEnd = fullPath.get(i + 1);
            double segmentLength = Math.sqrt(segmentStart.distSqr(segmentEnd));
            
            // Check if this duct is involved in this segment
            boolean isDuctInThisSegment = worldPosition.equals(segmentStart) || worldPosition.equals(segmentEnd);
            
            if (isDuctInThisSegment && targetDistance >= totalDistanceCovered && targetDistance <= totalDistanceCovered + segmentLength) {
                // Item is currently traveling through a segment involving this duct
                double progressInSegment = (targetDistance - totalDistanceCovered) / segmentLength;
                
                // If this duct is the start of the segment, show item moving out
                // If this duct is the end of the segment, show item moving in
                if (worldPosition.equals(segmentStart)) {
                    // Item is leaving this duct
                    Vec3 startPos = Vec3.atCenterOf(segmentStart);
                    Vec3 endPos = Vec3.atCenterOf(segmentEnd);
                    return startPos.lerp(endPos, progressInSegment);
                } else if (worldPosition.equals(segmentEnd)) {
                    // Item is approaching this duct
                    Vec3 startPos = Vec3.atCenterOf(segmentStart);
                    Vec3 endPos = Vec3.atCenterOf(segmentEnd);
                    return startPos.lerp(endPos, progressInSegment);
                }
            }
            
            totalDistanceCovered += segmentLength;
        }
        
        // If we get here, item might be stationary at this duct position
        if (worldPosition.equals(item.origin) || worldPosition.equals(item.destination)) {
            return Vec3.atCenterOf(worldPosition);
        }
        
        for (BlockPos pathPos : item.path) {
            if (worldPosition.equals(pathPos)) {
                return Vec3.atCenterOf(worldPosition);
            }
        }
        
        return null; // Don't render if item isn't related to this duct
    }
    
    // region NETWORK
    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    // STATE
    @Override
    public FriendlyByteBuf getStatePacket(FriendlyByteBuf buffer) {
        // Send the current render items that were calculated for this duct
        buffer.writeInt(renderItemsInTransit.size());
        for (ItemTransitData itemData : renderItemsInTransit) {
            buffer.writeItem(itemData.stack);
            buffer.writeDouble(itemData.position.x);
            buffer.writeDouble(itemData.position.y);
            buffer.writeDouble(itemData.position.z);
        }
        
        super.getStatePacket(buffer);
        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {
        System.out.println("CLIENT: handleStatePacket called at " + worldPosition);
        
        renderItemsInTransit.clear();
        int count = buffer.readInt();
        System.out.println("CLIENT: Reading " + count + " items from packet");
        
        for (int i = 0; i < count; i++) {
            ItemStack stack = buffer.readItem();
            double x = buffer.readDouble();
            double y = buffer.readDouble();
            double z = buffer.readDouble();
            ItemTransitData transitData = new ItemTransitData(stack, new Vec3(x, y, z));
            renderItemsInTransit.add(transitData);
            System.out.println("CLIENT: Received item " + stack.getItem() + " at position (" + x + ", " + y + ", " + z + ")");
        }
        
        super.handleStatePacket(buffer);
        
        System.out.println("CLIENT: Final renderItemsInTransit size: " + renderItemsInTransit.size());
    }
    // endregion
    
    /**
     * Simple data class for client-side item rendering
     */
    public static class ItemTransitData {
        public final ItemStack stack;
        public final Vec3 position;
        
        public ItemTransitData(ItemStack stack, Vec3 position) {
            this.stack = stack.copy();
            this.position = position;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ItemTransitData other = (ItemTransitData) obj;
            return ItemStack.matches(stack, other.stack) && position.equals(other.position);
        }
        
        @Override
        public int hashCode() {
            return position.hashCode();
        }
    }

}