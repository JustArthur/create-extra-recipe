package net.kuma.create_extra_recipes.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MechanicalSpawnerEggRecipeProvider implements DataProvider {

    private static final String NAMESPACE = "createextrarecipes";
    private static final String BASE_PATH = "recipe/filling/compat/create_mechanical_spawner";
    private static final String MODID = "create_mechanical_spawner";
    private static final int SPAWN_FLUID_AMOUNT = 250;

    private static final List<String> ENTITIES = List.of(
            "bat", "bee", "blaze", "chicken", "cow", "creeper", "drowned", "enderman", "evoker",
            "fox", "ghast", "horse", "magma_cube", "panda", "parrot", "pig", "piglin", "rabbit",
            "skeleton", "slime", "spider", "villager", "witch", "wither_skeleton", "wither", "wolf", "zombie"
    );

    private final PackOutput packOutput;

    public MechanicalSpawnerEggRecipeProvider(PackOutput packOutput) {
        this.packOutput = packOutput;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        Path root = packOutput.getOutputFolder(PackOutput.Target.DATA_PACK);
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (String entity : ENTITIES) {
            JsonObject json = buildRecipeJson(entity);
            Path path = root.resolve(NAMESPACE).resolve(BASE_PATH).resolve(entity + "_spawn_egg.json");
            futures.add(DataProvider.saveStable(output, json, path));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private static JsonObject buildRecipeJson(String entity) {
        JsonObject json = new JsonObject();

        JsonArray conditions = new JsonArray();
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "neoforge:mod_loaded");
        condition.addProperty("modid", MODID);
        conditions.add(condition);
        json.add("neoforge:conditions", conditions);

        json.addProperty("type", "create:filling");

        JsonArray ingredients = new JsonArray();
        JsonObject eggIngredient = new JsonObject();
        eggIngredient.addProperty("item", "minecraft:egg");
        ingredients.add(eggIngredient);

        JsonObject fluidIngredient = new JsonObject();
        fluidIngredient.addProperty("type", "neoforge:single");
        fluidIngredient.addProperty("amount", SPAWN_FLUID_AMOUNT);
        fluidIngredient.addProperty("fluid", MODID + ":spawn_fluid_" + entity);
        ingredients.add(fluidIngredient);

        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        JsonObject result = new JsonObject();
        result.addProperty("id", "minecraft:" + entity + "_spawn_egg");
        results.add(result);
        json.add("results", results);

        return json;
    }

    @Override
    public String getName() {
        return "Mechanical Spawner Egg Filling Recipes";
    }
}
