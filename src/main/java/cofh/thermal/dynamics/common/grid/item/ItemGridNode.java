package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.util.*;

/**
 * Grid node for item transport networks.
 * Handles pathfinding and item routing.
 */
public class ItemGridNode extends GridNode<ItemGrid> implements ITickableGridNode {
    
    public ItemGridNode(ItemGrid grid) {
        super(grid);
    }

    // Cache of connected item handlers
    private final Map<Direction, LazyOptional<IItemHandler>> connectedHandlers = new EnumMap<>(Direction.class);
    
    // Cache of valid destinations in the network
    private Map<BlockPos, PathInfo> destinationCache = new HashMap<>();
    private long lastCacheUpdate = 0;
    private static final long CACHE_DURATION = 100; // Update cache every 5 seconds (100 ticks)
    
    private boolean connectionsCached = false;

    public void clearItemConnections() {
        connectedHandlers.clear();
        destinationCache.clear();
        connectionsCached = false;
        connections.clear();
    }
    
    private void cacheConnections() {
        connections.clear();
        for (Direction dir : Direction.values()) {
            if (grid.canConnectOnSide(pos.relative(dir), dir.getOpposite())) {
                connections.add(dir);
            }
        }
        connectionsCached = true;
    }

    public IDuct<?, ?> getDuct() {
        return gridHost();
    }
    
    @Override
    public void attachmentTick() {
        IDuct<?, ?> duct = getDuct();
        if (duct == null) {
            return;
        }
        for (Direction dir : Direction.values()) {
            duct.getAttachment(dir).tick();
        }
    }
    
    @Override
    public void distributionTick() {
        if (!isLoaded()) return;
        
        // Tick attachments first
        attachmentTick();
        
        // Cache connections if needed
        if (!connectionsCached) {
            cacheConnections();
        }
        
        // Update destination cache periodically
        long currentTick = grid.getLevel().getGameTime();
        if (currentTick - lastCacheUpdate > CACHE_DURATION) {
            updateDestinationCache();
            lastCacheUpdate = currentTick;
        }
        
        // Check for connected item handlers that might need servicing
        for (Direction dir : Direction.values()) {
            updateConnectedHandler(dir);
        }
    }

    private void updateConnectedHandler(Direction dir) {
        BlockEntity tile = grid.getLevel().getBlockEntity(pos.relative(dir));
        if (tile != null) {
            LazyOptional<IItemHandler> handler = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite());
            connectedHandlers.put(dir, handler);
        } else {
            connectedHandlers.remove(dir);
        }
    }

    private void updateDestinationCache() {
        destinationCache.clear();
        
        System.out.println("ItemGridNode: Updating destination cache for node at " + pos);
        
        // Use BFS to find all reachable destinations
        Set<BlockPos> visited = new HashSet<>();
        Queue<PathNode> queue = new LinkedList<>();
        queue.add(new PathNode(pos, new ArrayList<>()));
        visited.add(pos);
        
        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            
            // Check for external connections from current position
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = current.pos.relative(dir);
                BlockEntity tile = grid.getLevel().getBlockEntity(neighborPos);
                
                // Skip if this is another duct (internal connection)
                if (grid.getNodes().containsKey(neighborPos)) {
                    continue;
                }
                
                // Check if this tile has item handler capability
                if (tile != null && tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite()).isPresent()) {
                    // Found an external destination
                    List<BlockPos> pathToDestination = new ArrayList<>(current.path);
                    pathToDestination.add(neighborPos);
                    destinationCache.put(neighborPos, new PathInfo(pathToDestination, dir.getOpposite()));
                    System.out.println("ItemGridNode: Found destination " + neighborPos + " (" + tile.getClass().getSimpleName() + ") via " + current.pos + " on side " + dir.getOpposite());
                }
            }
            
            // Explore grid neighbors
            ItemGridNode currentNode = grid.getNodes().get(current.pos);
            if (currentNode != null) {
                for (ItemGridNode neighbor : grid.nodeGraph.adjacentNodes(currentNode)) {
                    if (!visited.contains(neighbor.pos)) {
                        visited.add(neighbor.pos);
                        List<BlockPos> newPath = new ArrayList<>(current.path);
                        newPath.add(neighbor.pos);
                        queue.add(new PathNode(neighbor.pos, newPath));
                    }
                }
            }
        }
        
        System.out.println("ItemGridNode: Destination cache updated, found " + destinationCache.size() + " destinations");
    }

    public boolean canExtractItem(Direction from) {
        LazyOptional<IItemHandler> handler = connectedHandlers.get(from);
        if (!handler.isPresent()) return false;
        
        IItemHandler itemHandler = handler.orElse(null);
        if (itemHandler == null) return false;
        
        // Check if any slot has items
        for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
            if (!itemHandler.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        
        return false;
    }

    public ItemStack extractItem(Direction from, int amount, boolean simulate) {
        LazyOptional<IItemHandler> handler = connectedHandlers.get(from);
        if (!handler.isPresent()) return ItemStack.EMPTY;
        
        IItemHandler itemHandler = handler.orElse(null);
        if (itemHandler == null) return ItemStack.EMPTY;
        
        // Try to extract from any slot
        for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
            ItemStack extracted = itemHandler.extractItem(slot, amount, simulate);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        
        return ItemStack.EMPTY;
    }

    public PathInfo findBestDestination(ItemStack stack) {
        System.out.println("ItemGridNode: findBestDestination for " + stack.getItem() + ", cache size: " + destinationCache.size());
        
        // For now, return first available destination
        // TODO: Implement smart routing based on filters, priorities, etc.
        for (Map.Entry<BlockPos, PathInfo> entry : destinationCache.entrySet()) {
            BlockPos destPos = entry.getKey();
            PathInfo pathInfo = entry.getValue();
            System.out.println("ItemGridNode: Checking destination " + destPos + " on side " + pathInfo.side);
            
            BlockEntity tile = grid.getLevel().getBlockEntity(destPos);
            if (tile == null) {
                System.out.println("ItemGridNode: No tile entity at " + destPos);
                continue;
            }
            
            System.out.println("ItemGridNode: Found tile: " + tile.getClass().getSimpleName());
            
            LazyOptional<IItemHandler> cap = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, pathInfo.side);
            if (!cap.isPresent()) {
                System.out.println("ItemGridNode: Tile has no item handler capability on side " + pathInfo.side);
                continue;
            }
            
            IItemHandler handler = cap.orElse(null);
            if (handler == null) {
                System.out.println("ItemGridNode: Item handler is null");
                continue;
            }
            
            System.out.println("ItemGridNode: Found item handler with " + handler.getSlots() + " slots");
            
            if (canInsertItem(handler, stack)) {
                System.out.println("ItemGridNode: Found valid destination at " + destPos);
                return pathInfo;
            } else {
                System.out.println("ItemGridNode: Cannot insert item into destination");
            }
        }
        
        System.out.println("ItemGridNode: No valid destination found");
        return null;
    }

    private boolean canInsertItem(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            remaining = handler.insertItem(slot, remaining, true);
            if (remaining.isEmpty()) {
                return true;
            }
        }
        return remaining.getCount() < stack.getCount();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = super.serializeNBT();
        // No additional data to serialize
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        super.deserializeNBT(nbt);
        // No additional data to deserialize
    }

    private static class PathNode {
        final BlockPos pos;
        final List<BlockPos> path;

        PathNode(BlockPos pos, List<BlockPos> path) {
            this.pos = pos;
            this.path = path;
        }
    }

    public static class PathInfo {
        public final List<BlockPos> path;
        public final Direction side;

        public PathInfo(List<BlockPos> path, Direction side) {
            this.path = path;
            this.side = side;
        }
    }
}