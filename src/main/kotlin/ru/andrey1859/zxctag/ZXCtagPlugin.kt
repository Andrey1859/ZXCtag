package ru.andrey1859.zxctag

import org.bukkit.plugin.java.JavaPlugin
import ru.andrey1859.zxctag.npc.MojangSkinService
import ru.andrey1859.zxctag.npc.NpcCommand
import ru.andrey1859.zxctag.npc.NpcManager
import ru.andrey1859.zxctag.npc.NpcPackets

class ZXCtagPlugin : JavaPlugin() {
    private lateinit var npcManager: NpcManager

    override fun onEnable() {
        instance = this
        listOf("zxc", "prefix").forEach { name ->
            getCommand(name)?.apply {
                setExecutor(ZXCCommand)
                tabCompleter = ZXCCommand
            }
        }

        val skinService = MojangSkinService()
        npcManager = NpcManager(this, NpcPackets(this), skinService)
        server.pluginManager.registerEvents(npcManager, this)
        val npcCommand = NpcCommand(this, npcManager)
        server.pluginManager.registerEvents(npcCommand, this)
        listOf("spawnnpc", "npcitem").forEach { name ->
            getCommand(name)?.apply {
                setExecutor(npcCommand)
                tabCompleter = npcCommand
            } ?: logger.severe("Команда '$name' не найдена в plugin.yml")
        }
        npcManager.load()
        logger.info("ZXCtag включён")
    }

    override fun onDisable() {
        if (::npcManager.isInitialized) npcManager.shutdown()
    }

    companion object {
        lateinit var instance: ZXCtagPlugin
            private set
    }
}
