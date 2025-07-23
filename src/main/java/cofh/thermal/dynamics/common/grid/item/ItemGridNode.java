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
        System.out.println("=== ItemGridNode.cacheConnections() called ===");
        System.out.println("Node pos: " + pos);
        connections.clear();
        for (Direction dir : Direction.values()) {
            BlockPos targetPos = pos.relative(dir);
            boolean canConnect = grid.canConnectOnSide(targetPos, dir.getOpposite());
            System.out.println("  " + dir + " -> " + targetPos + ": canConnect=" + canConnect);
            if (canConnect) {
                connections.add(dir);
            }
        }
        System.out.println("Final connections: " + connections);
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
        
        // Cache connections if needed
        if (!connectionsCached) {
            System.out.println("ItemGridNode distributionTick: connections not cached, calling cacheConnections");
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
        
        // Use BFS to find all reachable destinations
        Set<BlockPos> visited = new HashSet<>();
        Queue<PathNode> queue = new LinkedList<>();
        queue.add(new PathNode(pos, new ArrayList<>()));
        visited.add(pos);
        
        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            
            // Check if current position has item handlers
            BlockEntity tile = grid.getLevel().getBlockEntity(current.pos);
            if (tile != null) {
                for (Direction dir : Direction.values()) {
                    if (tile.getCapability(ForgeCapabilities.ITEM_HANDLER, dir).isPresent()) {
                        // Found a destination
                        if (!current.pos.equals(pos)) {
                            destinationCache.put(current.pos, new PathInfo(current.path, dir));
                        }
                    }
                }
            }
            
            // Explore neighbors
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
        // For now, return first available destination
        // TODO: Implement smart routing based on filters, priorities, etc.
        for (Map.Entry<BlockPos, PathInfo> entry : destinationCache.entrySet()) {
            BlockEntity tile = grid.getLevel().getBlockEntity(entry.getKey());
            if (tile != null) {
                LazyOptional<IItemHandler> cap = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, entry.getValue().side);
                if (cap.isPresent()) {
                    IItemHandler handler = cap.orElse(null);
                    if (handler != null && canInsertItem(handler, stack)) {
                        return entry.getValue();
                    }
                }
            }
        }
        
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