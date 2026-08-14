package net.kuma.create_extra_recipes;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Create_extra_recipes.MODID)
public class Create_extra_recipes {
    public static final String MODID = "create_extra_recipes";
    public static final Logger LOGGER = LoggerFactory.getLogger(Create_extra_recipes.class);

    private static final String CLOTH_CONFIG_MODID = "cloth_config";

    public Create_extra_recipes(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        if (ModList.get().isLoaded(CLOTH_CONFIG_MODID)) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (minecraft, parent) -> ClothConfigScreen.build(parent));
        }

        NeoForge.EVENT_BUS.register(new RecipeOverrides());
    }
}
