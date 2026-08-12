package net.kuma.create_extra_recipes;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Create_extra_recipes.MODID)
public class Create_extra_recipes {
    public static final String MODID = "create_extra_recipes";
    public static final Logger LOGGER = LoggerFactory.getLogger(Create_extra_recipes.class);

    public Create_extra_recipes() {
        NeoForge.EVENT_BUS.register(new RecipeOverrides());
    }
}
