# Mcrl, Minecraft Chat Restrictions Lifted

<img src="assets/banner.png" alt="Mcrl, chat restrictions lifted" width="100%">

[![Last commit](https://img.shields.io/github/last-commit/Sm0keSkreen/mcrl)](https://github.com/Sm0keSkreen/mcrl/commits/master)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://github.com/Sm0keSkreen/mcrl)
[![Vanilla](https://img.shields.io/badge/Vanilla-yes-4c1)]()
[![Forge](https://img.shields.io/badge/Forge-yes-4c1)]()
[![NeoForge](https://img.shields.io/badge/NeoForge-yes-4c1)]()
[![Fabric](https://img.shields.io/badge/Fabric-yes-4c1)]()
[![Quilt](https://img.shields.io/badge/Quilt-yes-4c1)]()

A JVM agent that clears the Microsoft/Xbox account-level "chat disabled" check on the
Minecraft Java client. Works on vanilla and every loader, one jar, no rebuild per
version. Doesn't touch chat signing or reporting, that's a separate system (see
[What it actually touches](#what-it-actually-touches)).
### This was coded with the help of ai, though I looked over every part of this project, and came up with every desgin chose myself.

## Install

### Windows

Download [`install.bat`](https://github.com/Sm0keSkreen/mcrl/releases/latest/download/install.bat),
double-click it, follow the prompt (install or uninstall, and where). It downloads
`mcrl.jar` and points `JDK_JAVA_OPTIONS` at it for you, defaulting to
`%LOCALAPPDATA%\Mcrl` if you don't pick a different folder. Same script handles
removing it later, pick uninstall and it clears the environment variable and
optionally deletes the folder.

Once it's set, that's it, this sticks around from now on. No per-instance JVM
argument, no re-running this after Minecraft updates, and no re-running it after Mcrl
itself gets updated either since it always pulls the current jar. Just close every
open Minecraft launcher window (official launcher, PrismLauncher, CurseForge,
whatever you use) and reopen after running it.

Prefer not to run a script at all? Manual version: download
[`mcrl.jar`](https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar) from
the latest release, put it somewhere permanent like `%LOCALAPPDATA%\Mcrl\mcrl.jar`, then
open Start, search "environment variables," choose "Edit environment variables for your
account," and add a new variable named `JDK_JAVA_OPTIONS` with the value
`-javaagent:"%LOCALAPPDATA%\Mcrl\mcrl.jar"` (expanded to your actual path, quotes
included, so `JDK_JAVA_OPTIONS`'s own tokenizer doesn't split on a space in your
username).

### Linux / macOS

Download [`install.sh`](https://github.com/Sm0keSkreen/mcrl/releases/latest/download/install.sh)
and run it (`bash install.sh`), same install/uninstall/choose-path prompt as the
Windows version. For native launchers it picks the right mechanism per OS, since
none of them read a shell profile: on macOS it installs a `LaunchAgent`
(`~/Library/LaunchAgents`) that runs `launchctl setenv`, active immediately; on
systemd Linux it writes `~/.config/environment.d/mcrl.conf` (loaded once per login,
by the session itself); anywhere else it falls back to your shell profile
(`~/.bashrc`/`~/.zshrc`/`~/.profile`), which only actually reaches a launcher you
start from that same terminal, not one started from a desktop icon. On Linux it
also lists every installed Flatpak app so you can pick which ones to cover (common
launchers are pre-selected), and sets a Flatpak override, filesystem access
included, for each one you choose, since Flatpak apps don't see the host shell's
environment or the rest of your filesystem at all.

Prefer to do it by hand? Same pieces, just set them yourself. Quote the path (even
if it has no spaces) so `JDK_JAVA_OPTIONS`'s own tokenizer doesn't split on any
embedded space:

On macOS, a `LaunchAgent` plist calling `launchctl setenv JDK_JAVA_OPTIONS
'-javaagent:"/path/to/mcrl.jar"'` (see [Apple's LaunchAgent
docs](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html)
for the plist format), loaded with `launchctl bootstrap gui/$(id -u) <plist>`.

On a systemd system, put this in `~/.config/environment.d/mcrl.conf` (no `export`,
just the assignment):

```
JDK_JAVA_OPTIONS=-javaagent:"/path/to/mcrl.jar"
```

Not on systemd, add this to your shell profile instead:

```
export JDK_JAVA_OPTIONS="-javaagent:\"/path/to/mcrl.jar\""
```

For a Flatpak launcher, both the env var and read access to the jar's folder:

```
flatpak override --user --env=JDK_JAVA_OPTIONS='-javaagent:"/path/to/mcrl.jar"' org.prismlauncher.PrismLauncher
flatpak override --user --filesystem=/path/to:ro org.prismlauncher.PrismLauncher
```

(swap `org.prismlauncher.PrismLauncher` for whichever launcher's Flatpak app ID you're
using)

### Applying it to just one instance instead

Everything above sets `JDK_JAVA_OPTIONS` globally, every Java program picks it up,
not just Minecraft (harmless, see [What it actually
touches](#what-it-actually-touches)). If you'd rather scope it to a single instance or
profile instead, most launchers have their own per-instance JVM arguments field, paste
`-javaagent:/path/to/mcrl.jar` in there and skip the environment variable entirely:

- **PrismLauncher / MultiMC / PolyMC**: right-click the instance, "Edit Instance," Java
  tab, "JVM arguments" box.
- **Official Minecraft Launcher**: Installations tab, edit a profile, "More Options,"
  "JVM Arguments" field (append to what's already there, don't replace it).
- **Modrinth App**: instance settings, Java tab, custom JVM arguments.
- **ATLauncher**: instance settings, Java/Minecraft tab, custom JVM arguments.

## How it works

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

This has been checked against real bytecode, not just assumed to work. All 24 release
versions from 1.19 through 1.21.11, plus 26.1 and 26.2, tested against the raw client
jar Mojang ships with no remapping applied, and cross-checked against real Fabric
"Intermediary" production bytecode using Fabric's own tiny-remapper tool.

### What it actually touches

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
applies to every Java program you run afterward, not just Minecraft. It's harmless,
the agent does nothing on anything that isn't the game, and checks whether the
classpath or launch command looks like Minecraft before printing anything, so other
Java programs' logs stay clean. The one thing that can't be suppressed is the JVM's
own `NOTE: Picked up JDK_JAVA_OPTIONS...` line, which every Java 9+ runtime prints
before any agent code even runs.

The agent itself needs Java 8 or newer to even load, which is about as broad as
Minecraft's own runtime requirements get across every version.

If some future version restructures the feature again in a way that matches neither
shape, the agent just won't find anything to patch. You'll see its install banner
print but no "found... enum" or "patching..." lines, which is how you'd notice nothing
happened.

### Optional: Realms, servers, and friends

Chat unlock is always on. Both install scripts also ask whether to unlock Realms, the
multiplayer server list, and friends (where the account API has that flag), off by
default. This patches a different target entirely: Mojang's `authlib` library, which
unlike Minecraft's own classes is a plain, unobfuscated shared dependency with stable
names across every loader and version, so it's matched by real class/method name
instead of the shape-detection the chat patch needs. Enabling it manually means adding
`=extras` after the jar path in whichever mechanism you're using, e.g.
`-javaagent:"/path/to/mcrl.jar"=extras`.

Two things worth knowing: it doesn't force `ACCEPT_FRIEND_INVITES` or
`CHAT_FRIENDS_ONLY`, leaving those as your account actually reports them, matching No
Chat Reports' own restraint there. And the account API's flag list itself has grown
over time, friends support in particular is a recent addition, so on older Minecraft
versions whichever flags aren't present yet get silently skipped rather than causing
an error.

## Beta

This is fresh and hasn't been run at any real scale yet, just checked against real
bytecode as described above. If you hit anything off, a version or loader combo that
doesn't get patched, chat still blocked after install, anything weird, open an issue
on the repo or message me on Discord at `sm0keskreen`. Real reports from real use are
the most useful thing right now.

## Looking for help with a tutorial video

Written instructions only get so many people to actually try something. If anyone
wants to put together a short video walking through the install and showing it
working in-game, reach out, happy to help with whatever's needed for it.

## Building from source

The Gradle project lives in [`agent/`](agent), see [`agent/README.md`](agent/README.md)
for requirements, the build command, and a rundown of the source layout.

# Faq
please note that this project was hevely assested by the use of ai, and also there is no ai used past this line

### why not use the mod No Chat Restrictions?

in simple terms, this is like a golbal No chat restrrictions, where (once you install it)  every single Minecraft instace will basicly have a "ghost "vertion" of  No Chat Restrictions, that applys to all vertions. also this lets you use vanilla, and is also compattable with all mod loaders (not just the main cupple!) and will work for futere vertions if they keep the chat restrcionts the way they are now. also this works in snapshots and aprill fools snapshots.

### is it compatable with x mod or y instance

yep... It modifys it in a way where it looks at byte code, and looks at the "shape" and not the name of it. and it runs indpendntly from minecraft's mod loaders and stuff. no amount of diffrent mod loaders and mods should change that.

### there is an issue with x or a bug in y, how do i contact you?

use discord. im sm0keskreen
