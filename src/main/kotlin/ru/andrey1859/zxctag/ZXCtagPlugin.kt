package ru.andrey1859.zxctag

import org.bukkit.plugin.java.JavaPlugin

class ZXCtagPlugin : JavaPlugin() {
    override fun onEnable() {
        instance = this
        HpDisplayTracker.start(this)
        getCommand("zxc")?.apply {
            setExecutor(ZXCCommand)
            tabCompleter = ZXCCommand
        }
        logger.info("ZXCtag включён")
    }

    override fun onDisable() {
        HpDisplayTracker.stop()
    }

    companion object {
        lateinit var instance: ZXCtagPlugin
            private set
    }
}
