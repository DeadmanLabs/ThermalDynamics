package cofh.thermal.dynamics.common.attachment;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.inventory.attachment.ItemTurboServoAttachmentMenu;
import net.minecraft.core.Direction;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_TURBO_SERVO_ATTACHMENT;

public class ItemTurboServoAttachment extends ItemServoAttachment {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.item_turbo_servo");

    public static final int DEFAULT_TRANSFER = 1; // Default items per operation
    public static final int MIN_TRANSFER = 1; // Minimum items per operation
    public static final int MAX_TRANSFER = 64; // Maximum items per operation for turbo servo

    // Turbo extraction interval for normal ducts (71 ticks ≈ 3.5 seconds = 17 extractions/min)
    // Impulse ducts use 1/4 of this (17 ticks ≈ 0.85 seconds = 72 extractions/min)
    protected static final int TURBO_BASE_EXTRACTION_INTERVAL = 71;

    public ItemTurboServoAttachment(IDuct<?, ?> duct, Direction side) {
        super(duct, side);
        this.amountTransfer = DEFAULT_TRANSFER;
    }

    @Override
    public int getMaxTransfer() {
        return MAX_TRANSFER; // Override parent's MAX_TRANSFER (8) with turbo's limit (64)
    }

    /**
     * Get the extraction interval for turbo servo based on the attached duct type.
     * Turbo servo is ~2.8x faster than basic servo.
     * Impulse ducts provide additional 4x faster extraction rate.
     * @return Ticks between extractions (71 for normal, 17 for impulse)
     */
    @Override
    protected int getExtractionInterval() {
        // Check if attached to impulse duct
        if (duct instanceof ItemDuctBlockEntity itemDuct && itemDuct.isImpulse()) {
            return TURBO_BASE_EXTRACTION_INTERVAL / 4;  // ~17 ticks for turbo+impulse (72/min)
        }
        return TURBO_BASE_EXTRACTION_INTERVAL;  // 71 ticks for turbo+normal (17/min)
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(ITEMS.get(ID_TURBO_SERVO_ATTACHMENT));
    }

    @Override
    public Component getDisplayName() {
        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {
        return new ItemTurboServoAttachmentMenu(i, player.level, pos(), side, inventory, player);
    }

    @Override
    public void tick() {
        if (!rsControl.getState()) {
            return;
        }

        // Try to reinject overflow items first (priority over new extractions)
        if (isOverflowing()) {
            tryReinjectOverflow();
            return; // Block new extractions until overflow is cleared
        }

        // Decrement extraction cooldown
        if (extractionCooldown > 0) {
            extractionCooldown--;
            return;
        }

        // Extract items from connected inventory and route through grid
        extractAndRouteItems();

        // Set cooldown for next extraction (varies by duct type)
        extractionCooldown = getExtractionInterval();
    }

    /**
     * Try to reinject overflow items back into the grid when a valid path exists.
     */
    private void tryReinjectOverflow() {
        try {
            // Get grid for routing
            if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) {
                return;
            }

            // Get our grid node
            cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = itemGrid.getNodes().get(pos());
            if (ourNode == null) {
                return;
            }

            // Try to reinject items from overflow storage
            for (int slot = 0; slot < overflowStorage.getSlots(); slot++) {
                net.minecraft.world.item.ItemStack stack = overflowStorage.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                // Find a valid destination for this item
                cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult destination = ourNode.findBestDestination(stack);
                if (destination == null) {
                    continue; // No valid path yet - keep in overflow
                }

                net.minecraft.core.BlockPos destPos = destination.destination;
                cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = destination.pathInfo;

                // Check if destination has capacity
                int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, stack);
                if (availableCapacity <= 0) {
                    continue;
                }

                // Extract only what can fit
                int toExtract = Math.min(stack.getCount(), availableCapacity);
                net.minecraft.world.item.ItemStack extracted = overflowStorage.extractItem(slot, toExtract, false);

                if (!extracted.isEmpty()) {
                    // Route item through grid
                    itemGrid.insertItem(extracted, pos(), side(), destPos, pathInfo.side, pathInfo.path);

                    // Clear backflow flag if we successfully reinjected
                    if (!isOverflowing()) {
                        clearBackflow();
                    }

                    // Only process one item per tick to avoid flooding the network
                    return;
                }
            }
        } catch (Exception e) {
            // Silent failure for performance
        }
    }
    
    private void extractAndRouteItems() {
        try {
            // Get connected external inventory
            LazyOptional<net.minecraftforge.items.IItemHandler> extCap = getExternalCapability();
            if (!extCap.isPresent()) {
                return;
            }
            
            net.minecraftforge.items.IItemHandler externalHandler = extCap.orElse(null);
            if (externalHandler == null) {
                return;
            }
            
            // Get grid for routing
            if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) {
                return;
            }
            
            // Get our grid node
            cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = itemGrid.getNodes().get(pos());
            if (ourNode == null) {
                return;
            }
            
            int remainingToExtract = amountTransfer;
            
            // Try to extract items from external inventory
            for (int slot = 0; slot < externalHandler.getSlots() && remainingToExtract > 0; slot++) {
                try {
                    net.minecraft.world.item.ItemStack slotStack = externalHandler.getStackInSlot(slot);
                    if (slotStack.isEmpty()) {
                        continue;
                    }
                    
                    // Test extraction first
                    net.minecraft.world.item.ItemStack extractable = externalHandler.extractItem(slot, Math.min(remainingToExtract, 64), true);
                    if (extractable.isEmpty()) {
                        continue;
                    }
                    
                    if (!filter.valid(extractable)) {
                        continue;
                    }
                    
                    // Find best destination for this item
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult destination = ourNode.findBestDestination(extractable);
                    if (destination == null) {
                        continue;
                    }
                    
                    net.minecraft.core.BlockPos destPos = destination.destination;
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = destination.pathInfo;
                    
                    // Check if destination has capacity (including items in transit)
                    int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, extractable);
                    if (availableCapacity <= 0) {
                        continue;
                    }
                    
                    // Extract only what can fit and what we still need to extract
                    int toExtract = Math.min(extractable.getCount(), Math.min(remainingToExtract, availableCapacity));
                    
                    net.minecraft.world.item.ItemStack extracted = externalHandler.extractItem(slot, toExtract, false);
                    if (extracted.isEmpty()) {
                        continue;
                    }
                    
                    // Route item through grid
                    itemGrid.insertItem(extracted, pos(), side(), destPos, pathInfo.side, pathInfo.path);
                    remainingToExtract -= extracted.getCount();
                    
                } catch (Exception e) {
                    // Silent failure for performance
                }
            }
            
        } catch (Exception e) {
            // Silent failure for performance
        }
    }
    
    private LazyOptional<net.minecraftforge.items.IItemHandler> getExternalCapability() {
        // Always get the raw capability directly from the tile, don't use cached wrapped version
        net.minecraft.world.level.block.entity.BlockEntity tile = world().getBlockEntity(pos().relative(side));
        if (tile == null) {
            return LazyOptional.empty();
        }
        
        return tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side.getOpposite());
    }
}