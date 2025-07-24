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

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.turbo_servo");

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
            System.out.println("ItemTurboServo: Redstone control disabled at " + pos() + " side " + side());
            return;
        }
        
        System.out.println("ItemTurboServo: Attempting extraction at " + pos() + " side " + side());
        
        // Extract items from connected inventory and route through grid (turbo version - no cooldown)
        extractAndRouteItems();
    }
    
    private void extractAndRouteItems() {
        try {
            System.out.println("ItemTurboServo: extractAndRouteItems called at " + pos() + " side " + side());
            
            // Get connected external inventory
            LazyOptional<net.minecraftforge.items.IItemHandler> extCap = getExternalCapability();
            if (!extCap.isPresent()) {
                System.out.println("ItemTurboServo: No external capability at " + pos() + " side " + side());
                return;
            }
            
            net.minecraftforge.items.IItemHandler externalHandler = extCap.orElse(null);
            if (externalHandler == null) {
                System.out.println("ItemTurboServo: External handler is null at " + pos() + " side " + side());
                return;
            }
            
            // Get grid for routing
            if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) {
                System.out.println("ItemTurboServo: Grid is not ItemGrid at " + pos() + " side " + side() + " - got " + (duct.getGrid() != null ? duct.getGrid().getClass().getSimpleName() : "null"));
                return;
            }
            
            // Get our grid node
            cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = itemGrid.getNodes().get(pos());
            if (ourNode == null) {
                System.out.println("ItemTurboServo: No grid node found at " + pos());
                return;
            }
            
            System.out.println("ItemTurboServo: Got ItemGrid and external handler, proceeding with extraction");
            System.out.println("ItemTurboServo: External handler has " + externalHandler.getSlots() + " slots");
            System.out.println("ItemTurboServo: Configured to extract " + amountTransfer + " items per operation");
            
            int remainingToExtract = amountTransfer;
            
            // Try to extract items from external inventory
            for (int slot = 0; slot < externalHandler.getSlots() && remainingToExtract > 0; slot++) {
                try {
                    net.minecraft.world.item.ItemStack slotStack = externalHandler.getStackInSlot(slot);
                    if (slotStack.isEmpty()) {
                        continue;
                    }
                    
                    System.out.println("ItemTurboServo: Checking slot " + slot + " with " + slotStack.getCount() + "x " + slotStack.getItem());
                    
                    // Test extraction first
                    net.minecraft.world.item.ItemStack extractable = externalHandler.extractItem(slot, Math.min(remainingToExtract, 64), true);
                    if (extractable.isEmpty()) {
                        System.out.println("ItemTurboServo: Cannot extract from slot " + slot);
                        continue;
                    }
                    
                    if (!filter.valid(extractable)) {
                        System.out.println("ItemTurboServo: Item " + extractable.getItem() + " failed filter check");
                        continue;
                    }
                    
                    // Find best destination for this item
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult destination = ourNode.findBestDestination(extractable);
                    if (destination == null) {
                        System.out.println("ItemTurboServo: No valid destination found for " + extractable.getItem());
                        continue;
                    }
                    
                    net.minecraft.core.BlockPos destPos = destination.destination;
                    cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = destination.pathInfo;
                    
                    System.out.println("ItemTurboServo: Found destination " + destPos + " for " + extractable.getItem());
                    
                    // Check if destination has capacity (including items in transit)
                    int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, extractable);
                    if (availableCapacity <= 0) {
                        System.out.println("ItemTurboServo: No capacity available at destination " + destPos);
                        continue;
                    }
                    
                    // Extract only what can fit and what we still need to extract
                    int toExtract = Math.min(extractable.getCount(), Math.min(remainingToExtract, availableCapacity));
                    
                    net.minecraft.world.item.ItemStack extracted = externalHandler.extractItem(slot, toExtract, false);
                    if (extracted.isEmpty()) {
                        System.out.println("ItemTurboServo: Actual extraction failed for slot " + slot);
                        continue;
                    }
                    
                    System.out.println("ItemTurboServo: Successfully extracted " + extracted.getCount() + "x " + extracted.getItem() + " from " + pos() + " -> routing to " + destPos + " (remaining: " + (remainingToExtract - extracted.getCount()) + ")");
                    
                    // Route item through grid
                    itemGrid.insertItem(extracted, pos(), side(), destPos, pathInfo.side, pathInfo.path);
                    remainingToExtract -= extracted.getCount();
                    
                    System.out.println("ItemTurboServo: Inserted item into grid for transport");
                    
                } catch (Exception e) {
                    System.out.println("ItemTurboServo: Exception processing slot " + slot + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            System.out.println("ItemTurboServo: Extraction cycle completed");
            
        } catch (Exception e) {
            System.out.println("ItemTurboServo: Exception during extraction: " + e.getMessage());
            e.printStackTrace();
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