package net.kuma.create_extra_recipes.datagen;

import net.kuma.create_extra_recipes.Create_extra_recipes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = Create_extra_recipes.MODID)
public class DataGenerators {

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(true,
                new DyeableComponentsRecipeProvider(event.getGenerator().getPackOutput()));
    }
}
