# CoreToolsExtension — ColdMC's own Typewriter extension

Built 2026-11-22 for the owner's goal: *make Typewriter and PremiumAdvancements replayable by binding them
to MMOProfiles profiles instead of the whole player, using CoreTools per-profile variables instead of
Typewriter facts.* No such extension existed upstream, so this is ColdMC's own.

## What it does

1. **`coretools_variable_fact`** — a readable + writable Typewriter fact whose value is a CoreTools
   PLAYER variable (per MMOProfiles profile). `variable:` empty = same id as the entry.
   Criteria, modifiers, `tw facts set|add|reset` and `%typewriter_<id>%` all work on it.
   `gen_typewriter_story.py` emits every story fact as this type and declares the variables in
   `CoreTools/Variables/coldmc_story_facts.yml`.
2. **Per-profile PremiumAdvancements.** On `ProfileSelectEvent` the account's rows in PAdv's
   database are saved under the previous profile's uuid and replaced by the new profile's rows;
   the client display is redrawn through UAA's DatabaseManager with the tabs hidden (no rewards,
   no chat, no toasts). JOIN / PLAYTIME / LOGIN_STREAK advancements stay on the account.
   First pick after install: the account's old progress becomes that profile's, minus every
   advancement a Typewriter quest grants (a `cm_adv_<id>` fact) — those reset with the facts.
   Bookkeeping lives in PAdv's `player_data`: `coldmc_profile_owner` (account) and
   `coldmc_profile_snapshot` (profile).

PAdv 3.1 is obfuscated; its DatabaseManager (`o.n`, field `b` on the main class) is reached by
reflection on exact signatures. After a PAdv update, check the boot log for
`Per-profile advancements are OFF` — if it appears, progress silently falls back to per account.

## Build and install

```bash
sh knowledge/runtime/plugins-src/CoreToolsExtension/build.sh
mv <copy of CoreToolsExtension.jar> core/server/plugins/Typewriter/extensions/CoreToolsExtension.jar   # then restart
```

Kotlin 2.3.0 compiler and CFR live in `tools/`. `res/extension.json` is hand-written (what KSP would
generate); its `engineVersion` must equal the installed Typewriter. A good boot logs
`[ColdMC] Advancements are per profile (N account-wide ...)`.
