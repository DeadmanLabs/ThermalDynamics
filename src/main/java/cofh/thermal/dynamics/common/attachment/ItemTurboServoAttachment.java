package cofh.thermal.dynamics.common.attachment;

import cofh.thermal.dynamics.api.grid.IDuct;
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

    public ItemTurboServoAttachment(IDuct<?, ?> duct, Direction side) {
        super(duct, side);
        this.amountTransfer = DEFAULT_TRANSFER;
    }

    @Override
    public int getMaxTransfer() {
        return MAX_TRANSFER; // Override parent's MAX_TRANSFER (8) with turbo's limit (64)
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
        
        // Extract items from connected inventory and route through grid (turbo version - no cooldown)
        extractAndRouteItems();
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