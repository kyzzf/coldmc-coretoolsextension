package se.coldmc.typewriter.coretools

import org.bukkit.entity.Player
import ranet.coretools.CoreTools
import ranet.coretools.features.variables.UtilsVariables
import ranet.coretools.util.UtilsPlayer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes CoreTools PLAYER variables - the per-MMOProfiles-profile store.
 *
 * Writes go straight into the selected profile's PlayerData map, which is what CoreTools'
 * own `setVariable` does (ModifyVariables, 2026-11 jar). That keeps a write synchronous, so
 * a sibling Typewriter action reads the new value in the same tick, and it avoids the
 * MiniMessage parser that the public `change(...)` runs on every value.
 */
object CoreToolsBridge {
    private val log: Logger = Logger.getLogger("ColdMC-CoreTools")
    private var warnedMissing = HashSet<String>()

    private fun norm(variable: String) = variable.replace(".", "_").lowercase()

    fun read(player: Player, variable: String): Int {
        val raw = try {
            UtilsVariables.getProfile(player, norm(variable))
        } catch (t: Throwable) {
            null
        } ?: return 0
        return raw.trim().toDoubleOrNull()?.toInt() ?: 0
    }

    fun write(player: Player, variable: String, value: Int) {
        val key = norm(variable)
        try {
            val ct = CoreTools.getInstance()
            if (!ct.variables.variablesConfig.containsKey(key)) {
                if (warnedMissing.add(key)) {
                    log.warning("[ColdMC] CoreTools variable '$key' is not declared - fact write dropped")
                }
                return
            }
            // true = the selected PROFILE's uuid, false = the account (UtilsPlayer.getUUID)
            val profile = UtilsPlayer.getUUID(player, true) ?: return
            val data = ct.dataStorage.playerData[profile] ?: return
            data.variables[key] = value.toString()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "[ColdMC] CoreTools variable write failed for $key", t)
        }
    }
}
