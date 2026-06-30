package org.chatterjay.crafting_tracker;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.chatterjay.crafting_tracker.config.CTConfig;
import org.chatterjay.crafting_tracker.server.CraftTracker;
import org.chatterjay.crafting_tracker.server.CraftTrackerCommand;
import org.chatterjay.crafting_tracker.server.CraftTrackerNetwork;
import org.slf4j.Logger;

@Mod(Crafting_tracker.MODID)
public class Crafting_tracker {
    public static final String MODID = "crafting_tracker";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public Crafting_tracker(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, CTConfig.SPEC);

        modEventBus.addListener(RegisterPayloadHandlersEvent.class,
                (event) -> CraftTrackerNetwork.register(event));

        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
                (event) -> CraftTracker.onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                (event) -> CraftTrackerCommand.register(event.getDispatcher()));
    }
}
