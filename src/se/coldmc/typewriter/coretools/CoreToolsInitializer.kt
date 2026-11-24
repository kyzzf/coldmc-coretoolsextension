package se.coldmc.typewriter.coretools

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import fr.phoenixdevt.profiles.event.ProfileSelectEvent
import fr.phoenixdevt.profiles.event.ProfileUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener

/** Starts the per-profile advancement swap when Typewriter loads (and on `tw reload`). */
@Singleton
class CoreToolsInitializer : Initializable {
    override suspend fun initialize() {
        val plugin = Bukkit.getPluginManager().getPlugin("Typewriter") ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            HandlerList.unregisterAll(ProfileListener)
            ProfileAdvancements.enable()
            Bukkit.getPluginManager().registerEvents(ProfileListener, plugin)
        })
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(ProfileListener)
    }
}

object ProfileListener : Listener {
    /** Every advancement a Typewriter quest grants has a `cm_adv_<id>` fact. */
    private fun questAdvancements(): Set<String> =
        Query.find<CoreToolsVariableFact>()
            .map { it.id }
            .filter { it.startsWith("cm_adv_") }
            .map { it.removePrefix("cm_adv_") }
            .toSet()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onUnload(event: ProfileUnloadEvent) {
        val player = event.player ?: return
        val profile = event.profile ?: return
        ProfileAdvancements.onUnload(player, profile.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSelect(event: ProfileSelectEvent) {
        val player = event.player ?: return
        val profile = event.profile ?: return
        ProfileAdvancements.onSelect(player, profile.uniqueId, ::questAdvancements)
        // UAA may finish loading the player after this (default-profile picks at join);
        // PremiumAdvancements then syncs from the swapped rows itself, and this is a backstop.
        val plugin = Bukkit.getPluginManager().getPlugin("Typewriter") ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { ProfileAdvancements.resync(player) }, 60L)
    }
}
