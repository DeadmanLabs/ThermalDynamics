package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.Grid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

/**
 * Grid for managing item transport through ducts.
 * Items travel through the network with transit time rather than instant teleportation.
 */
public class ItemGrid extends Grid<ItemGrid, ItemGridNode> {

    private static final Logger LOGGER = LogManager.getLogger();
    
    // Track active grids for client-side rendering
    private static final Map<Level, Set<ItemGrid>> activeGrids = new ConcurrentHashMap<>();
    
    // Speed in blocks per tick (0.05 = 1 block per second at 20 TPS for better visibility)
    private static final float ITEM_SPEED = 0.05f;

    // Queue for items in transit - using concurrent queue for thread safety
    private final Queue<ItemInTransit> itemsInTransit = new ConcurrentLinkedQueue<>();

    // Items that need to be returned due to backup
    private final Queue<ItemInTransit> returningItems = new ConcurrentLinkedQueue<>();

    // Track items in transit to destinations for capacity calculation
    private final Map<BlockPos, Map<Direction, Integer>> itemsInTransitToDestination = new ConcurrentHashMap<>();

    /**
     * Topology version when items were last checked for path validity.
     * When this differs from current topology, all items need path revalidation.
     */
    private long lastCheckedTopologyVersion = -1;

    public ItemGrid(UUID id, Level world) {
        super(ITEM_GRID.get(), id, world);
        
        // Register this grid for client-side rendering (both server and client)
        activeGrids.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet()).add(this);
        // Grid registered for item transport
    }
    
    /**
     * Get all active ItemGrids in a level for client-side rendering
     */
    public static Collection<ItemGrid> getActiveGrids(Level level) {
        Set<ItemGrid> grids = activeGrids.get(level);
        return grids != null ? new ArrayList<>(grids) : Collections.emptyList();
    }
    
    /**
     * Clean up grid tracking when grid is destroyed
     */
    public void cleanup() {
        if (world != null) {
            Set<ItemGrid> grids = activeGrids.get(world);
            if (grids != null) {
                grids.remove(this);
                if (grids.isEmpty()) {
                    activeGrids.remove(world);
                }
            }
        }
    }
    
    @Override
    public ItemGridNode newNode() {
        return new ItemGridNode(this);
    }

    @Override
    public void tick() {
        super.tick();

        // Skip item processing if no items are in transit (optimization)
        boolean hasItems = !itemsInTransit.isEmpty() || !returningItems.isEmpty();

        if (hasItems) {
            // Check if topology changed - need to validate/backflow items
            long currentTopology = getTopologyVersion();
            if (lastCheckedTopologyVersion != currentTopology) {
                handleTopologyChange();
                lastCheckedTopologyVersion = currentTopology;
            }

            // Process items in transit
            processItemsInTransit();

            // Process returning items
            processReturningItems();

            // Update render data only for windowed ducts with items nearby
            updateRenderData();
        }
    }
    
    /**
     * Handle topology changes by validating all items' paths.
     * Items with invalid paths are reversed back to their origin.
     */
    private void handleTopologyChange() {
        Iterator<ItemInTransit> iterator = itemsInTransit.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();

            // Check if the item's destination is still valid
            if (!isPathValid(item)) {
                // Path is broken - initiate backflow to origin
                item.reverse();
                item.distanceTraveled = 0;
                removeItemFromTransitTracking(item);
                returningItems.add(item);
                iterator.remove();

                // Notify the origin servo about the backflow
                notifyServoOfBackflow(item);
            }
        }
    }

    /**
     * Check if an item's path is still valid after a topology change.
     */
    private boolean isPathValid(ItemInTransit item) {
        // Check if destination still exists and has item handler
        BlockEntity destTile = world.getBlockEntity(item.destination);
        if (destTile == null) {
            return false;
        }

        // Check if the destination can still accept items
        if (!destTile.getCapability(ForgeCapabilities.ITEM_HANDLER, item.destinationSide).isPresent()) {
            return false;
        }

        // Check if the path through ducts is still connected
        // We check that each node in the path still exists in the grid
        for (BlockPos pathPos : item.path) {
            if (!getNodes().containsKey(pathPos)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Notify a servo that an item is being backflowed to it.
     */
    private void notifyServoOfBackflow(ItemInTransit item) {
        // The item's new destination (after reverse) is the original origin
        BlockPos originPos = item.destination;
        Direction originSide = item.destinationSide;

        ItemGridNode originNode = getNodes().get(originPos);
        if (originNode != null && originNode.getDuct() != null) {
            if (originNode.getDuct().getAttachment(originSide) instanceof cofh.thermal.dynamics.common.attachment.ItemServoAttachment servo) {
                servo.notifyBackflow();
            }
        }
    }

    private void updateRenderData() {
        if (world.isClientSide || itemsInTransit.isEmpty()) {
            return;
        }
        
        // Update only windowed duct entities with current item transit data
        for (ItemGridNode node : getNodes().values()) {
            try {
                if (node.getDuct() instanceof cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity ductEntity) {
                    if (ductEntity.isWindowed() && ductEntity.hasLevel()) {
                        ductEntity.update();
                    }
                }
            } catch (Exception e) {
                // Don't let render updates break the grid - silent failure
            }
        }
    }

    private void processItemsInTransit() {
        Iterator<ItemInTransit> iterator = itemsInTransit.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();

            // Note: Per-tick rerouting removed - path validation now handled by handleTopologyChange()
            // This significantly improves performance by avoiding O(N) scans every tick

            // Update item position based on actual speed
            item.distanceTraveled += ITEM_SPEED;
            double totalDistance = item.getTotalDistance();

            // Check if item reached destination
            if (item.distanceTraveled >= totalDistance) {
                // Try to insert into destination
                if (tryInsertItem(item)) {
                    // Successfully inserted - remove from transit tracking
                    removeItemFromTransitTracking(item);
                    iterator.remove();
                } else {
                    // Backup occurred - reverse path and add to returning queue
                    item.reverse();
                    // Reset distance traveled for return journey
                    item.distanceTraveled = 0;
                    removeItemFromTransitTracking(item);
                    returningItems.add(item);
                    iterator.remove();
                }
            }
        }
    }

    private void processReturningItems() {
        Iterator<ItemInTransit> iterator = returningItems.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();
            
            double totalDistance = item.getTotalDistance();
            
            // Update item position
            item.distanceTraveled += ITEM_SPEED;
            
            // Check if item reached origin
            if (item.distanceTraveled >= totalDistance) {
                // Store in original servo's overflow (infinite storage)
                handleReturnedItem(item);
                iterator.remove();
            }
        }
    }

    private boolean tryInsertItem(ItemInTransit item) {
        BlockPos destPos = item.destination;
        Direction destSide = item.destinationSide;
        
        BlockEntity tile = world.getBlockEntity(destPos);
        if (tile == null) return false;
        
        LazyOptional<IItemHandler> cap = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, destSide);
        if (!cap.isPresent()) return false;
        
        IItemHandler handler = cap.orElse(null);
        if (handler == null) return false;
        
        // Try to insert the item
        ItemStack remaining = item.stack.copy();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
            if (remaining.isEmpty()) {
                return true;
            }
        }
        
        // Update stack with remaining
        item.stack = remaining;
        return false;
    }

    private void handleReturnedItem(ItemInTransit item) {
        BlockPos originPos = item.origin;
        Direction originSide = item.originSide;
        Level world = getLevel();
        
        if (world != null && !world.isClientSide) {
            // Find the original servo that extracted this item
            ItemGridNode originNode = getNodes().get(originPos);
            if (originNode != null && originNode.getDuct() != null) {
                if (originNode.getDuct().getAttachment(originSide) instanceof cofh.thermal.dynamics.common.attachment.ItemServoAttachment servo) {
                    // Store in original servo's overflow (infinite storage)
                    servo.storeOverflowItem(item.stack);
                    LOGGER.debug("Stored overflow item {} in original servo at {} direction {}", 
                        item.stack, originPos, originSide);
                    return;
                }
            }
            
            // If original servo not found, drop item (this shouldn't happen)
            LOGGER.warn("Original servo not found for overflow item {} at {} direction {} - dropping", 
                item.stack, originPos, originSide);
            net.minecraft.world.entity.item.ItemEntity itemEntity = new net.minecraft.world.entity.item.ItemEntity(
                world, 
                originPos.getX() + 0.5, 
                originPos.getY() + 0.5, 
                originPos.getZ() + 0.5, 
                item.stack
            );
            world.addFreshEntity(itemEntity);
        }
    }

    public void insertItem(ItemStack stack, BlockPos origin, Direction originSide, BlockPos destination, Direction destinationSide, List<BlockPos> path) {
        if (stack.isEmpty()) {
            return;
        }
        
        ItemInTransit item = new ItemInTransit(stack.copy(), origin, originSide, destination, destinationSide, path);
        itemsInTransit.add(item);
        
        // Track this item as in transit to destination
        addItemToTransitTracking(item);
    }
    
    private void addItemToTransitTracking(ItemInTransit item) {
        itemsInTransitToDestination.computeIfAbsent(item.destination, k -> new ConcurrentHashMap<>())
            .merge(item.destinationSide, item.stack.getCount(), Integer::sum);
    }
    
    private void removeItemFromTransitTracking(ItemInTransit item) {
        Map<Direction, Integer> destMap = itemsInTransitToDestination.get(item.destination);
        if (destMap != null) {
            destMap.compute(item.destinationSide, (dir, count) -> {
                if (count == null) return null;
                int newCount = count - item.stack.getCount();
                return newCount <= 0 ? null : newCount;
            });
            if (destMap.isEmpty()) {
                itemsInTransitToDestination.remove(item.destination);
            }
        }
    }

    public Collection<ItemInTransit> getItemsInTransit() {
        return Collections.unmodifiableCollection(itemsInTransit);
    }
    
    /**
     * Get available capacity at destination considering items in transit
     */
    public int getAvailableCapacity(BlockPos destination, Direction side, ItemStack stack) {
        BlockEntity tile = world.getBlockEntity(destination);
        if (tile == null) return 0;
        
        LazyOptional<IItemHandler> cap = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
        if (!cap.isPresent()) return 0;
        
        IItemHandler handler = cap.orElse(null);
        if (handler == null) return 0;
        
        // Calculate total capacity
        int totalCapacity = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            int slotLimit = handler.getSlotLimit(slot);
            
            if (existing.isEmpty()) {
                if (handler.isItemValid(slot, stack)) {
                    totalCapacity += Math.min(stack.getMaxStackSize(), slotLimit);
                }
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                totalCapacity += Math.min(stack.getMaxStackSize(), slotLimit) - existing.getCount();
            }
        }
        
        // Subtract items in transit to this destination
        Map<Direction, Integer> transitMap = itemsInTransitToDestination.get(destination);
        if (transitMap != null) {
            Integer inTransit = transitMap.get(side);
            if (inTransit != null) {
                totalCapacity -= inTransit;
            }
        }
        
        return Math.max(0, totalCapacity);
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {
        if (GridHelper.getGridHost(tile) != null) {
            LOGGER.debug("ItemGrid cannot connect to tile at {} (direction: {}): tile is already part of a grid",
                    tile.getBlockPos(), dir);
            return false;
        }
        
        boolean canConnect = false;
        if (dir != null) {
            canConnect = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir).isPresent();
        }
        
        LOGGER.debug("ItemGrid checking connection to tile at {} (direction: {}): tile={}, hasItemCapability={}",
                tile.getBlockPos(), dir, tile.getClass().getSimpleName(), canConnect);
        
        return canConnect;
    }

    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap) {
        return LazyOptional.empty();
    }

    @Override
    public void onMerge(ItemGrid from) {
        // Transfer items in transit
        itemsInTransit.addAll(from.itemsInTransit);
        returningItems.addAll(from.returningItems);
        
        // Merge transit tracking
        for (Map.Entry<BlockPos, Map<Direction, Integer>> entry : from.itemsInTransitToDestination.entrySet()) {
            Map<Direction, Integer> ourMap = itemsInTransitToDestination.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>());
            for (Map.Entry<Direction, Integer> dirEntry : entry.getValue().entrySet()) {
                ourMap.merge(dirEntry.getKey(), dirEntry.getValue(), Integer::sum);
            }
        }
    }

    @Override
    public void onSplit(List<ItemGrid> others) {
        // Items in transit will be returned to their original servos for safety
        for (ItemInTransit item : itemsInTransit) {
            handleReturnedItem(item);
        }
        for (ItemInTransit item : returningItems) {
            handleReturnedItem(item);
        }
        
        // Clear transit tracking
        itemsInTransitToDestination.clear();
        
        // Cleanup grid tracking
        cleanup();
    }

    @Override
    public void refreshCapabilities() {
        // No capabilities to refresh
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        
        // Serialize items in transit
        ListTag transitList = new ListTag();
        for (ItemInTransit item : itemsInTransit) {
            transitList.add(item.serializeNBT());
        }
        tag.put("itemsInTransit", transitList);
        
        ListTag returningList = new ListTag();
        for (ItemInTransit item : returningItems) {
            returningList.add(item.serializeNBT());
        }
        tag.put("returningItems", returningList);
        
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        super.deserializeNBT(nbt);
        
        // Deserialize items in transit
        itemsInTransit.clear();
        itemsInTransitToDestination.clear();
        
        ListTag transitList = nbt.getList("itemsInTransit", 10);
        for (int i = 0; i < transitList.size(); i++) {
            ItemInTransit item = new ItemInTransit();
            item.deserializeNBT(transitList.getCompound(i));
            itemsInTransit.add(item);
            // Rebuild transit tracking
            addItemToTransitTracking(item);
        }
        
        returningItems.clear();
        ListTag returningList = nbt.getList("returningItems", 10);
        for (int i = 0; i < returningList.size(); i++) {
            ItemInTransit item = new ItemInTransit();
            item.deserializeNBT(returningList.getCompound(i));
            returningItems.add(item);
        }
    }
    
    // Note: Per-tick rerouting methods removed (checkAndRerouteItem, calculateCurrentPosition,
    // findNearestNode, calculatePathDistance) - path validation now handled by handleTopologyChange()

    /**
     * Represents an item traveling through the duct network
     */
    public static class ItemInTransit {
        public ItemStack stack;
        public BlockPos origin;
        public Direction originSide;
        public BlockPos destination;
        public Direction destinationSide;
        public List<BlockPos> path;
        public int currentPathIndex;
        public float distanceTraveled;
        public boolean returning;

        public ItemInTransit() {
            // For deserialization
        }

        public ItemInTransit(ItemStack stack, BlockPos origin, Direction originSide, BlockPos destination, Direction destinationSide, List<BlockPos> path) {
            this.stack = stack;
            this.origin = origin;
            this.originSide = originSide;
            this.destination = destination;
            this.destinationSide = destinationSide;
            this.path = new ArrayList<>(path);
            this.currentPathIndex = 0;
            this.distanceTraveled = 0;
            this.returning = false;
        }
        
        /**
         * Calculate the total physical distance the item needs to travel
         */
        public double getTotalDistance() {
            if (path.size() <= 1) {
                // Direct connection - distance from origin to destination
                return Math.sqrt(origin.distSqr(destination));
            }
            
            double totalDistance = 0;
            BlockPos currentPos = origin;
            
            // Add distance from origin to first path node
            if (!path.isEmpty()) {
                totalDistance += Math.sqrt(currentPos.distSqr(path.get(0)));
                currentPos = path.get(0);
            }
            
            // Add distance between path nodes
            for (int i = 1; i < path.size(); i++) {
                totalDistance += Math.sqrt(currentPos.distSqr(path.get(i)));
                currentPos = path.get(i);
            }
            
            // Add distance from last path node to destination
            totalDistance += Math.sqrt(currentPos.distSqr(destination));
            
            return totalDistance;
        }

        public void reverse() {
            // Swap origin and destination
            BlockPos tempPos = origin;
            Direction tempSide = originSide;
            origin = destination;
            originSide = destinationSide;
            destination = tempPos;
            destinationSide = tempSide;
            
            // Reverse path
            Collections.reverse(path);
            currentPathIndex = 0;
            distanceTraveled = 0;
            returning = true;
        }

        public BlockPos getCurrentPosition() {
            if (currentPathIndex >= path.size() - 1) {
                return path.get(path.size() - 1);
            }
            return path.get(currentPathIndex);
        }

        public BlockPos getNextPosition() {
            if (currentPathIndex + 1 >= path.size()) {
                return path.get(path.size() - 1);
            }
            return path.get(currentPathIndex + 1);
        }

        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.put("stack", stack.serializeNBT());
            tag.putLong("origin", origin.asLong());
            tag.putInt("originSide", originSide.ordinal());
            tag.putLong("destination", destination.asLong());
            tag.putInt("destSide", destinationSide.ordinal());
            tag.putInt("pathIndex", currentPathIndex);
            tag.putFloat("distance", distanceTraveled);
            tag.putBoolean("returning", returning);
            
            ListTag pathList = new ListTag();
            for (BlockPos pos : path) {
                CompoundTag posTag = new CompoundTag();
                posTag.putLong("pos", pos.asLong());
                pathList.add(posTag);
            }
            tag.put("path", pathList);
            
            return tag;
        }

        public void deserializeNBT(CompoundTag tag) {
            stack = ItemStack.of(tag.getCompound("stack"));
            origin = BlockPos.of(tag.getLong("origin"));
            originSide = Direction.values()[tag.getInt("originSide")];
            destination = BlockPos.of(tag.getLong("destination"));
            destinationSide = Direction.values()[tag.getInt("destSide")];
            currentPathIndex = tag.getInt("pathIndex");
            distanceTraveled = tag.getFloat("distance");
            returning = tag.getBoolean("returning");
            
            path = new ArrayList<>();
            ListTag pathList = tag.getList("path", 10);
            for (int i = 0; i < pathList.size(); i++) {
                path.add(BlockPos.of(pathList.getCompound(i).getLong("pos")));
            }
        }
    }
}