package cofh.thermal.dynamics.client.renderer;

import cofh.thermal.dynamics.common.block.ItemDuctBlock;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
        // Debug: Log all render calls to see if we're being called at all
        Level level = Minecraft.getInstance().level;
        if (level != null && level.getGameTime() % 60 == 0) {
            System.out.println("CLIENT: renderItemsInTransit called at stage " + event.getStage() + " tick " + level.getGameTime());
        }
        
        // Try multiple render stages to ensure visibility
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS && 
            event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS &&
            event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            return;
        }

        if (level == null) {
            System.out.println("CLIENT: Level is null, returning");
            return;
        }
        
        // Debug logging every few seconds instead of every frame
        if (level.getGameTime() % 60 == 0) {
            System.out.println("CLIENT: ItemTransportRenderer proceeding with rendering at stage " + event.getStage() + " tick " + level.getGameTime());
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();

        // Don't translate by camera position - let Minecraft handle world-to-screen transformation
        poseStack.pushPose();
        System.out.println("CLIENT: Starting render pass, camera at " + cameraPos);

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
        // Use the cached block entity approach with improved position calculation
        Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int renderDistance = 32; // Reasonable render distance
        
        int itemCount = 0;
        int windowedDuctsFound = 0;
        int totalDuctsScanned = 0;
        int itemDuctsFound = 0;
        
        // Check block entities in a reasonable area around the camera
        int minX = (int) Math.floor(cameraPos.x - renderDistance);
        int maxX = (int) Math.ceil(cameraPos.x + renderDistance);
        int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(cameraPos.y - renderDistance));
        int maxY = Math.min(level.getMaxBuildHeight(), (int) Math.ceil(cameraPos.y + renderDistance));
        int minZ = (int) Math.floor(cameraPos.z - renderDistance);
        int maxZ = (int) Math.ceil(cameraPos.z + renderDistance);
        
        // Sample every block for better accuracy when finding ducts
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    totalDuctsScanned++;
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof ItemDuctBlockEntity ductEntity) {
                        itemDuctsFound++;
                        if (ductEntity.isWindowed()) {
                            windowedDuctsFound++;
                            List<ItemDuctBlockEntity.ItemTransitData> transitItems = ductEntity.getRenderItemsInTransit();
                            
                            if (level.getGameTime() % 60 == 0 || !transitItems.isEmpty()) {
                                System.out.println("CLIENT: Found windowed duct at " + pos + " with " + transitItems.size() + " transit items");
                            }
                            
                            for (ItemDuctBlockEntity.ItemTransitData transitData : transitItems) {
                                // Add smooth movement with partial tick interpolation
                                renderTransitItemWithInterpolation(transitData, poseStack, bufferSource, partialTick);
                                itemCount++;
                            }
                        } else if (level.getGameTime() % 60 == 0) {
                            System.out.println("CLIENT: Found non-windowed duct at " + pos);
                        }
                    }
                }
            }
        }
        
        
        // Log scan results every 60 frames to avoid spam
        if (level.getGameTime() % 60 == 0) {
            System.out.println("ItemTransportRenderer: Scanned " + totalDuctsScanned + " positions, found " + itemDuctsFound + " item ducts (" + windowedDuctsFound + " windowed), rendered " + itemCount + " items");
            System.out.println("ItemTransportRenderer: Camera at " + cameraPos + ", scanning from (" + minX + "," + minY + "," + minZ + ") to (" + maxX + "," + maxY + "," + maxZ + ")");
        }
        
        return itemCount;
    }
    
    private static boolean shouldRenderAtPosition(Vec3 position, Level level) {
        // Check if the position is within a windowed duct
        BlockPos blockPos = new BlockPos((int)Math.floor(position.x), (int)Math.floor(position.y), (int)Math.floor(position.z));
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        
        if (blockEntity instanceof ItemDuctBlockEntity ductEntity) {
            return ductEntity.isWindowed();
        }
        
        return false;
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
    
    private static void renderTransitItemWithInterpolation(ItemDuctBlockEntity.ItemTransitData transitData, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        Vec3 basePosition = transitData.position;
        
        System.out.println("CLIENT: renderTransitItemWithInterpolation called for item " + transitData.stack.getItem() + " at " + basePosition);
        
        poseStack.pushPose();
        
        // Get camera position for relative translation
        Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        
        // Translate relative to camera position (this is crucial for world rendering)
        double relativeX = basePosition.x - cameraPos.x;
        double relativeY = basePosition.y - cameraPos.y;
        double relativeZ = basePosition.z - cameraPos.z;
        
        poseStack.translate(relativeX, relativeY, relativeZ);
        System.out.println("CLIENT: Translated to relative position (" + relativeX + ", " + relativeY + ", " + relativeZ + ") from world " + basePosition + " camera " + cameraPos);
        
        // Add some rotation and bobbing for visual appeal
        float time = System.currentTimeMillis() * 0.001f;
        float bobbing = Mth.sin(time * 2.0f) * 0.1f;
        float rotation = time * 45.0f; // 45 degrees per second
        
        poseStack.translate(0, bobbing, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        
        // Scale to visible size
        poseStack.scale(0.5f, 0.5f, 0.5f);
        System.out.println("CLIENT: Applied transforms - bobbing=" + bobbing + " rotation=" + rotation);
        
        // Force maximum brightness
        int lightLevel = 15728880; // Full bright
        
        try {
            BakedModel model = itemRenderer.getModel(transitData.stack, null, null, 0);
            System.out.println("CLIENT: Got model: " + model);
            
            // Render the item using GROUND context - this is the most reliable for world rendering
            itemRenderer.render(transitData.stack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, lightLevel, 0, model);
            System.out.println("CLIENT: ItemRenderer.render() called successfully");
            
        } catch (Exception e) {
            System.out.println("CLIENT: ERROR in rendering: " + e.getMessage());
            e.printStackTrace();
        }
        
        poseStack.popPose();
        System.out.println("CLIENT: renderTransitItemWithInterpolation completed");
    }
    
    private static void renderTransitItemFromData(ItemDuctBlockEntity.ItemTransitData transitData, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        renderTransitItemWithInterpolation(transitData, poseStack, bufferSource, partialTick);
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

    private static void renderTransitItem(ItemGrid.ItemInTransit item, Vec3 renderPos, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
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
    
    private static void renderTransitItem(ItemGrid.ItemInTransit item, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        Vec3 renderPos = calculateItemRenderPosition(item, partialTick);
        renderTransitItem(item, renderPos, poseStack, bufferSource, partialTick);
    }

    private static Vec3 calculateItemRenderPosition(ItemGrid.ItemInTransit item, float partialTick) {
        // Calculate the current position along the path based on distance traveled
        double totalDistance = item.getTotalDistance();
        double currentDistance = item.distanceTraveled;
        
        // Add partial tick interpolation for smooth movement (0.05f is ITEM_SPEED from ItemGrid)
        double interpolatedDistance = currentDistance + (0.05f * partialTick);
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