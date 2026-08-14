package net.kuma.create_extra_recipes;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_SMITHING_TEMPLATE_NERF;
    public static final ModConfigSpec.BooleanValue ENABLE_WOOD_BUFF;
    public static final ModConfigSpec.BooleanValue ENABLE_RAW_ORE_BLASTING;
    public static final ModConfigSpec.BooleanValue ENABLE_COPPER_OXIDATION_FILLING;
    public static final ModConfigSpec.BooleanValue ENABLE_MECHANICAL_SPAWN_EGG;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("vanillaRecipes");

        ENABLE_SMITHING_TEMPLATE_NERF = builder
                .comment("Nerf duplicated armor trim smithing templates (swaps the majority/minority ingredient counts).")
                .define("enableSmithingTemplateNerf", true);

        ENABLE_WOOD_BUFF = builder
                .comment("Buff 2x2 log-to-wood crafting recipes from 3 output to 4.")
                .define("enableWoodBuff", true);

        ENABLE_RAW_ORE_BLASTING = builder
                .comment("Generate blasting recipes for raw ore blocks that don't already have one.")
                .define("enableRawOreBlasting", true);

        builder.pop();
        builder.push("createRecipes");

        ENABLE_COPPER_OXIDATION_FILLING = builder
                .comment("Generate Create filling recipes for copper (and copper-like) oxidation stages.")
                .define("enableCopperOxidationFilling", true);

        builder.pop();
        builder.push("otherModsCompat");

        ENABLE_MECHANICAL_SPAWN_EGG = builder
                .comment("Generate Create filling recipes turning eggs into mob spawn eggs using Create: Mechanical "
                        + "Spawner's fluids. Has no effect unless that mod is also installed.")
                .define("enableMechanicalSpawnEgg", true);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
