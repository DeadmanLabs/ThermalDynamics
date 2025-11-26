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
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static cofh.lib.util.constants.ModIds.ID_THERMAL;

/**
 * Optimized renderer for items flowing through windowed item ducts.
 * Uses efficient tracking instead of world scanning for better performance.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = ID_THERMAL)
public class ItemTransportRenderer {

    private static final ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
    
    // Performance optimizations
    private static final Map<BlockPos, ItemDuctBlockEntity> windowedDuctCache = new ConcurrentHashMap<>();
    private static final Set<BlockPos> dirtyDucts = ConcurrentHashMap.newKeySet();
    private static final int RENDER_DISTANCE = 32; // Reduced from 64x64x64 scanning
    private static final int MAX_ITEMS_PER_FRAME = 100; // Limit items rendered per frame
    
    // Cached render data to avoid recalculation
    private static final Map<ItemDuctBlockEntity, List<ItemDuctBlockEntity.ItemTransitData>> cachedRenderData = new ConcurrentHashMap<>();
    
    // No complex interpolation needed - server handles smooth movement

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ItemTransportRenderer::renderItemsInTransit);
    }
    
    /**
     * Register a windowed duct for efficient tracking
     */
    public static void registerWindowedDuct(ItemDuctBlockEntity duct) {
        windowedDuctCache.put(duct.getBlockPos(), duct);
        dirtyDucts.add(duct.getBlockPos());
    }
    
    /**
     * Unregister a windowed duct when removed
     */
    public static void unregisterWindowedDuct(BlockPos pos) {
        windowedDuctCache.remove(pos);
        dirtyDucts.remove(pos);
        cachedRenderData.entrySet().removeIf(entry -> entry.getKey().getBlockPos().equals(pos));
    }
    
    /**
     * Mark a duct as dirty for render data updates
     */
    public static void markDuctDirty(BlockPos pos) {
        dirtyDucts.add(pos);
    }

    private static void renderItemsInTransit(RenderLevelStageEvent event) {
        // Only render during the appropriate stage
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();

        poseStack.pushPose();
        
        // Update cache every frame for smooth movement
        updateRenderCache(level, cameraPos);
        
        // Render from cached data
        renderFromCache(poseStack, buffer, cameraPos, partialTick);

        buffer.endBatch();
        poseStack.popPose();
    }

    /**
     * Update the render cache by checking dirty ducts and removing invalid ones
     */
    private static void updateRenderCache(Level level, Vec3 cameraPos) {
        
        // Remove invalid ducts
        windowedDuctCache.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            ItemDuctBlockEntity duct = entry.getValue();
            
            // Check if duct is still valid and within render distance
            if (duct.isRemoved() || !duct.isWindowed() || 
                pos.distSqr(BlockPos.containing(cameraPos)) > RENDER_DISTANCE * RENDER_DISTANCE) {
                cachedRenderData.remove(duct);
                dirtyDucts.remove(pos);
                return true;
            }
            return false;
        });
        
        // Update render data for ALL ducts every update for smooth movement
        for (ItemDuctBlockEntity duct : windowedDuctCache.values()) {
            if (duct != null && duct.isWindowed()) {
                List<ItemDuctBlockEntity.ItemTransitData> transitItems = duct.getRenderItemsInTransit();
                cachedRenderData.put(duct, new ArrayList<>(transitItems));
            }
        }
        dirtyDucts.clear();
    }
    
    /**
     * Render from cached data instead of scanning the world
     */
    private static void renderFromCache(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 cameraPos, float partialTick) {
        int itemsRendered = 0;
        
        for (Map.Entry<ItemDuctBlockEntity, List<ItemDuctBlockEntity.ItemTransitData>> entry : cachedRenderData.entrySet()) {
            ItemDuctBlockEntity duct = entry.getKey();
            List<ItemDuctBlockEntity.ItemTransitData> transitItems = entry.getValue();
            
            // Skip if too far away (distance culling)
            BlockPos ductPos = duct.getBlockPos();
            if (ductPos.distSqr(BlockPos.containing(cameraPos)) > RENDER_DISTANCE * RENDER_DISTANCE) {
                continue;
            }
            
            // Limit items rendered per frame for performance
            int itemsToRender = Math.min(transitItems.size(), MAX_ITEMS_PER_FRAME - itemsRendered);
            
            for (int i = 0; i < itemsToRender; i++) {
                ItemDuctBlockEntity.ItemTransitData transitData = transitItems.get(i);
                Vec3 smoothPosition = getSmoothInterpolatedPosition(transitData, ductPos);
                renderTransitItemWithSmoothPosition(transitData, smoothPosition, poseStack, bufferSource, partialTick, cameraPos);
                itemsRendered++;
            }
            
            if (itemsRendered >= MAX_ITEMS_PER_FRAME) {
                break; // Performance limit reached
            }
        }
    }
    
    /**
     * Optimized rendering method with reduced allocations and no debug logging
     */
    private static void renderTransitItemOptimized(ItemDuctBlockEntity.ItemTransitData transitData, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick, Vec3 cameraPos) {
        Vec3 basePosition = transitData.position;
        
        poseStack.pushPose();
        
        // Translate relative to camera position
        double relativeX = basePosition.x - cameraPos.x;
        double relativeY = basePosition.y - cameraPos.y;
        double relativeZ = basePosition.z - cameraPos.z;
        
        poseStack.translate(relativeX, relativeY, relativeZ);
        
        // Optimized animation calculations
        long timeMillis = System.currentTimeMillis();
        float time = timeMillis * 0.001f;
        float bobbing = Mth.sin(time * 2.0f) * 0.05f; // Reduced bobbing
        float rotation = (timeMillis * 0.045f) % 360f; // Optimized rotation
        
        poseStack.translate(0, bobbing, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.scale(0.4f, 0.4f, 0.4f); // Slightly smaller for better performance
        
        // Use cached light level
        int lightLevel = 15728880;
        
        try {
            BakedModel model = itemRenderer.getModel(transitData.stack, null, null, 0);
            itemRenderer.render(transitData.stack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, lightLevel, 0, model);
        } catch (Exception e) {
            // Silent failure - don't spam console
        }
        
        poseStack.popPose();
    }
    
    /**
     * Get position for rendering - use server position directly for smoothest movement
     * The server already calculates smooth movement at 0.05 blocks per tick
     */
    private static Vec3 getSmoothInterpolatedPosition(ItemDuctBlockEntity.ItemTransitData transitData, BlockPos ductPos) {
        // Use server position directly - server handles smooth movement calculation
        return transitData.position;
    }
    
    /**
     * Render item at a specific smooth position with proper spinning animation
     */
    private static void renderTransitItemWithSmoothPosition(ItemDuctBlockEntity.ItemTransitData transitData, Vec3 smoothPosition, PoseStack poseStack, MultiBufferSource bufferSource, float partialTick, Vec3 cameraPos) {
        poseStack.pushPose();

        // Get interpolated position for smooth movement between server updates
        Vec3 interpolatedPos = transitData.getInterpolatedPosition(partialTick);

        // Translate relative to camera position
        double relativeX = interpolatedPos.x - cameraPos.x;
        double relativeY = interpolatedPos.y - cameraPos.y;
        double relativeZ = interpolatedPos.z - cameraPos.z;

        poseStack.translate(relativeX, relativeY, relativeZ);

        // Progress-based rotation: item spins as it travels through the duct
        // Slowed down: 2 full spins over the journey (was 4)
        float progressRotation = transitData.progress * 720f * 2f;

        // Add time-based continuous rotation for extra smoothness (slowed by half)
        long timeMillis = System.currentTimeMillis();
        float timeRotation = (timeMillis * 0.09f) % 360f; // Halved from 0.18f

        // Combined rotation
        float totalRotation = (progressRotation + timeRotation) % 360f;

        // Gentle bobbing based on progress
        float bobbing = Mth.sin(transitData.progress * 6.28f * 2f) * 0.03f;

        poseStack.translate(0, bobbing, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(totalRotation));
        poseStack.scale(0.45f, 0.45f, 0.45f); // Slightly larger (was 0.35f)

        // Calculate light level based on item position (like item on floor)
        Level level = Minecraft.getInstance().level;
        int lightLevel;
        if (level != null) {
            BlockPos lightPos = BlockPos.containing(interpolatedPos);
            int blockLight = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, lightPos);
            int skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lightPos);
            lightLevel = net.minecraft.client.renderer.LightTexture.pack(blockLight, skyLight);
        } else {
            lightLevel = 15728880; // Full bright fallback
        }

        try {
            BakedModel model = itemRenderer.getModel(transitData.stack, null, null, 0);
            itemRenderer.render(transitData.stack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, lightLevel, 0, model);
        } catch (Exception e) {
            // Silent failure - don't spam console
        }

        poseStack.popPose();
    }
    
}