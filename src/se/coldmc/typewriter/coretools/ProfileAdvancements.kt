package se.coldmc.typewriter.coretools

import com.fren_gor.ultimateAdvancementAPI.AdvancementPlugin
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/**
 * PremiumAdvancements progress per MMOProfiles profile.
 *
 * PremiumAdvancements 3.1 keys everything by the Bukkit (account) UUID, and its database is
 * its only truth: it never caches completions, and on every UAA load it grants/revokes the
 * client display to match the database (o.ff.onPlayerLoadCompleted). So a profile's progress
 * is kept in the SAME database under the PROFILE's uuid, and swapped into the account rows
 * when that profile is selected.
 *
 * Account-scoped triggers (JOIN, PLAYTIME, LOGIN_STREAK) are never swapped: they measure the
 * account, and their rewards are already held on the account and paid to one profile.
 *
 * PremiumAdvancements is obfuscated, so its DatabaseManager (o.n, field `b` of the main class)
 * is reached by reflection on exact signatures. If any is missing (a PAdv update), this whole
 * part switches itself off and says so in the log - progress then stays per account, as before.
 */
object ProfileAdvancements {
    private val log: Logger = Logger.getLogger("ColdMC-CoreTools")

    /** player_data keys - under the ACCOUNT uuid unless noted. */
    private const val OWNER = "coldmc_profile_owner"     // profile uuid whose rows are live
    private const val SNAPSHOT = "coldmc_profile_snapshot" // under the PROFILE uuid: has a snapshot
    private val ACCOUNT_TRIGGERS = setOf("JOIN", "PLAYTIME", "LOGIN_STREAK")

    private class Db(
        val target: Any,
        val complete: Method, val uncomplete: Method, val completed: Method,
        val setProgress: Method, val delProgress: Method, val progress: Method,
        val setData: Method, val getData: Method,
    )

    private var db: Db? = null
    private var padv: Plugin? = null
    private var accountScoped: Set<String> = emptySet()
    private var disabledReason: String? = null

    fun enable() {
        db = null; disabledReason = null
        val plugin = Bukkit.getPluginManager().getPlugin("PremiumAdvancements")
        if (plugin == null || !plugin.isEnabled) return off("PremiumAdvancements is not enabled")
        padv = plugin
        try {
            val field = plugin.javaClass.getField("b")
            val manager = field.get(plugin) ?: return off("PremiumAdvancements has no database yet")
            val c = manager.javaClass
            fun m(name: String, vararg types: Class<*>) = c.getMethod(name, *types)
            val U = UUID::class.java; val S = String::class.java; val I = Int::class.javaPrimitiveType!!
            db = Db(
                manager,
                complete = m("a", U, S), uncomplete = m("b", U, S), completed = m("a", U),
                setProgress = m("a", U, S, I), delProgress = m("d", U, S), progress = m("b", U),
                setData = m("a", U, S, S), getData = m("e", U, S),
            )
        } catch (t: Throwable) {
            return off("PremiumAdvancements' database API changed (${t.javaClass.simpleName}: ${t.message})")
        }
        accountScoped = readAccountScoped(plugin)
        log.info("[ColdMC] Advancements are per profile (${accountScoped.size} account-wide: JOIN/PLAYTIME/LOGIN_STREAK)")
    }

    private fun off(reason: String) {
        disabledReason = reason; db = null
        log.warning("[ColdMC] Per-profile advancements are OFF: $reason")
    }

    private fun readAccountScoped(plugin: Plugin): Set<String> {
        val dir = File(plugin.dataFolder, "Advancements")
        val out = HashSet<String>()
        dir.listFiles { f -> f.name.endsWith(".yml") }?.forEach { f ->
            val y = YamlConfiguration.loadConfiguration(f)
            val advs = y.getConfigurationSection("advancements") ?: return@forEach
            for (id in advs.getKeys(false)) {
                val trig = advs.getString("$id.trigger")?.uppercase() ?: continue
                if (trig in ACCOUNT_TRIGGERS) out += id
            }
        }
        return out
    }

    // ---------------------------------------------------------------- raw database helpers
    @Suppress("UNCHECKED_CAST")
    private fun Db.done(u: UUID): List<String> = (completed.invoke(target, u) as? List<String>) ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    private fun Db.prog(u: UUID): Map<String, Int> = (progress.invoke(target, u) as? Map<String, Int>) ?: emptyMap()
    private fun Db.data(u: UUID, k: String): String? = getData.invoke(target, u, k) as String?
    private fun Db.put(u: UUID, k: String, v: String) { setData.invoke(target, u, k, v) }

    /** Replace `to`'s per-profile rows with `from`'s, leaving account-scoped ids alone. */
    private fun Db.copyRows(from: UUID, to: UUID) {
        for (id in done(to)) if (id !in accountScoped) uncomplete.invoke(target, to, id)
        for (id in prog(to).keys) if (id !in accountScoped) delProgress.invoke(target, to, id)
        for (id in done(from)) if (id !in accountScoped) complete.invoke(target, to, id)
        for ((id, v) in prog(from)) if (id !in accountScoped) setProgress.invoke(target, to, id, v)
    }

    private fun Db.snapshot(account: UUID, profile: UUID) {
        copyRows(account, profile)
        put(profile, SNAPSHOT, "1")
    }

    // ---------------------------------------------------------------- profile events
    /** Before MMOProfiles drops a profile (switch or quit): keep its rows under its own uuid. */
    fun onUnload(player: Player, profile: UUID) {
        val d = db ?: return
        try {
            if (d.data(player.uniqueId, OWNER) == profile.toString()) d.snapshot(player.uniqueId, profile)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "[ColdMC] advancement snapshot failed for ${player.name}", t)
        }
    }

    /** A profile was picked: make the account rows that profile's rows, then redraw. */
    fun onSelect(player: Player, profile: UUID, questFactAdvancements: () -> Set<String>) {
        val d = db ?: return
        val account = player.uniqueId
        try {
            val owner = d.data(account, OWNER)
            when {
                owner == null -> {
                    // First pick after this system went live: the account's old progress becomes
                    // this profile's, minus everything the Typewriter quests grant (their facts
                    // moved to per-profile variables and start at 0 - the owner accepted that reset).
                    val reset = questFactAdvancements() - accountScoped
                    for (id in reset) {
                        d.uncomplete.invoke(d.target, account, id)
                        d.delProgress.invoke(d.target, account, id)
                    }
                    log.info("[ColdMC] ${player.name}: first per-profile pick, kept old advancements, reset ${reset.size} quest ones")
                }
                owner == profile.toString() -> Unit // already live (relog into the same profile)
                else -> {
                    // A crash can skip ProfileUnloadEvent: save the old owner's rows first.
                    runCatching { UUID.fromString(owner) }.getOrNull()?.let { d.snapshot(account, it) }
                    if (d.data(profile, SNAPSHOT) != null) d.copyRows(profile, account)
                    else d.copyRows(UUID(0, 0), account) // a profile with nothing yet: clear
                }
            }
            d.put(account, OWNER, profile.toString())
            d.snapshot(account, profile)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "[ColdMC] advancement swap failed for ${player.name}", t)
            return
        }
        resync(player)
    }

    // ---------------------------------------------------------------- client display
    /**
     * Make UAA (the client's advancement screen) match the database without paying rewards,
     * without chat announcements and without toasts: progression is written through UAA's
     * DatabaseManager (no onGrant), with the tabs hidden, then the tabs are shown again.
     */
    fun resync(player: Player) {
        val d = db ?: return
        val plugin = padv ?: return
        if (!player.isOnline) return
        val uaa = Bukkit.getPluginManager().getPlugin("UltimateAdvancementAPI") as? AdvancementPlugin ?: return
        val dbm = uaa.main.databaseManager
        if (!dbm.isLoaded(player)) return // PremiumAdvancements syncs from the database itself on load
        val done = d.done(player.uniqueId).toSet()
        val prog = d.prog(player.uniqueId)
        val team = dbm.getTeamProgression(player)
        val api = com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI.getInstance(plugin)
        val tabs = api.tabs.filter { it.owningPlugin == plugin && it.isActive }
        val shown = tabs.filter { it.isShownTo(player) }
        shown.forEach { it.hideTab(player) }
        val writes = ArrayList<CompletableFuture<*>>()
        for (tab in tabs) for (adv in tab.advancements) {
            if (adv is RootAdvancement) continue
            val id = adv.key.key
            val max = adv.maxProgression
            val want = if (id in done) max else (prog[id] ?: 0).coerceIn(0, max - 1)
            if (team.getRawProgression(adv.key) != want) writes += dbm.setProgression(adv.key, team, want)
        }
        CompletableFuture.allOf(*writes.toTypedArray()).whenComplete { _, err ->
            if (err != null) log.log(Level.WARNING, "[ColdMC] advancement redraw failed for ${player.name}", err)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (player.isOnline) shown.forEach { if (it.isActive) it.showTab(player) }
            })
        }
    }

    fun status(): String = disabledReason?.let { "OFF: $it" } ?: if (db != null) "on" else "not started"
}
