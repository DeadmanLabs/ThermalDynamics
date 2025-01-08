package cofh.thermal.dynamics.common.inventory.slot;

import cofh.lib.common.inventory.SlotFalseCopy;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import static cofh.core.util.helpers.ItemHelper.cloneStack;

/**
 * Slot which copies an ItemStack when clicked on, does not decrement the ItemStack on the cursor.
 */
public class SlotFalseBuffer extends SlotFalseCopy {

    public SlotFalseBuffer(Container inventoryIn, int index, int xPosition, int yPosition) {

        super(inventoryIn, index, xPosition, yPosition);
    }

    @Override
    public void set(ItemStack stack) {

        if (!mayPlace(stack)) {
            return;
        }
        super.set(stack);
        setChanged();
    }

    public void incrementStack(int count) {

        ItemStack myStack = getItem();
        if (!myStack.isEmpty() && myStack.getCount() < myStack.getMaxStackSize()) {
            super.set(cloneStack(myStack, Math.min(myStack.getCount() + count, myStack.getMaxStackSize())));
            setChanged();
        }
    }

    public void decrementStack(int count) {

        ItemStack myStack = getItem();
        if (!myStack.isEmpty() && myStack.getCount() > 1) {
            super.set(cloneStack(myStack, Math.max(myStack.getCount() - count, 1)));
            setChanged();
        }
    }

}
