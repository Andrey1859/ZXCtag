package ru.andrey1859.zxctag

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object ZXCtagMod : ModInitializer {
    const val MOD_ID = "zxctag"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ZXCCommand.register()
        HpDisplayTracker.register()
        LOGGER.info("ZXCtag loaded")
    }
}
