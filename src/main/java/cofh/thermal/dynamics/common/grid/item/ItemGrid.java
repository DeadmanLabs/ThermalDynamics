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

    // Epsilon for float comparison to handle precision issues after deserialization
    private static final float DISTANCE_EPSILON = 0.001f;

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
        LOGGER.info("handleTopologyChange called - checking {} items in transit", itemsInTransit.size());

        Iterator<ItemInTransit> iterator = itemsInTransit.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();

            // Check if the item's destination is still valid
            boolean pathValid = isPathValid(item);
            LOGGER.info("  Item {} path valid: {} (origin={}, dest={}, path={})",
                item.stack, pathValid, item.origin, item.destination, item.path);

            if (!pathValid) {
                // Path is broken - initiate backflow to origin
                LOGGER.info("  Path invalid - initiating backflow for {}", item.stack);

                // Before reversing, check if the origin (servo) is still in the grid
                // If the servo duct was broken, we need to drop the item instead
                if (!getNodes().containsKey(item.origin)) {
                    LOGGER.info("  Origin {} no longer in grid - dropping as entity", item.origin);
                    dropItemAsEntity(item);
                    removeItemFromTransitTracking(item);
                    iterator.remove();
                    continue;
                }

                // reverse() now preserves position - don't reset distanceTraveled after
                item.reverse();

                // After reverse: item.destination is now the servo duct position
                // Check if the servo duct still exists in the grid
                if (!getNodes().containsKey(item.destination)) {
                    LOGGER.info("  Destination (servo) {} no longer in grid after reverse - dropping as entity", item.destination);
                    dropItemAsEntity(item);
                    removeItemFromTransitTracking(item);
                    iterator.remove();
                    continue;
                }

                // CRITICAL: Filter the path to only include positions that still exist as nodes
                // After reverse, the path may contain the broken duct's position
                // Also remove duplicates and ensure path is valid
                List<BlockPos> validPath = new ArrayList<>();
                BlockPos lastPos = null;
                for (BlockPos pathPos : item.path) {
                    // Only add if it's a valid node AND not a duplicate of the previous position
                    if (getNodes().containsKey(pathPos) && !pathPos.equals(lastPos)) {
                        validPath.add(pathPos);
                        lastPos = pathPos;
                    }
                }
                item.path = validPath;
                LOGGER.info("  Filtered path to {} valid positions: {}", validPath.size(), validPath);

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
     * This checks that the destination exists and all path nodes exist.
     * We do NOT check edges because path nodes may not be directly connected
     * in the graph (they might be connected through edge ducts).
     */
    private boolean isPathValid(ItemInTransit item) {
        // Check if destination still exists and has item handler
        BlockEntity destTile = world.getBlockEntity(item.destination);
        if (destTile == null) {
            LOGGER.info("Path invalid: destination {} has no tile entity", item.destination);
            return false;
        }

        // Check if the destination can still accept items
        if (!destTile.getCapability(ForgeCapabilities.ITEM_HANDLER, item.destinationSide).isPresent()) {
            LOGGER.info("Path invalid: destination {} has no item handler on side {}", item.destination, item.destinationSide);
            return false;
        }

        // Check origin node exists
        if (!getNodes().containsKey(item.origin)) {
            LOGGER.info("Path invalid: origin {} not in grid nodes", item.origin);
            return false;
        }

        // Check if the path through ducts is still connected
        // Verify all path nodes still exist
        for (BlockPos pathPos : item.path) {
            if (!getNodes().containsKey(pathPos)) {
                LOGGER.info("Path invalid: path position {} not in grid nodes", pathPos);
                return false;
            }
        }

        // Path is valid if all nodes exist
        // Note: We don't check edges because consecutive path nodes may not have
        // direct graph edges (they might be connected through edge ducts that aren't nodes)
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

    /**
     * Check if the return path for a returning item is still valid.
     * For returning items:
     * - origin is the external block (chest) - NOT a grid node
     * - destination is the servo duct - IS a grid node
     * - path contains intermediate duct nodes (already filtered)
     *
     * Returns false only if the destination (servo) no longer exists.
     * The path has already been filtered during backflow initiation,
     * so we just need to verify the destination is still reachable.
     */
    private boolean isReturnPathValid(ItemInTransit item) {
        // Check if destination (the servo duct we're returning to) still exists in grid
        if (!getNodes().containsKey(item.destination)) {
            LOGGER.info("  Return path invalid: destination {} not in nodes", item.destination);
            return false;
        }

        // The path was already filtered when backflow was initiated
        // We trust that the item can reach the destination through whatever path remains
        // If a path node was removed while item is returning, we'll handle it dynamically

        return true;
    }

    /**
     * Drop an item as an entity at its current position when both paths are broken.
     */
    private void dropItemAsEntity(ItemInTransit item) {
        if (world == null || world.isClientSide) return;

        // Calculate current world position based on progress through path
        net.minecraft.world.phys.Vec3 pos = calculateItemWorldPosition(item);

        LOGGER.info("=== DROPPING ITEM AS ENTITY ===");
        LOGGER.info("  Item: {}", item.stack);
        LOGGER.info("  Drop position: ({}, {}, {})", pos.x, pos.y, pos.z);
        LOGGER.info("  Item origin: {}", item.origin);
        LOGGER.info("  Item destination: {}", item.destination);
        LOGGER.info("  Item path: {}", item.path);
        LOGGER.info("  Distance traveled: {} / total: {}", item.distanceTraveled, item.getTotalDistance());
        LOGGER.info("  Returning flag: {}", item.returning);

        net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
            world, pos.x, pos.y, pos.z, item.stack
        );
        // Set no velocity so item doesn't fly away
        entity.setDeltaMovement(0, 0, 0);
        // Prevent pickup delay so player can grab it immediately
        entity.setPickUpDelay(10);
        world.addFreshEntity(entity);

        LOGGER.info("  Entity spawned at world position: ({}, {}, {})", entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * Calculate the world position of an item based on its progress through the path.
     */
    private net.minecraft.world.phys.Vec3 calculateItemWorldPosition(ItemInTransit item) {
        double totalDistance = item.getTotalDistance();
        if (totalDistance <= 0) {
            // Fallback: use origin center
            return new net.minecraft.world.phys.Vec3(
                item.origin.getX() + 0.5,
                item.origin.getY() + 0.5,
                item.origin.getZ() + 0.5
            );
        }

        double progress = Math.min(item.distanceTraveled / totalDistance, 1.0);

        // Build ordered position list: origin -> path nodes -> destination
        List<BlockPos> positions = new ArrayList<>();
        positions.add(item.origin);
        positions.addAll(item.path);
        positions.add(item.destination);

        // Calculate cumulative distances
        List<Double> cumulativeDistances = new ArrayList<>();
        cumulativeDistances.add(0.0);
        double cumDist = 0;
        for (int i = 1; i < positions.size(); i++) {
            cumDist += Math.sqrt(positions.get(i - 1).distSqr(positions.get(i)));
            cumulativeDistances.add(cumDist);
        }

        // Find which segment we're in
        double targetDistance = progress * totalDistance;
        for (int i = 1; i < cumulativeDistances.size(); i++) {
            if (targetDistance <= cumulativeDistances.get(i)) {
                // We're in segment i-1 to i
                BlockPos from = positions.get(i - 1);
                BlockPos to = positions.get(i);
                double segmentStart = cumulativeDistances.get(i - 1);
                double segmentEnd = cumulativeDistances.get(i);
                double segmentLength = segmentEnd - segmentStart;

                double segmentProgress = segmentLength > 0 ? (targetDistance - segmentStart) / segmentLength : 0;

                return new net.minecraft.world.phys.Vec3(
                    from.getX() + 0.5 + (to.getX() - from.getX()) * segmentProgress,
                    from.getY() + 0.5 + (to.getY() - from.getY()) * segmentProgress,
                    from.getZ() + 0.5 + (to.getZ() - from.getZ()) * segmentProgress
                );
            }
        }

        // Fallback: at destination
        return new net.minecraft.world.phys.Vec3(
            item.destination.getX() + 0.5,
            item.destination.getY() + 0.5,
            item.destination.getZ() + 0.5
        );
    }

    private void updateRenderData() {
        // Skip client-side or if no items to render (check BOTH queues!)
        if (world.isClientSide || (itemsInTransit.isEmpty() && returningItems.isEmpty())) {
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

            // Check if item reached destination (use epsilon for float precision tolerance)
            if (item.distanceTraveled >= totalDistance - DISTANCE_EPSILON) {
                // Try to insert into destination
                if (tryInsertItem(item)) {
                    // Successfully inserted - remove from transit tracking
                    removeItemFromTransitTracking(item);
                    iterator.remove();
                } else {
                    // Backup occurred - reverse path and add to returning queue
                    // Note: reverse() preserves position, so item will be at destination end
                    // and travel back. Since we're at the destination, distanceTraveled should
                    // be close to totalDistance, so after reverse it will be near 0.
                    item.reverse();
                    removeItemFromTransitTracking(item);
                    returningItems.add(item);
                    iterator.remove();
                }
            }
        }
    }

    private void processReturningItems() {
        if (!returningItems.isEmpty()) {
            LOGGER.info("processReturningItems: {} items returning", returningItems.size());
        }

        Iterator<ItemInTransit> iterator = returningItems.iterator();
        while (iterator.hasNext()) {
            ItemInTransit item = iterator.next();

            // Check if return path is still valid (both paths broken case)
            boolean returnPathValid = isReturnPathValid(item);
            if (!returnPathValid) {
                // Both destination AND return path broken - drop item as entity
                LOGGER.info("Return path invalid for {} - dropping as entity", item.stack);
                dropItemAsEntity(item);
                iterator.remove();
                continue;
            }

            double totalDistance = item.getTotalDistance();

            // Update item position
            item.distanceTraveled += ITEM_SPEED;

            // Check if item reached origin (use epsilon for float precision tolerance)
            if (item.distanceTraveled >= totalDistance - DISTANCE_EPSILON) {
                // Store in original servo's overflow (infinite storage)
                LOGGER.info("Item {} reached origin - storing in servo overflow", item.stack);
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
        // For returning items (after reverse()):
        // - item.origin = external block (chest) where item came from
        // - item.destination = servo duct position where servo is attached
        // - item.destinationSide = the side of the duct where servo is attached
        BlockPos servoPos = item.destination;
        Direction servoSide = item.destinationSide;
        Level world = getLevel();

        LOGGER.info("handleReturnedItem: Looking for servo at {} side {} (item origin={}, dest={})",
            servoPos, servoSide, item.origin, item.destination);

        if (world != null && !world.isClientSide) {
            // Find the original servo that extracted this item
            ItemGridNode servoNode = getNodes().get(servoPos);
            if (servoNode != null && servoNode.getDuct() != null) {
                LOGGER.info("  Found node at {}, checking attachment on side {}", servoPos, servoSide);
                var attachment = servoNode.getDuct().getAttachment(servoSide);
                LOGGER.info("  Attachment: {}", attachment != null ? attachment.getClass().getSimpleName() : "null");

                if (attachment instanceof cofh.thermal.dynamics.common.attachment.ItemServoAttachment servo) {
                    // Store in original servo's overflow (infinite storage)
                    servo.storeOverflowItem(item.stack);
                    LOGGER.info("SUCCESS: Stored overflow item {} in servo at {} direction {}",
                        item.stack, servoPos, servoSide);
                    return;
                }
            } else {
                LOGGER.info("  Node not found at {} or duct is null", servoPos);
            }

            // If original servo not found, drop item at calculated position
            LOGGER.warn("Original servo not found for overflow item {} at {} direction {} - dropping as entity",
                item.stack, servoPos, servoSide);
            dropItemAsEntity(item);
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

        // IMPORTANT: Sync lastCheckedTopologyVersion with current topology to prevent
        // immediate path validation on first tick after insertion. This avoids false
        // positives where a newly inserted item incorrectly triggers backflow.
        lastCheckedTopologyVersion = getTopologyVersion();
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
     * Get all items that are returning to their origin servo (backflowing).
     */
    public Collection<ItemInTransit> getReturningItems() {
        return Collections.unmodifiableCollection(returningItems);
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
            return false;
        }

        if (dir != null) {
            return tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir).isPresent();
        }
        return false;
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
        LOGGER.info("onSplit called with {} new grids, {} items in transit, {} returning items",
            others.size(), itemsInTransit.size(), returningItems.size());

        // If no new grids (all ducts removed), drop all items
        if (others.isEmpty()) {
            LOGGER.info("No new grids - dropping all items");
            for (ItemInTransit item : itemsInTransit) {
                dropItemAsEntity(item);
            }
            for (ItemInTransit item : returningItems) {
                dropItemAsEntity(item);
            }
            itemsInTransit.clear();
            returningItems.clear();
            itemsInTransitToDestination.clear();
            cleanup();
            return;
        }

        // Build a map of node positions to their new grids
        Map<BlockPos, ItemGrid> nodeToGrid = new HashMap<>();
        for (ItemGrid newGrid : others) {
            Map<BlockPos, ItemGridNode> newGridNodes = newGrid.getNodes();
            LOGGER.info("New grid has {} nodes: {}", newGridNodes.size(), newGridNodes.keySet());
            for (BlockPos pos : newGridNodes.keySet()) {
                nodeToGrid.put(pos, newGrid);
            }
        }

        LOGGER.info("Built nodeToGrid with {} entries: {}", nodeToGrid.size(), nodeToGrid.keySet());

        // Process items in transit - transfer to grid containing their origin
        for (ItemInTransit item : itemsInTransit) {
            LOGGER.info("Processing forward item {} - origin={}, dest={}, path={}, distTraveled={}",
                item.stack, item.origin, item.destination, item.path, item.distanceTraveled);

            // First try to find grid containing the origin
            ItemGrid targetGrid = nodeToGrid.get(item.origin);
            LOGGER.info("  nodeToGrid.get(origin={}) = {}", item.origin, targetGrid != null ? "found" : "null");

            // If origin not found, check path positions (the path contains node positions)
            if (targetGrid == null && item.path != null) {
                for (BlockPos pathPos : item.path) {
                    targetGrid = nodeToGrid.get(pathPos);
                    LOGGER.info("  nodeToGrid.get(pathPos={}) = {}", pathPos, targetGrid != null ? "found" : "null");
                    if (targetGrid != null) {
                        LOGGER.info("Found grid via path position {} for item {}", pathPos, item.stack);
                        break;
                    }
                }
            }

            if (targetGrid != null) {
                // Check if we can find the origin in this grid for proper backflow
                boolean originInTargetGrid = targetGrid.getNodes().containsKey(item.origin);

                if (originInTargetGrid) {
                    // Origin is in the target grid - reverse and send back
                    // reverse() preserves position - item continues from where it was
                    item.reverse();

                    // Filter the path to only include positions that exist in the target grid
                    // After reverse, path is reversed too, so we need valid positions
                    List<BlockPos> validPath = new ArrayList<>();
                    for (BlockPos pathPos : item.path) {
                        if (targetGrid.getNodes().containsKey(pathPos)) {
                            validPath.add(pathPos);
                        }
                    }
                    item.path = validPath;
                    LOGGER.info("Filtered path to {} positions for returning item", validPath.size());

                    targetGrid.returningItems.add(item);

                    // Notify the servo about the backflow
                    ItemGridNode originNode = targetGrid.getNodes().get(item.destination);
                    if (originNode != null && originNode.getDuct() != null) {
                        if (originNode.getDuct().getAttachment(item.destinationSide) instanceof cofh.thermal.dynamics.common.attachment.ItemServoAttachment servo) {
                            servo.notifyBackflow();
                        }
                    }

                    LOGGER.info("Item {} transferred to origin grid during split, returning to origin at {}",
                        item.stack, item.destination);
                } else {
                    // Origin is NOT in the found grid - the item is on the wrong side of the split
                    // Drop the item at its current position since it can't reach home
                    dropItemAsEntity(item);
                    LOGGER.info("Item {} dropped - origin {} not reachable from path grid",
                        item.stack, item.origin);
                }
            } else {
                // No grid found at all - drop as entity at current position
                dropItemAsEntity(item);
                LOGGER.debug("Item {} dropped as entity during split - origin {} not in any grid (path={})",
                    item.stack, item.origin, item.path);
            }
        }

        // Process returning items - transfer to grid containing their destination (original origin)
        for (ItemInTransit item : returningItems) {
            ItemGrid destGrid = nodeToGrid.get(item.destination);

            // If destination not found, check path positions
            if (destGrid == null && item.path != null) {
                for (BlockPos pathPos : item.path) {
                    destGrid = nodeToGrid.get(pathPos);
                    if (destGrid != null) {
                        break;
                    }
                }
            }

            if (destGrid != null && destGrid.getNodes().containsKey(item.destination)) {
                // Transfer item to the grid containing its destination
                destGrid.returningItems.add(item);
                LOGGER.debug("Returning item {} transferred to destination grid during split", item.stack);
            } else {
                // Destination is completely disconnected - drop as entity
                dropItemAsEntity(item);
                LOGGER.debug("Returning item {} dropped as entity during split - destination {} not reachable",
                    item.stack, item.destination);
            }
        }

        // Clear our queues (items have been transferred to new grids)
        itemsInTransit.clear();
        returningItems.clear();
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

        // After deserialization, sync lastCheckedTopologyVersion with the current topology
        // to prevent immediate path validation (which would reverse items incorrectly
        // since destinations may not be loaded yet)
        lastCheckedTopologyVersion = getTopologyVersion();
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
            // Calculate the remaining distance before swapping - this is how far the item
            // still needs to travel. After reversing, this becomes the distance traveled.
            double totalDistance = getTotalDistance();
            double remainingDistance = Math.max(0, totalDistance - distanceTraveled);

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

            // CRITICAL: The distance traveled after reversal is the remaining distance
            // that the item WOULD have traveled. This preserves the item's visual position
            // so it continues from where it was, not from the end of the path.
            distanceTraveled = (float) remainingDistance;
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