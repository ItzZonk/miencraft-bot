package com.aquamix.drawbot.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> createConfigScreen(parent) }
    }
    
    private fun createConfigScreen(parent: Screen): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("config.aquamix.title"))
            .setSavingRunnable { ModConfig.save() }
        
        val entryBuilder = builder.entryBuilder()
        val config = ModConfig.data
        
        // Timing category
        val timing = builder.getOrCreateCategory(Text.translatable("config.aquamix.timing"))
        
        timing.addEntry(entryBuilder.startLongField(
            Text.translatable("config.aquamix.timing.menu_click_delay"),
            config.timing.menuClickDelay
        )
            .setDefaultValue(200)
            .setMin(50)
            .setMax(1000)
            .setSaveConsumer { config.timing.menuClickDelay = it }
            .build())
        
        timing.addEntry(entryBuilder.startLongField(
            Text.translatable("config.aquamix.timing.fly_reactivate_delay"),
            config.timing.flyReactivateDelay
        )
            .setDefaultValue(500)
            .setMin(100)
            .setMax(2000)
            .setSaveConsumer { config.timing.flyReactivateDelay = it }
            .build())
        
        // Navigation category
        val navigation = builder.getOrCreateCategory(Text.translatable("config.aquamix.navigation"))
        
        navigation.addEntry(entryBuilder.startIntField(
            Text.translatable("config.aquamix.navigation.flight_height"),
            config.navigation.flightHeight
        )
            .setDefaultValue(100)
            .setMin(64)
            .setMax(256)
            .setSaveConsumer { config.navigation.flightHeight = it }
            .build())
        
        navigation.addEntry(entryBuilder.startIntField(
            Text.translatable("config.aquamix.navigation.landing_offset"),
            config.navigation.landingOffset
        )
            .setDefaultValue(8)
            .setMin(0)
            .setMax(15)
            .setSaveConsumer { config.navigation.landingOffset = it }
            .build())
        
        return builder.build()
    }
}
