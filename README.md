# Mcrl (pronounced "em-curl") - Minecraft Chat Restrictions Lifted

A JVM agent that clears the Microsoft/Xbox account-level "chat disabled" gate on the
Minecraft Java client. It does not touch chat signing/reporting - see the "What this
does and doesn't do" section below.

It's a `-javaagent`, not a mod, so it attaches below whatever mod loader (or nothing)
is running, and matches its target by shape rather than by class/method name - so the
same jar works across loaders, and on unmodified vanilla, without a rebuild per loader.
Two real shapes exist depending on the game's era, and this agent handles both:

- **1.19 - 1.21.11**: a single enum with constants `ENABLED`, `DISABLED_BY_OPTIONS`,
  `DISABLED_BY_PROFILE`, `DISABLED_BY_LAUNCHER`.
- **26.1+**: Mojang restructured this into a `ChatAbilities` object built from a set of
  `ChatRestriction` reasons (no more `ENABLED` constant - "no restriction" is just an
  empty set). Matched by the fluent `addRestriction(ChatRestriction) -> same type`
  method shape instead.

The key design point, found the hard way (see below): every class/method/field
*symbol* name in the game gets renamed depending on context - Mojang's own obfuscation
in the raw vanilla jar, Forge/NeoForge's official-Mojang-mapping remap, Fabric/Quilt's
"Intermediary" remap (a *different*, synthetic, non-human-readable scheme from the
Yarn names you see in a dev environment - production Fabric/Quilt never uses those).
So this agent doesn't match on any symbol name at all. Instead it reads the literal
string arguments baked into the enum's own `<clinit>` (the `"ENABLED"`,
`"DISABLED_BY_PROFILE"`, etc. that every enum constant passes to its constructor) -
those survive every renaming scheme unchanged, because obfuscators rename symbols, not
arbitrary string literals, and the game itself depends on these particular strings
staying intact for `Enum.name()`/`valueOf()` and other data-driven lookups to work.
That one property is what makes a single jar cover vanilla and every loader at once.

**Verified against real bytecode, not assumed:** every one of the 24 release versions
from 1.19 through 1.21.11, plus 26.1 and 26.2, checked directly against the *raw,
unmodified* client jar Mojang actually ships (no remapping applied at all) - confirming
true vanilla works for the entire version range, not just 26.x. Additionally
cross-checked against real Fabric Loader "Intermediary" production bytecode
(remapped with Fabric's own `tiny-remapper` tool and official mappings, not a
hand-rolled approximation) to confirm Fabric/Quilt production environments match too.
An earlier version of this doc claimed vanilla didn't work pre-26.1 - that was wrong,
caused by a flawed test (`grep` silently skipping binary files without `-a`), corrected
once the mistake was found by testing more rigorously.

## Install (Windows)

1. Put this whole `Mcrl` folder inside your **Documents** folder, so the jar ends up at:
   `Documents\Mcrl\mcrl.jar`
2. Press `Win + R`, paste this, press Enter:

   ```
   cmd /c setx JDK_JAVA_OPTIONS "-javaagent:%USERPROFILE%\Documents\Mcrl\mcrl.jar"
   ```

3. Close every Minecraft launcher window that's currently open (official launcher,
   PrismLauncher, CurseForge, whatever), then reopen and launch normally.

That's it. This applies automatically from now on - no per-instance JVM argument,
no re-running this after launcher/game updates. Works for any Minecraft Java version,
any loader (Forge, NeoForge, Fabric, Quilt), and unmodified vanilla.

If your Windows account's Documents folder is redirected by OneDrive (some setups
move it to `...\OneDrive\Documents`), point the path in step 2 at wherever the folder
actually landed instead.

## Linux / macOS

Same idea, no launcher-sandbox issues on macOS. On Linux, if your launcher is a
Flatpak (e.g. PrismLauncher), a plain shell environment variable won't reach it - use:

```
flatpak override --user --env=JDK_JAVA_OPTIONS='-javaagent:/path/to/Mcrl/mcrl.jar' org.prismlauncher.PrismLauncher
```

For a native (non-Flatpak) install, add to your shell profile (`~/.bashrc`, `~/.zshrc`,
or macOS equivalent):

```
export JDK_JAVA_OPTIONS="-javaagent:/path/to/Mcrl/mcrl.jar"
```

## What this does and doesn't do

- **Does:** overrides the client-side `DISABLED_BY_PROFILE` chat gate (the check tied
  to the Microsoft/Xbox account's "Online Safety" / communication privacy setting) so
  the game always allows chat, regardless of that account flag. Every other
  restriction reason (`DISABLED_BY_OPTIONS`, `DISABLED_BY_LAUNCHER`, and their 26.x
  equivalents) is left untouched.
- **Doesn't:** touch chat message signing or the report-to-Mojang pipeline introduced
  in 1.19. That's a separate, much larger system (packet structure, not a simple enum
  gate) handled by unrelated tools like No Chat Reports / FreedomChat.
- **Side effect:** `JDK_JAVA_OPTIONS` applies to *every* Java program you run
  afterward, not just Minecraft. Harmless functionally (the agent no-ops on anything
  that isn't the game), but you'll see a one-line notice print to the console of any
  unrelated Java program you run.
- **Requires Java 17+ to run the agent itself**, same as Minecraft's own minimum for
  1.19+.

## Version coverage (verified against real bytecode, not assumed)

| Range | Shape matched | Vanilla | Forge / NeoForge | Fabric / Quilt |
|---|---|---|---|---|
| 1.19 - 1.21.11 (24 releases) | legacy enum getter | Yes | Yes | Yes |
| 26.1, 26.2 | modern `ChatAbilities` builder | Yes | Yes* | Yes* |

\* 26.x mod loader support depends on those projects having caught up to a
version-only-months-old release; the agent itself doesn't care whether a loader is
present at all, so this is really "does a loader for 26.x exist yet", not a limitation
of this agent.

If a future version restructures the feature again in a way that matches neither
shape, the agent simply never finds anything to patch - it prints its install banner
but no `found ... enum` / `patching ...` lines, which is how you'd notice it isn't
doing anything on that version.

## Building from source

Requires JDK 17+ and network access for Gradle to fetch dependencies (ASM) and the
Shadow plugin.

```
./gradlew shadowJar   # or: gradle shadowJar
```

Output: `build/libs/mcrl.jar`.
