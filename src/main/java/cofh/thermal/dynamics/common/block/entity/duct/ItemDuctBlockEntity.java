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
            TileStatePacket.sendToClient(this);
        }
    }
    
    public void updateRenderItems() {
        if (level == null || level.isClientSide) return;
        
        // Only update windowed ducts
        if (!windowed) return;
        
        // Collect items in transit that pass through this duct
        List<ItemTransitData> newRenderItems = new ArrayList<>();
        
        ItemGrid grid = getGrid();
        if (grid != null) {
            for (ItemGrid.ItemInTransit item : grid.getItemsInTransit()) {
                Vec3 currentPos = calculateItemPosition(item);
                BlockPos itemBlockPos = new BlockPos((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
                
                // If item is currently at this duct position, add to render data
                if (itemBlockPos.equals(worldPosition)) {
                    newRenderItems.add(new ItemTransitData(item.stack, currentPos));
                    System.out.println("ItemDuctBlockEntity: Item " + item.stack.getItem() + " is at duct " + worldPosition + " at position " + currentPos);
                }
            }
        }
        
        // Always send packet if we have items OR if the count changed (including from items to no items)
        if (!newRenderItems.equals(renderItemsInTransit)) {
            System.out.println("ItemDuctBlockEntity: Updating render items from " + renderItemsInTransit.size() + " to " + newRenderItems.size() + " items for windowed duct at " + worldPosition);
            renderItemsInTransit = newRenderItems;
            System.out.println("ItemDuctBlockEntity: Sending packet with " + newRenderItems.size() + " transit items for windowed=" + windowed + " at " + worldPosition);
            TileStatePacket.sendToClient(this);
        }
    }
    
    private Vec3 calculateItemPosition(ItemGrid.ItemInTransit item) {
        double totalDistance = item.getTotalDistance();
        double currentDistance = item.distanceTraveled;
        double progress = Math.min(1.0, currentDistance / totalDistance);
        
        if (item.path.size() <= 1) {
            Vec3 origin = Vec3.atCenterOf(item.origin);
            Vec3 destination = Vec3.atCenterOf(item.destination);
            return origin.lerp(destination, progress);
        }
        
        // Calculate position along path (simplified for now)
        return Vec3.atCenterOf(worldPosition);
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
        // Server-side: collect current transit items for this duct
        List<ItemTransitData> currentTransitItems = new ArrayList<>();
        
        try {
            if (windowed && level != null && !level.isClientSide) {
                ItemGrid grid = getGrid();
                if (grid != null) {
                    for (ItemGrid.ItemInTransit item : grid.getItemsInTransit()) {
                        Vec3 currentPos = calculateItemPosition(item);
                        BlockPos itemBlockPos = new BlockPos((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
                        
                        // If item is currently at this duct position, add to packet data
                        if (itemBlockPos.equals(worldPosition)) {
                            currentTransitItems.add(new ItemTransitData(item.stack, currentPos));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Don't let packet errors prevent normal operation
            System.out.println("ItemDuctBlockEntity: Error collecting transit items for packet: " + e.getMessage());
        }
        
        buffer.writeInt(currentTransitItems.size());
        for (ItemTransitData itemData : currentTransitItems) {
            buffer.writeItem(itemData.stack);
            buffer.writeDouble(itemData.position.x);
            buffer.writeDouble(itemData.position.y);
            buffer.writeDouble(itemData.position.z);
        }
        
        if (!currentTransitItems.isEmpty()) {
            System.out.println("ItemDuctBlockEntity: getStatePacket sending " + currentTransitItems.size() + " items for windowed=" + windowed + " at " + worldPosition);
        }
        
        super.getStatePacket(buffer);
        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {
        renderItemsInTransit.clear();
        int count = buffer.readInt();
        for (int i = 0; i < count; i++) {
            ItemStack stack = buffer.readItem();
            double x = buffer.readDouble();
            double y = buffer.readDouble();
            double z = buffer.readDouble();
            ItemTransitData transitData = new ItemTransitData(stack, new Vec3(x, y, z));
            renderItemsInTransit.add(transitData);
        }
        
        super.handleStatePacket(buffer);
        
        System.out.println("ItemDuctBlockEntity: Received " + count + " transit items on client for windowed=" + windowed + " at " + worldPosition);
        
        // Debug: Show what we actually stored
        for (int i = 0; i < renderItemsInTransit.size(); i++) {
            ItemTransitData item = renderItemsInTransit.get(i);
            System.out.println("ItemDuctBlockEntity: Stored transit item " + i + ": " + item.stack.getItem() + " at " + item.position);
        }
        
        // Verify the data is accessible
        List<ItemTransitData> testList = getRenderItemsInTransit();
        System.out.println("ItemDuctBlockEntity: getRenderItemsInTransit() returns " + testList.size() + " items");
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