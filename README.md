# Mcrl, Minecraft Chat Restrictions Lifted

<img src="assets/banner.png" alt="Mcrl, chat restrictions lifted" width="100%">

[![Last commit](https://img.shields.io/github/last-commit/Dylanthedabber/mcrl)](https://github.com/Dylanthedabber/mcrl/commits/master)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://github.com/Dylanthedabber/mcrl)
[![Vanilla](https://img.shields.io/badge/Vanilla-yes-4c1)]()
[![Forge](https://img.shields.io/badge/Forge-yes-4c1)]()
[![NeoForge](https://img.shields.io/badge/NeoForge-yes-4c1)]()
[![Fabric](https://img.shields.io/badge/Fabric-yes-4c1)]()
[![Quilt](https://img.shields.io/badge/Quilt-yes-4c1)]()

## Quick install (Windows)

Download [`install.bat`](https://github.com/Dylanthedabber/mcrl/releases/latest/download/install.bat), double-click it, follow the prompt. That's the
whole thing, no admin rights, no manual setup. See [Install
(Windows)](#install-windows) below for what it actually does, the PowerShell
alternative, and the fully manual fallback.

A JVM agent that clears the Microsoft/Xbox account-level "chat disabled" check on the
Minecraft Java client. It doesn't touch chat signing or reporting, that's a separate
system (see below).

It's a `-javaagent`, not a mod, so it sits below whatever loader you're running, or
nothing at all. Instead of hardcoding a class or method name, it matches by shape, so
one jar covers every loader and unmodified vanilla without a rebuild. Two shapes
actually exist depending on how old the game is:

- **1.19 through 1.21.11**: a single enum with four constants, `ENABLED`,
  `DISABLED_BY_OPTIONS`, `DISABLED_BY_PROFILE`, `DISABLED_BY_LAUNCHER`.
- **26.1 and up**: Mojang rebuilt this into a `ChatAbilities` object made from a set of
  `ChatRestriction` reasons, no more `ENABLED` constant, "no restriction" just means an
  empty set. This gets matched by its fluent `addRestriction(ChatRestriction) -> same
  type` method instead.

The thing that took the longest to figure out: every class, method, and field name in
the game gets renamed depending on what's running it. Raw vanilla is obfuscated by
Mojang. Forge and NeoForge remap to Mojang's own official names. Fabric and Quilt
remap to something called "Intermediary," a different, synthetic scheme, not the
human-readable Yarn names you'd see while developing a mod (production Fabric never
uses those, only the dev environment does). So none of this agent's matching relies on
a name at all. It reads the literal strings baked into each enum constant's own
constructor call, the actual text `"ENABLED"`, `"DISABLED_BY_PROFILE"`, and so on.
Those survive every renaming scheme because obfuscators rename symbols, not the string
literals sitting in the bytecode, and the game itself needs those exact strings to
keep working for `Enum.name()` and `valueOf()`. That's the one property that lets a
single jar cover vanilla and every loader at once.

This has actually been checked against real bytecode, not just assumed to work. All 24
release versions from 1.19 through 1.21.11, plus 26.1 and 26.2, tested against the raw
client jar Mojang ships with no remapping applied, confirming vanilla works across the
whole range, not just 26.x. Also cross-checked against real Fabric "Intermediary"
production bytecode using Fabric's own tiny-remapper tool, not a guess at what it
should look like. (An earlier version of this file said vanilla didn't work before
26.1. That was wrong, caused by a `grep` call that silently skips binary files without
`-a`. Found and fixed once testing got more rigorous.)

## Install (Windows)

Download [`install.bat`](https://github.com/Dylanthedabber/mcrl/releases/latest/download/install.bat) and double-click it, no right-click
ceremony needed. Follow the prompt (install or uninstall, and where). It downloads
`mcrl.jar` and points `JDK_JAVA_OPTIONS` at it for you, defaulting to
`%LOCALAPPDATA%\Mcrl` if you don't pick a different folder. Same script handles
removing it later, pick uninstall and it clears the environment variable and
optionally deletes the folder.

There's also [`install.ps1`](https://github.com/Dylanthedabber/mcrl/releases/latest/download/install.ps1), same prompt, same behavior, if you'd
rather run PowerShell (right-click it, choose "Run with PowerShell").

Once it's set, that's it, this sticks around from now on. No per-instance JVM
argument, no re-running this after Minecraft updates, and no re-running it after Mcrl
itself gets updated either since it always pulls the current jar. Works on any
Minecraft Java version, any loader, and unmodified vanilla. Just close every open
Minecraft launcher window (official launcher, PrismLauncher, CurseForge, whatever you
use) and reopen after running it.

Prefer not to run a script at all? Manual version: download `mcrl.jar` from this repo,
put it somewhere permanent like `%LOCALAPPDATA%\Mcrl\mcrl.jar`, then open Start,
search "environment variables," choose "Edit environment variables for your account,"
and add a new variable named `JDK_JAVA_OPTIONS` with the value
`-javaagent:%LOCALAPPDATA%\Mcrl\mcrl.jar` (expanded to your actual path).

## Linux / macOS

Same idea. macOS has no launcher-sandbox issues to worry about. On Linux, if you're
running a Flatpak launcher (PrismLauncher, for example), a plain shell environment
variable won't reach it, so use this instead:

```
flatpak override --user --env=JDK_JAVA_OPTIONS='-javaagent:/path/to/Mcrl/mcrl.jar' org.prismlauncher.PrismLauncher
```

For a native, non-Flatpak install, just add this to your shell profile (`~/.bashrc`,
`~/.zshrc`, or the macOS equivalent):

```
export JDK_JAVA_OPTIONS="-javaagent:/path/to/Mcrl/mcrl.jar"
```

## What it actually touches

It overrides the `DISABLED_BY_PROFILE` check specifically, the one tied to your
Microsoft/Xbox account's "Online Safety" or communication privacy setting, so the game
reports chat as allowed no matter what that flag says. Everything else,
`DISABLED_BY_OPTIONS`, `DISABLED_BY_LAUNCHER`, and their 26.x equivalents, gets left
alone.

It does not touch chat message signing or the report-to-Mojang pipeline that came in
with 1.19. That's a much bigger system built around packet structure rather than a
simple enum check, and it's handled by other tools like No Chat Reports or
FreedomChat, not this one.

One side effect worth knowing about: `JDK_JAVA_OPTIONS` is a global setting, so it
applies to every Java program you run afterward, not just Minecraft. It's harmless
since the agent does nothing on anything that isn't the game, but you will see a
one-line notice print to the console of whatever else you run.

The agent itself needs Java 8 or newer to even load, which is about as broad as
Minecraft's own runtime requirements get across every version.

## Version coverage

Checked against real bytecode, not assumed:

| Range | Shape matched | Vanilla | Forge / NeoForge | Fabric / Quilt |
|---|---|---|---|---|
| 1.19 through 1.21.11 (24 releases) | legacy enum getter | Yes | Yes | Yes |
| 26.1, 26.2 | modern `ChatAbilities` builder | Yes | Yes* | Yes* |

\* Whether a Forge/NeoForge or Fabric/Quilt build for 26.x actually exists yet is
really a question about those projects catching up to a version that's only months
old, not a limitation of this agent. It doesn't care whether a loader is present at
all.

If some future version restructures the feature again in a way that matches neither
shape, the agent just won't find anything to patch. You'll see its install banner
print but no "found... enum" or "patching..." lines, which is how you'd notice nothing
happened.

## Building from source

Needs JDK 17+ and network access so Gradle can pull down ASM and the Shadow plugin.

```
./gradlew shadowJar   # or: gradle shadowJar
```

Output lands at `build/libs/mcrl.jar`.
