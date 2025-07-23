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
    
    // Speed in blocks per tick (0.5 = 1 block every 2 ticks)
    private static final float ITEM_SPEED = 0.5f;
    
    // Queue for items in transit - using concurrent queue for thread safety
    private final Queue<ItemInTransit> itemsInTransit = new ConcurrentLinkedQueue<>();
    
    // Items that need to be returned due to backup
    private final Queue<ItemInTransit> returningItems = new ConcurrentLinkedQueue<>();
    
    // Track items in transit to destinations for capacity calculation
    private final Map<BlockPos, Map<Direction, Integer>> itemsInTransitToDestination = new ConcurrentHashMap<>();

    public ItemGrid(UUID id, Level world) {
        super(ITEM_GRID.get(), id, world);
    }
    
    @Override
    public ItemGridNode newNode() {
        return new ItemGridNode(this);
    }

    @Override
    public void tick() {
        super.tick();
        
        // Process items in transit
        processItemsInTransit();
        
        // Process returning items
        processReturningItems();
    }

    private void processItemsInTransit() {
        if (!itemsInTransit.isEmpty()) {
            System.out.println("ItemGrid: Processing " + itemsInTransit.size() + " items in transit");
        }
        
        Iterator<ItemInTransit> iterator = itemsInTransit.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();
            
            System.out.println("ItemGrid: Processing " + item.stack.getCount() + "x " + item.stack.getItem() + 
                              " - distance: " + item.distanceTraveled + ", pathIndex: " + item.currentPathIndex + "/" + item.path.size());
            
            // Update item position
            item.distanceTraveled += ITEM_SPEED;
            
            // Check if item reached next node
            if (item.distanceTraveled >= 1.0f) {
                item.distanceTraveled -= 1.0f;
                item.currentPathIndex++;
                
                System.out.println("ItemGrid: Item advanced to pathIndex " + item.currentPathIndex);
                
                // Check if reached destination
                if (item.currentPathIndex >= item.path.size() - 1) {
                    System.out.println("ItemGrid: Item reached destination, attempting delivery");
                    // Try to insert into destination
                    if (tryInsertItem(item)) {
                        System.out.println("ItemGrid: Item delivered successfully");
                        // Successfully inserted - remove from transit tracking
                        removeItemFromTransitTracking(item);
                        iterator.remove();
                    } else {
                        System.out.println("ItemGrid: Item delivery failed, returning to sender");
                        // Backup occurred - reverse path and add to returning queue
                        item.reverse();
                        removeItemFromTransitTracking(item);
                        returningItems.add(item);
                        iterator.remove();
                    }
                }
            }
        }
    }

    private void processReturningItems() {
        Iterator<ItemInTransit> iterator = returningItems.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();
            
            // Update item position
            item.distanceTraveled += ITEM_SPEED;
            
            // Check if item reached next node
            if (item.distanceTraveled >= 1.0f) {
                item.distanceTraveled -= 1.0f;
                item.currentPathIndex++;
                
                // Check if reached origin
                if (item.currentPathIndex >= item.path.size() - 1) {
                    // Store in original servo's overflow (infinite storage)
                    handleReturnedItem(item);
                    iterator.remove();
                }
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
        System.out.println("ItemGrid: insertItem called - " + stack.getCount() + "x " + stack.getItem() + " from " + origin + " to " + destination);
        
        if (stack.isEmpty() || path.isEmpty()) {
            System.out.println("ItemGrid: Rejecting item - stack empty: " + stack.isEmpty() + ", path empty: " + path.isEmpty());
            return;
        }
        
        System.out.println("ItemGrid: Path length: " + path.size() + ", current items in transit: " + itemsInTransit.size());
        
        ItemInTransit item = new ItemInTransit(stack.copy(), origin, originSide, destination, destinationSide, path);
        itemsInTransit.add(item);
        
        System.out.println("ItemGrid: Added item to transit queue, new size: " + itemsInTransit.size());
        
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