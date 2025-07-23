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

    public static final int TRANSFER = 32; // Higher transfer rate than regular servo
    public static final int MAX_TRANSFER = 256; // Higher max burst

    public ItemTurboServoAttachment(IDuct<?, ?> duct, Direction side) {
        super(duct, side);
        this.amountTransfer = TRANSFER;
    }

    @Override
    public int getTransfer() {
        return TRANSFER;
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
        
        // Extract items from connected inventory and route through grid (turbo version)
        extractAndRouteItems();
    }
    
    private void extractAndRouteItems() {
        // Get connected external inventory
        LazyOptional<net.minecraftforge.items.IItemHandler> extCap = getExternalCapability();
        if (!extCap.isPresent()) return;
        
        net.minecraftforge.items.IItemHandler externalHandler = extCap.orElse(null);
        if (externalHandler == null) return;
        
        // Get grid for routing
        if (!(duct.getGrid() instanceof cofh.thermal.dynamics.common.grid.item.ItemGrid itemGrid)) return;
        
        amountTransfer += TRANSFER;
        amountTransfer = Math.min(amountTransfer, MAX_TRANSFER);
        
        // Try to extract items from external inventory
        for (int slot = 0; slot < externalHandler.getSlots() && amountTransfer > 0; slot++) {
            net.minecraft.world.item.ItemStack extractable = externalHandler.extractItem(slot, Math.min(amountTransfer, 64), true);
            if (extractable.isEmpty() || !filter.valid(extractable)) continue;
            
            // Find best destination for this item
            cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo pathInfo = findBestDestination(itemGrid, extractable);
            if (pathInfo == null) continue; // No valid destination
            
            // Check if destination has capacity (including items in transit)
            net.minecraft.core.BlockPos destPos = pathInfo.path.get(pathInfo.path.size() - 1);
            int availableCapacity = itemGrid.getAvailableCapacity(destPos, pathInfo.side, extractable);
            if (availableCapacity <= 0) continue; // No capacity available
            
            // Extract only what can fit
            int toExtract = Math.min(extractable.getCount(), Math.min(amountTransfer, availableCapacity));
            net.minecraft.world.item.ItemStack extracted = externalHandler.extractItem(slot, toExtract, false);
            if (extracted.isEmpty()) continue;
            
            // Route item through grid
            itemGrid.insertItem(extracted, pos(), side, destPos, pathInfo.side, pathInfo.path);
            amountTransfer -= extracted.getCount();
        }
    }
    
    private LazyOptional<net.minecraftforge.items.IItemHandler> getExternalCapability() {
        if (externalCap.isPresent()) return externalCap;
        
        // Find connected tile
        net.minecraft.world.level.block.entity.BlockEntity tile = world().getBlockEntity(pos().relative(side));
        if (tile == null) return LazyOptional.empty();
        
        return tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side.getOpposite());
    }
    
    private cofh.thermal.dynamics.common.grid.item.ItemGridNode.PathInfo findBestDestination(cofh.thermal.dynamics.common.grid.item.ItemGrid grid, net.minecraft.world.item.ItemStack stack) {
        // Get our grid node
        cofh.thermal.dynamics.common.grid.item.ItemGridNode ourNode = grid.getNodes().get(pos());
        if (ourNode == null) return null;
        
        // Use the node's pathfinding to find destinations
        cofh.thermal.dynamics.common.grid.item.ItemGridNode.DestinationResult result = ourNode.findBestDestination(stack);
        return result != null ? result.pathInfo : null;
    }
}