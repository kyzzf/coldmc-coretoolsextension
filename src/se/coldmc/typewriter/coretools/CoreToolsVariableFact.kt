package se.coldmc.typewriter.coretools

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.WritableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.facts.FactId
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * A Typewriter fact whose value is a CoreTools PLAYER variable, so it belongs to the
 * MMOProfiles profile the player has selected instead of to the whole account.
 *
 * Criteria, modifiers, `tw facts set|add|reset` and `%typewriter_<id>%` all go through
 * readSinglePlayer / write, so every existing caller keeps working unchanged.
 *
 * `variable` is the CoreTools variable id; empty means "same as the entry id". The variable
 * must be declared in CoreTools/Variables/ (gen_typewriter_story.py writes them).
 */
@Entry(
    "coretools_variable_fact",
    "A per-profile number stored in a CoreTools player variable",
    Colors.PURPLE,
    "fa6-solid:user-tag",
)
class CoreToolsVariableFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    val variable: String = "",
) : ReadableFactEntry, WritableFactEntry {

    private val key: String get() = variable.ifBlank { id }

    override fun readSinglePlayer(player: Player): FactData =
        FactData(CoreToolsBridge.read(player, key))

    // Per profile, never per group: a player is always their own group here.
    override fun readForPlayersGroup(player: Player): FactData = readSinglePlayer(player)

    override fun readForGroup(groupId: GroupId): FactData {
        val player = playerOf(groupId.id) ?: return FactData(0)
        return readSinglePlayer(player)
    }

    override fun write(player: Player, value: Int) = CoreToolsBridge.write(player, key, value)

    override fun write(id: FactId, value: Int) {
        val player = playerOf(id.groupId.id) ?: return
        write(player, value)
    }

    private fun playerOf(raw: String): Player? =
        runCatching { Bukkit.getPlayer(UUID.fromString(raw)) }.getOrNull()
}
