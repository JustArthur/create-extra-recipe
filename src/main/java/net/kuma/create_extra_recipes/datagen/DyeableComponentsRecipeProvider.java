package net.kuma.create_extra_recipes.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.DyeColor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DyeableComponentsRecipeProvider implements DataProvider {

    private static final String NAMESPACE = "createextrarecipes";
    private static final String BASE_PATH = "recipe/dyeable_components/compat";

    private record Category(String folder, boolean ingredientIsTag, String ingredient, String resultNamespace,
                             String resultSuffix, String fileNameFormat, List<String> requiredMods) {

        String fileName(DyeColor color) {
            return String.format(fileNameFormat, color.getSerializedName());
        }

        String resultId(DyeColor color) {
            return resultNamespace + ":" + color.getSerializedName() + resultSuffix;
        }
    }

    private static final List<String> COLORING_MODS = List.of("vanillabackport", "create_dragons_plus");
    private static final List<String> DRAGONS_PLUS_ONLY = List.of("create_dragons_plus");

    private static final List<Category> CATEGORIES = List.of(
            new Category("bundles", true, "minecraft:bundles", "minecraft", "_bundle", "bundle_%s.json", COLORING_MODS),
            new Category("harnesses", true, "minecraft:harnesses", "minecraft", "_harness", "harness_%s.json", COLORING_MODS),
            new Category("banners", true, "minecraft:banners", "minecraft", "_banner", "%s_banner.json", DRAGONS_PLUS_ONLY),
            new Category("shulker_boxes", false, "minecraft:shulker_box", "minecraft", "_shulker_box", "%s_shulker_box.json", DRAGONS_PLUS_ONLY),
            new Category("toolboxes", true, "create:toolboxes", "create", "_toolbox", "%s_toolbox.json", DRAGONS_PLUS_ONLY)
    );

    private final PackOutput packOutput;

    public DyeableComponentsRecipeProvider(PackOutput packOutput) {
        this.packOutput = packOutput;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        Path root = packOutput.getOutputFolder(PackOutput.Target.DATA_PACK);
        List<CompletableFuture<?>> futures = new java.util.ArrayList<>();

        for (Category category : CATEGORIES) {
            for (DyeColor color : DyeColor.values()) {
                JsonObject json = buildRecipeJson(category, color);
                Path path = root.resolve(NAMESPACE).resolve(BASE_PATH)
                        .resolve(category.folder())
                        .resolve(category.fileName(color));
                futures.add(DataProvider.saveStable(output, json, path));
            }
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private static JsonObject buildRecipeJson(Category category, DyeColor color) {
        JsonObject json = new JsonObject();

        JsonArray conditions = new JsonArray();
        for (String modid : category.requiredMods()) {
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "neoforge:mod_loaded");
            condition.addProperty("modid", modid);
            conditions.add(condition);
        }
        json.add("neoforge:conditions", conditions);

        json.addProperty("type", "create_dragons_plus:coloring");
        json.addProperty("color", "minecraft:" + color.getSerializedName());

        JsonArray ingredients = new JsonArray();
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty(category.ingredientIsTag() ? "tag" : "item", category.ingredient());
        ingredients.add(ingredient);
        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        JsonObject result = new JsonObject();
        result.addProperty("id", category.resultId(color));
        result.addProperty("count", 1);
        results.add(result);
        json.add("results", results);

        return json;
    }

    @Override
    public String getName() {
        return "Dyeable Components Recipes";
    }
}
