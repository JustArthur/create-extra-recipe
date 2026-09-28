package net.kuma.create_extra_recipes;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Create_extra_recipes.MODID)
public class Create_extra_recipes {
    public static final String MODID = "create_extra_recipes";
    public static final Logger LOGGER = LoggerFactory.getLogger(Create_extra_recipes.class);

    static final String CLOTH_CONFIG_MODID = "cloth_config";

    public Create_extra_recipes(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Only touch client-only classes (Screen, ClothConfigScreen) when running on the client.
        // Referencing them here unconditionally makes the RuntimeDistCleaner strip/fail this
        // class's bytecode on a dedicated server, even though the lambda is never invoked there.
        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded(CLOTH_CONFIG_MODID)) {
            ClientSetup.registerConfigScreen(modContainer);
        }

        NeoForge.EVENT_BUS.register(new RecipeOverrides());
    }
}