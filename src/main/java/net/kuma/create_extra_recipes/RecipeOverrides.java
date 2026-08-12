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

/**
 * Two independent recipe-patching mechanisms, merged into a single replaceRecipes call:
 *
 * 1. STATIC: netherite_ingot and netherite_upgrade_smithing_template are re-read from
 *    data/create_extra_recipes/recipe_overrides/ and injected verbatim. They aren't picked
 *    up by mechanism 2 below (netherite_upgrade_smithing_template would otherwise match it
 *    too - it's explicitly excluded from the dynamic scan to avoid double-processing).
 *
 * 2. DYNAMIC (trim duplication): any minecraft:crafting_shaped recipe - vanilla, NeoForge's own
 *    retagged version, or a third-party mod's - that duplicates an armor trim smithing template
 *    is detected structurally (see isTrimDuplicationRecipe) and has its majority/minority
 *    ingredient slots swapped in memory, without needing to know the mod or ingredients
 *    ahead of time.
 *
 * 3. DYNAMIC (log-to-wood buff): any minecraft:crafting_shaped recipe that assembles 4 of a
 *    single minecraft:logs-tagged item into a 2x2 square yielding 3 of some result is detected
 *    structurally (see isLogToWoodRecipe) and has its result count bumped to 4, without needing
 *    to know the mod, the wood variant, or the result item's name ahead of time.
 *
 * 4. DYNAMIC (raw ore block blasting): unlike the previous two dynamic mechanisms, this one has
 *    no existing recipe to detect - it scans the item registry itself for "raw_<metal>_block"
 *    items (see findRawBlockToMetalBlockCandidates) that don't already have a smelting/blasting
 *    recipe to their "<metal>_block" counterpart, and generates one. Works for vanilla and any
 *    third-party mod's raw ore blocks without a per-mod/per-metal list.
 *
 * All are computed from a PreparableReloadListener added via AddReloadListenerEvent, so they
 * see every other pack's recipes (including NeoForge's own) already loaded - NeoForge's
 * resource pack loads after ours in the pack stacking order, so a plain datapack-level
 * override loses that race every time. However, the actual replaceRecipes() application is
 * deferred to TagsUpdatedEvent (see onTagsUpdated): item tags are not guaranteed bound yet
 * during that listener's apply() phase, so a tag-based ingredient (e.g. NeoForge's own
 * c:gems/diamond replacement for vanilla's netherite/diamond trim recipes) would still resolve
 * to a placeholder rather than its real contents if read too early.
 */
public class RecipeOverrides {

    private static final String OVERRIDE_NAMESPACE = Create_extra_recipes.MODID;
    private static final String OVERRIDE_FOLDER = "recipe_overrides";
    private static final String TRIM_TEMPLATE_SUFFIX = "_armor_trim_smithing_template";

    private static final List<ResourceLocation> STATIC_OVERRIDE_IDS = List.of(
            ResourceLocation.withDefaultNamespace("netherite_ingot"),
            ResourceLocation.withDefaultNamespace("netherite_upgrade_smithing_template")
    );

    // Set at the end of our reload listener's apply() phase, consumed by onTagsUpdated once
    // tags are actually bound (see class javadoc point 2 below for why this hand-off exists).
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

    /**
     * Item tags (including NeoForge's own c: tags, which several mods - and NeoForge itself,
     * for some vanilla recipes - use instead of concrete items) are NOT guaranteed resolved
     * yet during a PreparableReloadListener's apply() phase: MinecraftServer only calls
     * ReloadableServerResources#updateRegistryTags() - which binds tags and fires this event -
     * AFTER every reload listener (ours included) has finished. Reading Ingredient.getItems()
     * on a tag-based ingredient before that point returns a single placeholder
     * minecraft:barrier item rather than the tag's real, still-unbound contents. So the actual
     * nerf work is deferred to here, where tags are guaranteed bound. CLIENT_PACKET_RECEIVED
     * fires client-side on connect and isn't relevant to server-side recipe mutation.
     */
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

    /**
     * True only if ALL of: the recipe is a crafting_shaped recipe, its result item's id
     * ends with "_armor_trim_smithing_template", AND one of its (non-empty, single-item)
     * ingredient slots refers back to that same result item (the recipe duplicates itself).
     * The item tag minecraft:trim_templates was considered as a detection signal instead,
     * but at least one real mod (more_armor_trims) ships its tag addition under
     * assets/minecraft/tags/item/trim_templates.json instead of data/, so it never loads -
     * relying on it would silently miss that mod's templates. The self-reference check does
     * not depend on any third party doing anything correctly.
     */
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

    /**
     * Swaps the ingredient assigned to the majority-count pattern slot (e.g. 7 cells, the
     * expensive material under vanilla/NeoForge convention) with the one assigned to the
     * minority-count slot (e.g. 1 cell), regardless of what those ingredients actually are.
     * Returns empty (and logs a warning) if the recipe's shape isn't the standard "1 template
     * slot + exactly 2 other distinct material ingredients with different counts" layout -
     * we never guess on an unrecognized shape.
     */
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

    /**
     * Resolves an ingredient (tag- or item-based) to the concrete items it currently matches
     * and returns an item-only Ingredient built from those. A no-op for ingredients that were
     * already item-based.
     */
    private static Ingredient toConcreteIngredient(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return Ingredient.of(Arrays.stream(items));
    }

    // --- Mechanism 3: dynamic log-to-wood buff detection ----------------------------------

    /**
     * True only if ALL of: the recipe is a crafting_shaped recipe, its pattern is exactly a
     * 2x2 square ("##"/"##"), all 4 pattern cells resolve to the same single-item ingredient
     * (no tags, no ingredient lists), that item belongs to minecraft:logs, and result.count
     * is exactly 3 (the vanilla Wood/Stripped Wood ratio we're nerfing back up to 4).
     * minecraft:logs was chosen over logs_that_burn: it's the broadest, most stable tag and
     * already covers stripped variants and third-party tree mods that follow vanilla
     * convention, without pulling in fuel-related semantics that aren't relevant here. No
     * check is made on the result item's own id/name - the pattern+ingredient+count triplet
     * is already a highly specific signal, and requiring a naming convention would reintroduce
     * an assumption about how third-party mods name their result items.
     */
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

    /**
     * Rebuilds the recipe identically except for result.count, bumped from 3 to 4. Nothing
     * else about the recipe (pattern, group, category, ingredients) is touched.
     */
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

    /**
     * Scans the item registry for "<namespace>:raw_<metal>_block" items that also have a
     * "<namespace>:<metal>_block" counterpart in the same namespace, and that aren't already
     * covered by an existing smelting/blasting recipe (existingCookingPairs, built from the
     * currently-loaded recipes so third-party recipes are respected and never duplicated).
     *
     * Preferred anchor is tag-based: the raw block belongs to some bound item tag whose path
     * starts with "storage_blocks/raw_" (the NeoForge/Common Conventions family, e.g.
     * c:storage_blocks/raw_copper) - mirrors how minecraft:logs anchors the wood mechanism.
     * If no such tag is bound for that item (some third-party mods only follow the naming
     * convention without tagging correctly), we fall back to the name pattern alone, but log a
     * WARN so a nerf silently relying on unreliable third-party naming stays visible in logs.
     *
     * Reading BuiltInRegistries.ITEM itself does not depend on tag binding and could run as
     * early as AddReloadListenerEvent - but getTagNames() per item does depend on tags being
     * bound, so like mechanism 2/3's ingredient resolution, this whole scan is only run from
     * onTagsUpdated (see that method's javadoc for why tags aren't safe before then).
     */
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

    /**
     * Builds the "<namespace>_raw_<metal>_block" blasting recipe id (namespaced by the source
     * mod up front, e.g. createextrarecipes:create_raw_zinc_block) rather than a bare
     * "raw_<metal>_block" id, so two mods that happen to add a metal of the same name never
     * collide on the generated recipe's id.
     */
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

    /**
     * Indexes every currently-loaded minecraft:smelting/minecraft:blasting recipe by its
     * (single-item ingredient, result item) pair, so findRawBlockToMetalBlockCandidates can skip
     * generating a duplicate when a mod already ships its own raw-block-to-block recipe.
     * Recipes with non-single-item ingredients (tags, multiple items) are ignored here - they
     * can never exactly match a candidate's single concrete ingredient item anyway.
     */
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

    /**
     * Scans NeoForge's OXIDIZABLES data map - the same registry-driven mechanism the game uses
     * to drive natural weathering and axe scraping, which any mod can add entries to - for
     * every (block, next block) pair (read here via DataMapHooks.INVERSE_OXIDIZABLES_DATAMAP,
     * which already merges the data map with vanilla's legacy static map as a fallback), and
     * generates a create:filling recipe (250mB water) turning one stage's item into the next's.
     * No per-mod/per-family list to maintain: a new mod's copper block registered into this
     * data map is covered automatically, and the vanilla quirks that don't follow simple name
     * patterns (e.g. copper_block -> exposed_copper) are already resolved correctly because
     * they come from the game's own registered mapping rather than a name guess.
     * Blocks with no BlockItem (asItem() == AIR) are skipped, as are pairs that already have a
     * create:filling recipe (existingFillingPairs), so a mod shipping its own recipe is never
     * duplicated.
     */
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

            JsonObject json = buildFillingRecipeJson(fromId, toId);
            try {
                Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
                candidates.add(new RecipeHolder<>(recipeId, recipe));
            } catch (RuntimeException e) {
                Create_extra_recipes.LOGGER.error("Failed to build copper oxidation filling recipe {} -> {}", fromId, toId, e);
            }
        }

        return candidates;
    }

    private static JsonObject buildFillingRecipeJson(ResourceLocation ingredientItemId, ResourceLocation resultItemId) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "create:filling");

        JsonArray ingredients = new JsonArray();
        JsonObject itemIngredient = new JsonObject();
        itemIngredient.addProperty("item", ingredientItemId.toString());
        ingredients.add(itemIngredient);

        JsonObject fluidIngredient = new JsonObject();
        fluidIngredient.addProperty("type", "neoforge:single");
        fluidIngredient.addProperty("amount", COPPER_OXIDATION_WATER_AMOUNT);
        fluidIngredient.addProperty("fluid", "minecraft:water");
        ingredients.add(fluidIngredient);

        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        JsonObject result = new JsonObject();
        result.addProperty("id", resultItemId.toString());
        results.add(result);
        json.add("results", results);

        return json;
    }

    /**
     * Indexes every currently-loaded create:filling recipe by its (single-item ingredient,
     * result item) pair, so findCopperOxidationCandidates can skip generating a duplicate when
     * a mod already ships its own filling recipe for that pair. Identified by recipe type id
     * rather than an instanceof check, so this never needs a compile-time dependency on
     * Create's internal recipe class.
     */
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

    // --- Merge & apply ---------------------------------------------------------------------

    private static void applyOverrides(RecipeManager recipeManager, HolderLookup.Provider registries, List<RecipeHolder<?>> staticOverrides) {
        List<RecipeHolder<?>> merged = new ArrayList<>();
        int nerfedCount = 0;
        int buffedCount = 0;

        Set<ItemPair> existingCookingPairs = indexExistingCookingPairs(recipeManager, registries);

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (STATIC_OVERRIDE_IDS.contains(holder.id())) {
                continue; // replaced below by the statically-loaded version
            }

            if (holder.value() instanceof ShapedRecipe shaped) {
                ItemStack result = shaped.getResultItem(registries);
                if (!result.isEmpty() && isTrimDuplicationRecipe(shaped, result)) {
                    Optional<ShapedRecipe> nerfed = nerf(holder.id(), shaped, result);
                    if (nerfed.isPresent()) {
                        merged.add(new RecipeHolder<>(holder.id(), nerfed.get()));
                        nerfedCount++;
                        continue;
                    }
                    // skip case already logged a warning in nerf(); fall through and keep original
                }
                if (!result.isEmpty() && isLogToWoodRecipe(shaped, result)) {
                    merged.add(new RecipeHolder<>(holder.id(), buffWoodCount(shaped, result)));
                    buffedCount++;
                    continue;
                }
            }

            merged.add(holder);
        }

        merged.addAll(staticOverrides);

        List<RawBlockCandidate> rawBlockCandidates = findRawBlockToMetalBlockCandidates(existingCookingPairs);
        for (RawBlockCandidate candidate : rawBlockCandidates) {
            merged.add(buildRawBlockBlastingRecipe(candidate));
            Create_extra_recipes.LOGGER.info(
                    "Generated raw ore block blasting recipe: {} -> {}", candidate.rawBlockId(), candidate.metalBlockId());
        }

        Set<ItemPair> existingFillingPairs = indexExistingFillingPairs(recipeManager, registries);
        List<RecipeHolder<?>> copperOxidationCandidates = findCopperOxidationCandidates(registries, existingFillingPairs);
        for (RecipeHolder<?> candidate : copperOxidationCandidates) {
            merged.add(candidate);
            Create_extra_recipes.LOGGER.info("Generated copper oxidation filling recipe: {}", candidate.id());
        }

        if (nerfedCount == 0 && buffedCount == 0 && staticOverrides.isEmpty()
                && rawBlockCandidates.isEmpty() && copperOxidationCandidates.isEmpty()) {
            return;
        }

        recipeManager.replaceRecipes(merged);
        Create_extra_recipes.LOGGER.info(
                "create_extra_recipes: applied {} static recipe override(s), {} dynamically-detected trim template nerf(s), "
                        + "{} dynamically-detected log-to-wood buff(s), {} dynamically-generated raw ore block blasting recipe(s), "
                        + "and {} dynamically-generated copper oxidation filling recipe(s)",
                staticOverrides.size(), nerfedCount, buffedCount, rawBlockCandidates.size(), copperOxidationCandidates.size());
    }
}
