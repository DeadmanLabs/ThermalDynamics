package cofh.thermal.dynamics.init.registries;

import cofh.core.common.block.EntityBlock6Way;
import cofh.thermal.core.common.config.ThermalCoreConfig;
import cofh.thermal.dynamics.common.block.DuctBlock;
import cofh.thermal.dynamics.common.block.ItemDuctBlock;
import cofh.thermal.dynamics.common.block.entity.ItemBufferBlockEntity;
import cofh.thermal.dynamics.common.item.DuctBlockItem;
import net.minecraft.world.level.block.SoundType;

import java.util.function.IntSupplier;

import static cofh.lib.util.Utils.itemProperties;
import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;
import static cofh.thermal.core.ThermalCore.BLOCKS;
import static cofh.thermal.core.init.registries.ThermalCreativeTabs.devicesTab;
import static cofh.thermal.core.util.RegistrationHelper.registerBlock;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.*;
import static cofh.thermal.dynamics.init.registries.TDynIDs.*;
import static net.minecraft.world.level.block.state.BlockBehaviour.Properties.of;

public class TDynBlocks {

    private TDynBlocks() {

    }

    public static void register() {

        registerTileBlocks();
    }

    // region HELPERS
    private static void registerTileBlocks() {

        devicesTab(50, registerBlock(ID_ENERGY_DUCT,
                () -> new DuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), ENERGY_DUCT_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ENERGY_DUCT), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_FLUID_DUCT,
                () -> new DuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), FLUID_DUCT_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_FLUID_DUCT), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_FLUID_DUCT_WINDOWED,
                () -> new DuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), FLUID_DUCT_WINDOWED_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_FLUID_DUCT_WINDOWED), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        // Item Duct Base Variants
        devicesTab(50, registerBlock(ID_ITEM_DUCT,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));

        // Item Duct Dense Variants
        devicesTab(50, registerBlock(ID_ITEM_DUCT_DENSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_DENSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_DENSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_DENSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_DENSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_DENSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));

        // Item Duct Vacuum Variants
        devicesTab(50, registerBlock(ID_ITEM_DUCT_VACUUM,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_VACUUM_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_VACUUM), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_VACUUM_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_VACUUM_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_VACUUM_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));

        // Item Duct Impulse Variants
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_IMPULSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_IMPULSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE_DENSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_IMPULSE_DENSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE_DENSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE_DENSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_IMPULSE_DENSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE_DENSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE_VACUUM,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_IMPULSE_VACUUM_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE_VACUUM), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_IMPULSE_VACUUM_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_IMPULSE_VACUUM_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_IMPULSE_VACUUM_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));

        // Item Duct Signalum Variants
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_DENSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_DENSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_DENSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_DENSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_DENSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_DENSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_VACUUM,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_VACUUM_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_VACUUM), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_VACUUM_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_VACUUM_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_VACUUM_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_IMPULSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_IMPULSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE_DENSE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_IMPULSE_DENSE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE_DENSE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE_DENSE_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_IMPULSE_DENSE_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE_DENSE_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), true, ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
        devicesTab(50, registerBlock(ID_ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM_OPAQUE,
                () -> new ItemDuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), false, ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM_OPAQUE_BLOCK_ENTITY),
                () -> new DuctBlockItem(BLOCKS.get(ID_ITEM_DUCT_SIGNALUM_IMPULSE_VACUUM_OPAQUE), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));


        IntSupplier storageAugs = () -> ThermalCoreConfig.storageAugments;

        // registerAugmentableBlock(ID_ENERGY_DISTRIBUTOR, () -> new TileBlockActive6Way(of().sound(SoundType.LANTERN).strength(2.0F).harvestTool(ToolType.PICKAXE).noOcclusion(), EnergyDistributorTile::new), storageAugs, ENERGY_STORAGE_VALIDATOR, ID_THERMAL_DYNAMICS);

        devicesTab(150, registerBlock(ID_ITEM_BUFFER, () -> new EntityBlock6Way(of().sound(SoundType.NETHERITE_BLOCK).strength(2.0F), ItemBufferBlockEntity.class, ITEM_BUFFER_BLOCK_ENTITY), ID_THERMAL_DYNAMICS));
    }
    // endregion
}
