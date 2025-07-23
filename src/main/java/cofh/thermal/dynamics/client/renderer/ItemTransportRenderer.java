package cofh.thermal.dynamics.client.renderer;

import cofh.thermal.dynamics.common.block.ItemDuctBlock;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;

import static cofh.lib.util.constants.ModIds.ID_THERMAL;

/**
 * Renders items flowing through windowed item ducts.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = ID_THERMAL)
public class ItemTransportRenderer {

    private static final ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();

    public static void register() {
        System.out.println("ItemTransportRenderer: Registering render event listener");
        MinecraftForge.EVENT_BUS.addListener(ItemTransportRenderer::renderItemsInTransit);
        System.out.println("ItemTransportRenderer: Render event listener registered successfully");
    }

    private static void renderItemsInTransit(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        
        // Always log to debug why renderer isn't working
        System.out.println("ItemTransportRenderer: Render event called on " + (level.isClientSide ? "client" : "server") + " at tick " + level.getGameTime());

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Get all ItemGrids in the level and render their items in transit
        renderItemsForAllGrids(level, poseStack, buffer, partialTick);

        buffer.endBatch();
        poseStack.popPose();
    }

    private static void renderItemsForAllGrids(Level level, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        System.out.println("ItemTransportRenderer: renderItemsForAllGrids called, isClientSide=" + level.isClientSide);
        
        // Use block entity approach for client-side rendering
        if (level.isClientSide) {
            int itemCount = renderFromBlockEntities(level, poseStack, bufferSource, partialTick);
            System.out.println("ItemTransportRenderer: renderFromBlockEntities returned " + itemCount + " items rendered");
        } else {
            System.out.println("ItemTransportRenderer: Not rendering because not client side");
        }
    }
    
    private static int renderFromBlockEntities(Level level, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        // Use the level's block entity tick list to find loaded ItemDuctBlockEntity instances
        Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int renderDistance = 16; // Smaller search area but check every block
        
        int itemCount = 0;
        int totalItemsFound = 0;
        int entitiesChecked = 0;
        int windowedDuctsFound = 0;
        
        // Check block entities in a reasonable area around the camera
        int minX = (int) Math.floor(cameraPos.x - renderDistance);
        int maxX = (int) Math.ceil(cameraPos.x + renderDistance);
        int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(cameraPos.y - renderDistance));
        int maxY = Math.min(level.getMaxBuildHeight(), (int) Math.ceil(cameraPos.y + renderDistance));
        int minZ = (int) Math.floor(cameraPos.z - renderDistance);
        int maxZ = (int) Math.ceil(cameraPos.z + renderDistance);
        
        System.out.println("ItemTransportRenderer: Scanning area from " + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ + " around camera at " + cameraPos);
        
        for (int x = minX; x <= maxX; x += 1) { // Check every block to ensure we don't miss any ducts
            for (int y = minY; y <= maxY; y += 1) {
                for (int z = minZ; z <= maxZ; z += 1) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof ItemDuctBlockEntity ductEntity) {
                        entitiesChecked++;
                        System.out.println("ItemTransportRenderer: Found ItemDuctBlockEntity at " + pos + " - windowed=" + ductEntity.isWindowed());
                        
                        if (ductEntity.isWindowed()) {
                            windowedDuctsFound++;
                            List<ItemDuctBlockEntity.ItemTransitData> transitItems = ductEntity.getRenderItemsInTransit();
                            totalItemsFound += transitItems.size();
                            
                            System.out.println("ItemTransportRenderer: Windowed duct at " + pos + " has " + transitItems.size() + " transit items");
                            
                            for (ItemDuctBlockEntity.ItemTransitData transitData : transitItems) {
                                System.out.println("ItemTransportRenderer: Rendering item " + transitData.stack.getItem() + " at " + transitData.position);
                                renderTransitItemFromData(transitData, poseStack, bufferSource, partialTick);
                                itemCount++;
                                System.out.println("ItemTransportRenderer: Successfully rendered item " + transitData.stack.getItem());
                            }
                        } else {
                            // Also check non-windowed ducts to see if they have transit data (they shouldn't but let's verify)
                            List<ItemDuctBlockEntity.ItemTransitData> transitItems = ductEntity.getRenderItemsInTransit();
                            if (!transitItems.isEmpty()) {
                                System.out.println("ItemTransportRenderer: WARNING - Non-windowed duct at " + pos + " has " + transitItems.size() + " transit items (should be 0)");
                            }
                        }
                    }
                }
            }
        }
        
        // Always log to debug the issue
        System.out.println("ItemTransportRenderer: Checked " + entitiesChecked + " ducts (" + windowedDuctsFound + " windowed), found " + totalItemsFound + " transit items, rendered " + itemCount + " items");
        
        return itemCount;
    }

    private static void renderItemsForGrid(ItemGrid grid, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        Collection<ItemGrid.ItemInTransit> itemsInTransit = grid.getItemsInTransit();
        
        if (!itemsInTransit.isEmpty()) {
            System.out.println("ItemTransportRenderer: Grid has " + itemsInTransit.size() + " items in transit");
        }
        
        for (ItemGrid.ItemInTransit item : itemsInTransit) {
            System.out.println("ItemTransportRenderer: Checking item " + item.stack.getItem() + " for rendering");
            if (shouldRenderItem(item)) {
                System.out.println("ItemTransportRenderer: Rendering item " + item.stack.getItem());
                renderTransitItem(item, poseStack, bufferSource, partialTick);
            } else {
                System.out.println("ItemTransportRenderer: Item " + item.stack.getItem() + " not in windowed duct");
            }
        }
    }

    private static boolean shouldRenderItem(ItemGrid.ItemInTransit item) {
        // Only render items in windowed ducts
        Vec3 currentPos = calculateItemCurrentPosition(item);
        BlockPos blockPos = new BlockPos((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
        
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        
        // Check if the block is a windowed item duct
        Block block = level.getBlockState(blockPos).getBlock();
        if (block instanceof ItemDuctBlock itemDuct) {
            return itemDuct.isWindowed();
        }
        
        // Also check block entity as fallback
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity instanceof ItemDuctBlockEntity itemDuctEntity) {
            return itemDuctEntity.isWindowed();
        }
        
        return false;
    }
    
    private static boolean shouldRenderItemAtPosition(ItemDuctBlockEntity.ItemTransitData transitData, Level level) {
        // Check if this duct position has a windowed duct
        BlockPos blockPos = new BlockPos((int)Math.floor(transitData.position.x), (int)Math.floor(transitData.position.y), (int)Math.floor(transitData.position.z));
        
        Block block = level.getBlockState(blockPos).getBlock();
        if (block instanceof ItemDuctBlock itemDuct) {
            return itemDuct.isWindowed();
        }
        
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity instanceof ItemDuctBlockEntity itemDuctEntity) {
            return itemDuctEntity.isWindowed();
        }
        
        return false;
    }
    
    private static void renderTransitItemFromData(ItemDuctBlockEntity.ItemTransitData transitData, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        poseStack.pushPose();
        poseStack.translate(transitData.position.x, transitData.position.y, transitData.position.z);
        
        // Add some rotation and bobbing for visual appeal
        float time = System.currentTimeMillis() * 0.001f;
        float bobbing = Mth.sin(time * 2.0f) * 0.025f;
        float rotation = time * 45.0f; // 45 degrees per second
        
        poseStack.translate(0, bobbing, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.scale(0.5f, 0.5f, 0.5f); // Make items smaller when flowing
        
        // Render the item
        BakedModel model = itemRenderer.getModel(transitData.stack, null, null, 0);
        itemRenderer.render(transitData.stack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, 15728880, 0, model);
        
        poseStack.popPose();
    }
    
    private static Vec3 calculateItemCurrentPosition(ItemGrid.ItemInTransit item) {
        // Calculate the current position without partial tick interpolation
        double totalDistance = item.getTotalDistance();
        double currentDistance = item.distanceTraveled;
        double progress = Math.min(1.0, currentDistance / totalDistance);
        
        if (item.path.size() <= 1) {
            // Direct connection - interpolate between origin and destination
            Vec3 origin = Vec3.atCenterOf(item.origin);
            Vec3 destination = Vec3.atCenterOf(item.destination);
            return origin.lerp(destination, progress);
        }
        
        // Multi-segment path - find which segment we're on and interpolate within it
        return calculatePositionAlongPath(item, progress);
    }

    private static void renderTransitItem(ItemGrid.ItemInTransit item, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        Vec3 renderPos = calculateItemRenderPosition(item, partialTick);
        
        poseStack.pushPose();
        poseStack.translate(renderPos.x, renderPos.y, renderPos.z);
        
        // Add some rotation and bobbing for visual appeal
        float time = System.currentTimeMillis() * 0.001f;
        float bobbing = Mth.sin(time * 2.0f) * 0.025f;
        float rotation = time * 45.0f; // 45 degrees per second
        
        poseStack.translate(0, bobbing, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.scale(0.5f, 0.5f, 0.5f); // Make items smaller when flowing
        
        // Render the item
        BakedModel model = itemRenderer.getModel(item.stack, null, null, 0);
        itemRenderer.render(item.stack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, 15728880, 0, model);
        
        poseStack.popPose();
    }

    private static Vec3 calculateItemRenderPosition(ItemGrid.ItemInTransit item, float partialTick) {
        // Calculate the current position along the path based on distance traveled
        double totalDistance = item.getTotalDistance();
        double currentDistance = item.distanceTraveled;
        
        // Add partial tick interpolation for smooth movement
        double interpolatedDistance = currentDistance + (0.1f * partialTick); // 0.1f is ITEM_SPEED from ItemGrid
        double progress = Math.min(1.0, interpolatedDistance / totalDistance);
        
        if (item.path.size() <= 1) {
            // Direct connection - interpolate between origin and destination
            Vec3 origin = Vec3.atCenterOf(item.origin);
            Vec3 destination = Vec3.atCenterOf(item.destination);
            return origin.lerp(destination, progress);
        }
        
        // Multi-segment path - find which segment we're on and interpolate within it
        return calculatePositionAlongPath(item, progress);
    }

    private static Vec3 calculatePositionAlongPath(ItemGrid.ItemInTransit item, double progress) {
        // Calculate cumulative distances for each path segment
        double totalDistance = item.getTotalDistance();
        double targetDistance = progress * totalDistance;
        
        Vec3 currentPos = Vec3.atCenterOf(item.origin);
        double accumulatedDistance = 0;
        
        // Check distance from origin to first path node
        if (!item.path.isEmpty()) {
            Vec3 firstNode = Vec3.atCenterOf(item.path.get(0));
            double segmentDistance = currentPos.distanceTo(firstNode);
            
            if (targetDistance <= accumulatedDistance + segmentDistance) {
                // Item is between origin and first path node
                double segmentProgress = (targetDistance - accumulatedDistance) / segmentDistance;
                return currentPos.lerp(firstNode, segmentProgress);
            }
            
            accumulatedDistance += segmentDistance;
            currentPos = firstNode;
        }
        
        // Check distances between path nodes
        for (int i = 1; i < item.path.size(); i++) {
            Vec3 nextNode = Vec3.atCenterOf(item.path.get(i));
            double segmentDistance = currentPos.distanceTo(nextNode);
            
            if (targetDistance <= accumulatedDistance + segmentDistance) {
                // Item is between these two path nodes
                double segmentProgress = (targetDistance - accumulatedDistance) / segmentDistance;
                return currentPos.lerp(nextNode, segmentProgress);
            }
            
            accumulatedDistance += segmentDistance;
            currentPos = nextNode;
        }
        
        // Item is between last path node and destination
        Vec3 destination = Vec3.atCenterOf(item.destination);
        double finalSegmentDistance = currentPos.distanceTo(destination);
        
        if (finalSegmentDistance > 0) {
            double segmentProgress = (targetDistance - accumulatedDistance) / finalSegmentDistance;
            return currentPos.lerp(destination, Math.min(1.0, segmentProgress));
        }
        
        return destination;
    }
}