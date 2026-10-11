package ru.andrey1859.zxctag

import org.bukkit.plugin.java.JavaPlugin

class ZXCtagPlugin : JavaPlugin() {
    override fun onEnable() {
        instance = this
        listOf("zxc", "prefix").forEach { name ->
            getCommand(name)?.apply {
                setExecutor(ZXCCommand)
                tabCompleter = ZXCCommand
            }
        }
        logger.info("ZXCtag включён")
    }

    companion object {
        lateinit var instance: ZXCtagPlugin
            private set
    }
}
