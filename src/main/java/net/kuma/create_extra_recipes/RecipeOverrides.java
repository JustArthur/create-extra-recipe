package net.kuma.create_extra_recipes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecipeOverrides {

    private static final String OVERRIDE_NAMESPACE = Create_extra_recipes.MODID;
    private static final String OVERRIDE_FOLDER = "recipe_overrides";
    private static final String TRIM_TEMPLATE_SUFFIX = "_armor_trim_smithing_template";

    private static final List<ResourceLocation> STATIC_OVERRIDE_IDS = List.of(
            ResourceLocation.withDefaultNamespace("netherite_ingot"),
            ResourceLocation.withDefaultNamespace("netherite_upgrade_smithing_template")
    );

    private volatile PendingApply pending;

    private record PendingApply(RecipeManager recipeManager, HolderLookup.Provider registries,
                                 List<RecipeHolder<?>> staticOverrides) {
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        HolderLookup.Provider registries = event.getRegistryAccess();
        RecipeManager recipeManager = event.getServerResources().getRecipeManager();

        event.addListener((barrier, resourceManager, prepareProfiler, applyProfiler, prepareExecutor, applyExecutor) ->
                CompletableFuture.supplyAsync(() -> loadStaticOverrides(resourceManager, registries), prepareExecutor)
                        .thenCompose(barrier::wait)
                        .thenAcceptAsync(staticOverrides ->
                                pending = new PendingApply(recipeManager, registries, staticOverrides), applyExecutor)
        );
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        PendingApply toApply = pending;
        pending = null;
        if (toApply == null) {
            return;
        }
        applyOverrides(toApply.recipeManager(), toApply.registries(), toApply.staticOverrides());
    }

    // --- Mechanism 1: static, hand-authored overrides -----------------------------------

    private static List<RecipeHolder<?>> loadStaticOverrides(ResourceManager resourceManager, HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        List<RecipeHolder<?>> loaded = new ArrayList<>();

        for (ResourceLocation targetId : STATIC_OVERRIDE_IDS) {
            ResourceLocation overridePath = ResourceLocation.fromNamespaceAndPath(
                    OVERRIDE_NAMESPACE, OVERRIDE_FOLDER + "/" + targetId.getPath() + ".json");

            Optional<Resource> resource = resourceManager.getResource(overridePath);
            if (resource.isEmpty()) {
                Create_extra_recipes.LOGGER.error("Missing recipe override resource: {}", overridePath);
                continue;
            }

            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
                loaded.add(new RecipeHolder<>(targetId, recipe));
            } catch (IOException | RuntimeException e) {
                Create_extra_recipes.LOGGER.error("Failed to parse recipe override {}", overridePath, e);
            }
        }

        return loaded;
    }

    // --- Mechanism 2: dynamic detection + in-memory nerf ---------------------------------
    private static boolean isTrimDuplicationRecipe(ShapedRecipe recipe, ItemStack result) {
        ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (!resultId.getPath().endsWith(TRIM_TEMPLATE_SUFFIX)) {
            return false;
        }
        for (Ingredient ingredient : recipe.pattern.ingredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] items = ingredient.getItems();
            if (items.length == 1 && items[0].is(result.getItem())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<ShapedRecipe> nerf(ResourceLocation id, ShapedRecipe recipe, ItemStack result) {
        ShapedRecipePattern pattern = recipe.pattern;
        NonNullList<Ingredient> ingredients = pattern.ingredients();

        Map<Ingredient, Integer> materialCounts = new LinkedHashMap<>();
        boolean templateSlotFound = false;

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] items = ingredient.getItems();
            if (items.length == 1 && items[0].is(result.getItem())) {
                templateSlotFound = true;
                continue;
            }
            materialCounts.merge(ingredient, 1, Integer::sum);
        }

        if (!templateSlotFound) {
            Create_extra_recipes.LOGGER.warn("Skipping {}: no self-referencing template slot found (unexpected)", id);
            return Optional.empty();
        }
        if (materialCounts.size() != 2) {
            Create_extra_recipes.LOGGER.warn(
                    "Skipping {}: expected exactly 2 distinct material ingredients, found {} (non-standard layout)",
                    id, materialCounts.size());
            return Optional.empty();
        }

        var entries = materialCounts.entrySet().iterator();
        Map.Entry<Ingredient, Integer> first = entries.next();
        Map.Entry<Ingredient, Integer> second = entries.next();

        if (first.getValue().intValue() == second.getValue().intValue()) {
            Create_extra_recipes.LOGGER.warn(
                    "Skipping {}: both material ingredients occupy {} slots each, cannot determine majority/minority",
                    id, first.getValue());
            return Optional.empty();
        }

        Ingredient majority = first.getValue() > second.getValue() ? first.getKey() : second.getKey();
        Ingredient minority = first.getValue() > second.getValue() ? second.getKey() : first.getKey();
        int majorityCount = Math.max(first.getValue(), second.getValue());
        int minorityCount = Math.min(first.getValue(), second.getValue());

        // Some mods (including NeoForge's own vanilla recipe replacements) express these
        // material ingredients as tags (e.g. c:gems/diamond) rather than concrete items.
        // Resolving to the items the tag currently matches - and swapping THOSE - keeps the
        // result immune to the recipe re-serializing as a tag reference, matching how the old
        // static JSON overrides hardcoded concrete items directly. This only resolves correctly
        // because applyOverrides() (and therefore nerf()) is invoked post-TagsUpdatedEvent -
        // see onTagsUpdated's javadoc.
        Ingredient majorityConcrete = toConcreteIngredient(majority);
        Ingredient minorityConcrete = toConcreteIngredient(minority);

        NonNullList<Ingredient> swapped = NonNullList.withSize(ingredients.size(), Ingredient.EMPTY);
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.equals(majority)) {
                swapped.set(i, minorityConcrete);
            } else if (ingredient.equals(minority)) {
                swapped.set(i, majorityConcrete);
            } else {
                swapped.set(i, ingredient);
            }
        }

        ShapedRecipePattern newPattern = new ShapedRecipePattern(pattern.width(), pattern.height(), swapped, Optional.empty());
        ShapedRecipe newRecipe = new ShapedRecipe(recipe.getGroup(), recipe.category(), newPattern, result, recipe.showNotification());

        Create_extra_recipes.LOGGER.info(
                "Nerfed trim duplication recipe {} (mod: {}): material that needed {}x now needs {}x, and vice versa",
                id, id.getNamespace(), majorityCount, minorityCount);

        return Optional.of(newRecipe);
    }

    private static Ingredient toConcreteIngredient(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return Ingredient.of(Arrays.stream(items));
    }

    // --- Mechanism 3: dynamic log-to-wood buff detection ----------------------------------

    private static boolean isLogToWoodRecipe(ShapedRecipe recipe, ItemStack result) {
        if (result.getCount() != 3) {
            return false;
        }
        ShapedRecipePattern pattern = recipe.pattern;
        if (pattern.width() != 2 || pattern.height() != 2) {
            return false;
        }
        NonNullList<Ingredient> ingredients = pattern.ingredients();
        if (ingredients.size() != 4) {
            return false;
        }

        Ingredient first = ingredients.get(0);
        if (first.isEmpty()) {
            return false;
        }
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.equals(first)) {
                return false;
            }
        }

        ItemStack[] items = first.getItems();
        if (items.length != 1) {
            return false;
        }

        boolean isLog = items[0].is(ItemTags.LOGS);
        if (!isLog) {
            Create_extra_recipes.LOGGER.warn(
                    "Recipe for {} looks like a 2x2 single-ingredient 3x craft but its ingredient ({}) "
                            + "is not tagged minecraft:logs - skipping log-to-wood buff",
                    BuiltInRegistries.ITEM.getKey(result.getItem()), BuiltInRegistries.ITEM.getKey(items[0].getItem()));
            return false;
        }

        return true;
    }

    private static ShapedRecipe buffWoodCount(ShapedRecipe recipe, ItemStack result) {
        ItemStack buffedResult = result.copy();
        buffedResult.setCount(4);
        return new ShapedRecipe(recipe.getGroup(), recipe.category(), recipe.pattern, buffedResult, recipe.showNotification());
    }

    // --- Mechanism 4: dynamic raw-ore-block blasting recipe generation ---------------------

    private static final Pattern RAW_BLOCK_NAME_PATTERN = Pattern.compile("^raw_(.+)_block$");
    private static final String RAW_STORAGE_BLOCK_TAG_PREFIX = "storage_blocks/raw_";
    private static final int RAW_BLOCK_BLASTING_COOKING_TIME = 300; // vanilla blasting time for raw ores, half of smelting's 200
    private static final float RAW_BLOCK_BLASTING_EXPERIENCE = 3f; // matches vanilla raw ore item blasting/smelting XP

    private record RawBlockCandidate(ResourceLocation rawBlockId, Item rawBlockItem, ResourceLocation metalBlockId, Item metalBlockItem) {
    }

    private static List<RawBlockCandidate> findRawBlockToMetalBlockCandidates(Set<ItemPair> existingCookingPairs) {
        List<RawBlockCandidate> candidates = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation rawBlockId = BuiltInRegistries.ITEM.getKey(item);
            Matcher matcher = RAW_BLOCK_NAME_PATTERN.matcher(rawBlockId.getPath());
            if (!matcher.matches()) {
                continue;
            }
            String metal = matcher.group(1);

            ResourceLocation metalBlockId = ResourceLocation.fromNamespaceAndPath(rawBlockId.getNamespace(), metal + "_block");
            Item metalBlockItem = BuiltInRegistries.ITEM.getOptional(metalBlockId).orElse(null);
            if (metalBlockItem == null) {
                Create_extra_recipes.LOGGER.warn(
                        "Found raw ore block {} but no matching metal block {} in the same namespace - skipping",
                        rawBlockId, metalBlockId);
                continue;
            }

            if (existingCookingPairs.contains(new ItemPair(item, metalBlockItem))) {
                continue; // a smelting/blasting recipe for this exact pair already exists - don't duplicate it
            }

            if (!hasRawStorageBlockTag(item)) {
                Create_extra_recipes.LOGGER.warn(
                        "Raw ore block {} has no bound tag under c:{}* - generating its blasting recipe from name pattern alone",
                        rawBlockId, RAW_STORAGE_BLOCK_TAG_PREFIX);
            }

            candidates.add(new RawBlockCandidate(rawBlockId, item, metalBlockId, metalBlockItem));
        }

        return candidates;
    }

    private static boolean hasRawStorageBlockTag(Item item) {
        return BuiltInRegistries.ITEM.getTagNames()
                .filter(tag -> tag.location().getPath().startsWith(RAW_STORAGE_BLOCK_TAG_PREFIX))
                .anyMatch(tag -> item.builtInRegistryHolder().is(tag));
    }

    private static RecipeHolder<BlastingRecipe> buildRawBlockBlastingRecipe(RawBlockCandidate candidate) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OVERRIDE_NAMESPACE,
                candidate.rawBlockId().getNamespace() + "_" + candidate.rawBlockId().getPath());

        BlastingRecipe recipe = new BlastingRecipe(
                "",
                CookingBookCategory.MISC,
                Ingredient.of(candidate.rawBlockItem()),
                new ItemStack(candidate.metalBlockItem()),
                RAW_BLOCK_BLASTING_EXPERIENCE,
                RAW_BLOCK_BLASTING_COOKING_TIME);

        return new RecipeHolder<>(id, recipe);
    }

    private record ItemPair(Item ingredient, Item result) {
    }

    private static Set<ItemPair> indexExistingCookingPairs(RecipeManager recipeManager, HolderLookup.Provider registries) {
        Set<ItemPair> pairs = new HashSet<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) {
                continue;
            }
            ItemStack[] items = cooking.getIngredients().get(0).getItems();
            if (items.length != 1) {
                continue;
            }
            ItemStack result = cooking.getResultItem(registries);
            if (result.isEmpty()) {
                continue;
            }
            pairs.add(new ItemPair(items[0].getItem(), result.getItem()));
        }
        return pairs;
    }

    // --- Mechanism 5: dynamic copper oxidation filling recipe generation ------------------

    private static final ResourceLocation FILLING_RECIPE_TYPE_ID = ResourceLocation.fromNamespaceAndPath("create", "filling");
    private static final int COPPER_OXIDATION_WATER_AMOUNT = 250;

    private static List<RecipeHolder<?>> findCopperOxidationCandidates(
            HolderLookup.Provider registries, Set<ItemPair> existingFillingPairs) {
        List<RecipeHolder<?>> candidates = new ArrayList<>();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        for (Map.Entry<Block, Block> entry : DataMapHooks.INVERSE_OXIDIZABLES_DATAMAP.entrySet()) {
            // entry is (afterStage -> beforeStage): the recipe goes the other way, before -> after
            Item fromItem = entry.getValue().asItem();
            Item toItem = entry.getKey().asItem();
            if (fromItem == Items.AIR || toItem == Items.AIR) {
                continue;
            }
            if (existingFillingPairs.contains(new ItemPair(fromItem, toItem))) {
                continue;
            }

            ResourceLocation fromId = BuiltInRegistries.ITEM.getKey(fromItem);
            ResourceLocation toId = BuiltInRegistries.ITEM.getKey(toItem);
            ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(OVERRIDE_NAMESPACE,
                    "copper_oxidation/" + fromId.getNamespace() + "_" + fromId.getPath());

            JsonObject json = buildFillingRecipeJson(fromId, "minecraft:water", COPPER_OXIDATION_WATER_AMOUNT, toId);
            try {
                Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
                candidates.add(new RecipeHolder<>(recipeId, recipe));
            } catch (RuntimeException e) {
                Create_extra_recipes.LOGGER.error("Failed to build copper oxidation filling recipe {} -> {}", fromId, toId, e);
            }
        }

        return candidates;
    }

    private static JsonObject buildFillingRecipeJson(ResourceLocation ingredientItemId, String fluidId, int fluidAmount, ResourceLocation resultItemId) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "create:filling");

        JsonArray ingredients = new JsonArray();
        JsonObject itemIngredient = new JsonObject();
        itemIngredient.addProperty("item", ingredientItemId.toString());
        ingredients.add(itemIngredient);

        JsonObject fluidIngredient = new JsonObject();
        fluidIngredient.addProperty("type", "neoforge:single");
        fluidIngredient.addProperty("amount", fluidAmount);
        fluidIngredient.addProperty("fluid", fluidId);
        ingredients.add(fluidIngredient);

        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        JsonObject result = new JsonObject();
        result.addProperty("id", resultItemId.toString());
        results.add(result);
        json.add("results", results);

        return json;
    }

    private static Set<ItemPair> indexExistingFillingPairs(RecipeManager recipeManager, HolderLookup.Provider registries) {
        Set<ItemPair> pairs = new HashSet<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!FILLING_RECIPE_TYPE_ID.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) {
                continue;
            }
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.size() != 1) {
                continue;
            }
            ItemStack[] items = ingredients.get(0).getItems();
            if (items.length != 1) {
                continue;
            }
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) {
                continue;
            }
            pairs.add(new ItemPair(items[0].getItem(), result.getItem()));
        }
        return pairs;
    }

    // --- Mechanism 6: dynamic mechanical spawn egg filling recipe generation ---------------

    private static final String MECHANICAL_SPAWNER_MODID = "create_mechanical_spawner";
    private static final int SPAWN_EGG_FLUID_AMOUNT = 250;

    private static final List<String> MECHANICAL_SPAWNER_ENTITIES = List.of(
            "bat", "bee", "blaze", "chicken", "cow", "creeper", "drowned", "enderman", "evoker",
            "fox", "ghast", "horse", "magma_cube", "panda", "parrot", "pig", "piglin", "rabbit",
            "skeleton", "slime", "spider", "villager", "witch", "wither_skeleton", "wither", "wolf", "zombie"
    );

    private static List<RecipeHolder<?>> findMechanicalSpawnEggCandidates(
            HolderLookup.Provider registries, Set<ItemPair> existingFillingPairs) {
        if (!ModList.get().isLoaded(MECHANICAL_SPAWNER_MODID)) {
            return List.of();
        }

        List<RecipeHolder<?>> candidates = new ArrayList<>();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        Item egg = Items.EGG;
        for (String entity : MECHANICAL_SPAWNER_ENTITIES) {
            ResourceLocation spawnEggId = ResourceLocation.withDefaultNamespace(entity + "_spawn_egg");
            Item spawnEggItem = BuiltInRegistries.ITEM.getOptional(spawnEggId).orElse(null);
            if (spawnEggItem == null) {
                continue; // no vanilla spawn egg for this entity in the current registry - skip silently
            }
            if (existingFillingPairs.contains(new ItemPair(egg, spawnEggItem))) {
                continue;
            }

            ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(OVERRIDE_NAMESPACE,
                    "mechanical_spawn_egg/" + entity);
            String fluidId = MECHANICAL_SPAWNER_MODID + ":spawn_fluid_" + entity;

            JsonObject json = buildFillingRecipeJson(
                    ResourceLocation.withDefaultNamespace("egg"), fluidId, SPAWN_EGG_FLUID_AMOUNT, spawnEggId);
            try {
                Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
                candidates.add(new RecipeHolder<>(recipeId, recipe));
            } catch (RuntimeException e) {
                Create_extra_recipes.LOGGER.error("Failed to build mechanical spawn egg filling recipe for {}", entity, e);
            }
        }

        return candidates;
    }

    // --- Merge & apply ---------------------------------------------------------------------

    private static void applyOverrides(RecipeManager recipeManager, HolderLookup.Provider registries, List<RecipeHolder<?>> staticOverrides) {
        List<RecipeHolder<?>> merged = new ArrayList<>();
        int nerfedCount = 0;
        int buffedCount = 0;

        boolean nerfEnabled = Config.ENABLE_SMITHING_TEMPLATE_NERF.get();
        boolean woodBuffEnabled = Config.ENABLE_WOOD_BUFF.get();
        boolean rawOreBlastingEnabled = Config.ENABLE_RAW_ORE_BLASTING.get();
        boolean copperOxidationEnabled = Config.ENABLE_COPPER_OXIDATION_FILLING.get();
        boolean mechanicalSpawnEggEnabled = Config.ENABLE_MECHANICAL_SPAWN_EGG.get();

        Set<ItemPair> existingCookingPairs = indexExistingCookingPairs(recipeManager, registries);

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (STATIC_OVERRIDE_IDS.contains(holder.id())) {
                continue; // replaced below by the statically-loaded version
            }

            if (holder.value() instanceof ShapedRecipe shaped) {
                ItemStack result = shaped.getResultItem(registries);
                if (nerfEnabled && !result.isEmpty() && isTrimDuplicationRecipe(shaped, result)) {
                    Optional<ShapedRecipe> nerfed = nerf(holder.id(), shaped, result);
                    if (nerfed.isPresent()) {
                        merged.add(new RecipeHolder<>(holder.id(), nerfed.get()));
                        nerfedCount++;
                        continue;
                    }
                    // skip case already logged a warning in nerf(); fall through and keep original
                }
                if (woodBuffEnabled && !result.isEmpty() && isLogToWoodRecipe(shaped, result)) {
                    merged.add(new RecipeHolder<>(holder.id(), buffWoodCount(shaped, result)));
                    buffedCount++;
                    continue;
                }
            }

            merged.add(holder);
        }

        merged.addAll(staticOverrides);

        List<RawBlockCandidate> rawBlockCandidates = rawOreBlastingEnabled
                ? findRawBlockToMetalBlockCandidates(existingCookingPairs)
                : List.of();
        for (RawBlockCandidate candidate : rawBlockCandidates) {
            merged.add(buildRawBlockBlastingRecipe(candidate));
            Create_extra_recipes.LOGGER.info(
                    "Generated raw ore block blasting recipe: {} -> {}", candidate.rawBlockId(), candidate.metalBlockId());
        }

        Set<ItemPair> existingFillingPairs = indexExistingFillingPairs(recipeManager, registries);

        List<RecipeHolder<?>> copperOxidationCandidates = copperOxidationEnabled
                ? findCopperOxidationCandidates(registries, existingFillingPairs)
                : List.of();
        for (RecipeHolder<?> candidate : copperOxidationCandidates) {
            merged.add(candidate);
            Create_extra_recipes.LOGGER.info("Generated copper oxidation filling recipe: {}", candidate.id());
        }

        List<RecipeHolder<?>> mechanicalSpawnEggCandidates = mechanicalSpawnEggEnabled
                ? findMechanicalSpawnEggCandidates(registries, existingFillingPairs)
                : List.of();
        for (RecipeHolder<?> candidate : mechanicalSpawnEggCandidates) {
            merged.add(candidate);
            Create_extra_recipes.LOGGER.info("Generated mechanical spawn egg filling recipe: {}", candidate.id());
        }

        if (nerfedCount == 0 && buffedCount == 0 && staticOverrides.isEmpty() && rawBlockCandidates.isEmpty()
                && copperOxidationCandidates.isEmpty() && mechanicalSpawnEggCandidates.isEmpty()) {
            return;
        }

        recipeManager.replaceRecipes(merged);
        Create_extra_recipes.LOGGER.info(
                "create_extra_recipes: applied {} static recipe override(s), {} dynamically-detected trim template nerf(s) "
                        + "(enabled={}), {} dynamically-detected log-to-wood buff(s) (enabled={}), {} dynamically-generated "
                        + "raw ore block blasting recipe(s) (enabled={}), {} dynamically-generated copper oxidation filling "
                        + "recipe(s) (enabled={}), and {} dynamically-generated mechanical spawn egg filling recipe(s) (enabled={})",
                staticOverrides.size(),
                nerfedCount, nerfEnabled,
                buffedCount, woodBuffEnabled,
                rawBlockCandidates.size(), rawOreBlastingEnabled,
                copperOxidationCandidates.size(), copperOxidationEnabled,
                mechanicalSpawnEggCandidates.size(), mechanicalSpawnEggEnabled);
    }
}
