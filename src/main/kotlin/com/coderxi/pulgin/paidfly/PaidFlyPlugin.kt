package com.coderxi.pulgin.paidfly

import com.coderxi.pulgin.paidfly.command.PaidFlyCmdExecutor
import com.coderxi.pulgin.paidfly.command.PaidFlyTabCompleter
import com.coderxi.pulgin.paidfly.expansion.PaidFlyPAPIExpansion
import com.coderxi.pulgin.paidfly.service.*
import com.coderxi.pulgin.paidfly.utils.Localizer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class PaidFlyPlugin : JavaPlugin() {

    companion object {
        val plugin: PaidFlyPlugin get() = getPlugin(PaidFlyPlugin::class.java)
    }

    lateinit var localizer: Localizer
    private val services = listOf(PaidFlyService, PayService)

    override fun onLoad() {
        saveDefaultConfig()
        reloadConfig()
        localizer = Localizer.of(plugin) { "lang/" + plugin.config.getString("Language") }
    }

    override fun onEnable() {
        localizer.reload()
        services.forEach { it.init() }
        getCommand("paidfly")?.setExecutor(PaidFlyCmdExecutor)
        getCommand("paidfly")?.tabCompleter = PaidFlyTabCompleter()
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            PaidFlyPAPIExpansion().register()
        }
    }

    override fun onDisable() {
        PaidFlyService.shutdown()
    }

    fun reload() {
        reloadConfig()
        localizer.reload()
        services.forEach { it.reload() }
    }
}
