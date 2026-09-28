package net.kuma.create_extra_recipes;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

final class ClientSetup {

    private ClientSetup() {
    }

    static void registerConfigScreen(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> ClothConfigScreen.build(parent));
    }
}