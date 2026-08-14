package net.kuma.create_extra_recipes;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

final class ClothConfigScreen {

    private static final String MECHANICAL_SPAWNER_MODID = "create_mechanical_spawner";

    private ClothConfigScreen() {
    }

    static Screen build(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config." + Create_extra_recipes.MODID + ".title"));

        var entryBuilder = builder.entryBuilder();

        ConfigCategory vanillaCategory = builder.getOrCreateCategory(
                Component.translatable("config." + Create_extra_recipes.MODID + ".category.vanillaRecipes"));

        vanillaCategory.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config." + Create_extra_recipes.MODID + ".enableSmithingTemplateNerf"),
                        Config.ENABLE_SMITHING_TEMPLATE_NERF.get())
                .setDefaultValue(true)
                .setSaveConsumer(Config.ENABLE_SMITHING_TEMPLATE_NERF::set)
                .build());

        vanillaCategory.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config." + Create_extra_recipes.MODID + ".enableWoodBuff"),
                        Config.ENABLE_WOOD_BUFF.get())
                .setDefaultValue(true)
                .setSaveConsumer(Config.ENABLE_WOOD_BUFF::set)
                .build());

        vanillaCategory.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config." + Create_extra_recipes.MODID + ".enableRawOreBlasting"),
                        Config.ENABLE_RAW_ORE_BLASTING.get())
                .setDefaultValue(true)
                .setSaveConsumer(Config.ENABLE_RAW_ORE_BLASTING::set)
                .build());

        ConfigCategory createCategory = builder.getOrCreateCategory(
                Component.translatable("config." + Create_extra_recipes.MODID + ".category.createRecipes"));

        createCategory.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config." + Create_extra_recipes.MODID + ".enableCopperOxidationFilling"),
                        Config.ENABLE_COPPER_OXIDATION_FILLING.get())
                .setDefaultValue(true)
                .setSaveConsumer(Config.ENABLE_COPPER_OXIDATION_FILLING::set)
                .build());

        ConfigCategory otherModsCategory = builder.getOrCreateCategory(
                Component.translatable("config." + Create_extra_recipes.MODID + ".category.otherModsCompat"));

        AbstractConfigListEntry<Boolean> mechanicalSpawnEggEntry = entryBuilder
                .startBooleanToggle(Component.translatable("config." + Create_extra_recipes.MODID + ".enableMechanicalSpawnEgg"),
                        Config.ENABLE_MECHANICAL_SPAWN_EGG.get())
                .setDefaultValue(true)
                .setSaveConsumer(Config.ENABLE_MECHANICAL_SPAWN_EGG::set)
                .setTooltip(mechanicalSpawnerTooltip())
                .build();
        mechanicalSpawnEggEntry.setEditable(ModList.get().isLoaded(MECHANICAL_SPAWNER_MODID));
        otherModsCategory.addEntry(mechanicalSpawnEggEntry);

        return builder.build();
    }

    private static Component[] mechanicalSpawnerTooltip() {
        if (ModList.get().isLoaded(MECHANICAL_SPAWNER_MODID)) {
            return new Component[0];
        }
        return new Component[] {
                Component.translatable("config." + Create_extra_recipes.MODID + ".missingDependency", "Create: Mechanical Spawner")
        };
    }
}
