# Mcrl (pronounced "em-curl") - Minecraft Chat Restrictions Lifted

A JVM agent that clears the Microsoft/Xbox account-level "chat disabled" gate on the
Minecraft Java client. It does not touch chat signing/reporting - see the "What this
does and doesn't do" section below.

It's a `-javaagent`, not a mod, so it attaches below whatever mod loader (or nothing)
is running. It finds its target by shape - an enum with the constants `ENABLED`,
`DISABLED_BY_OPTIONS`, `DISABLED_BY_PROFILE`, `DISABLED_BY_LAUNCHER` - not by class or
method name, since Forge/NeoForge (official Mojang mappings: `Minecraft.getChatStatus()`)
and Fabric/Quilt (Yarn mappings: `MinecraftClient.getChatRestriction()`) name the same
feature differently. That means one jar works across all four loaders and vanilla,
without a rebuild per loader.

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
no re-running this after launcher/game updates. Works for any Minecraft Java version
and any loader (Forge, NeoForge, Fabric, Quilt, vanilla).

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
  the game always reports chat as enabled, regardless of that account flag. Every
  other restriction reason (`DISABLED_BY_OPTIONS`, `DISABLED_BY_LAUNCHER`) is left
  untouched.
- **Doesn't:** touch chat message signing or the report-to-Mojang pipeline introduced
  in 1.19. That's a separate, much larger system (packet structure, not a simple enum
  gate) handled by unrelated tools like No Chat Reports / FreedomChat.
- **Side effect:** `JDK_JAVA_OPTIONS` applies to *every* Java program you run
  afterward, not just Minecraft. Harmless functionally (the agent no-ops on anything
  that isn't the game), but you'll see a one-line notice print to the console of any
  unrelated Java program you run.

## Building from source

Requires JDK 17+ and network access for Gradle to fetch dependencies (ASM) and the
Shadow plugin.

```
./gradlew shadowJar   # or: gradle shadowJar
```

Output: `build/libs/mcrl.jar`.
