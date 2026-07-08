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

Download [`install.bat`](https://github.com/Sm0keSkreen/mcrl/releases/latest/download/install.bat)
and double-click it. Four options: install, uninstall, reconfigure (change your
Realms/telemetry/profanity choices without touching the jar or environment setup),
and upgrade (re-download the jar without touching your config or environment setup).
Install downloads `mcrl.jar` and points `JDK_JAVA_OPTIONS` at it for you, defaulting
to `%LOCALAPPDATA%\Mcrl` if you don't pick a different folder, and asks the three
config questions (see [Optional extras](#optional-realms-servers-friends-and-privacy)
below), writing your answers to `config.json` next to the jar.

Once it's set, that's it, this sticks around from now on. No per-instance JVM
argument, no re-running this after Minecraft updates. To pick up a newer mcrl release,
rerun the script and choose Upgrade, that only touches the jar. Just close every open
Minecraft launcher window (official launcher, PrismLauncher, CurseForge, whatever you
use) and reopen after installing or upgrading.

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
and run it (`bash install.sh`), same install/uninstall/reconfigure/upgrade prompt as
the Windows version. For native launchers it picks the right mechanism per OS, since
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

### Package managers

If you'd rather let a package manager track updates. Ready to install right now:

- **Homebrew** (macOS/Linux): `brew install Sm0keSkreen/mcrl/mcrl`. `brew upgrade mcrl`
  keeps the jar current at a path that never changes, so the one-time `JDK_JAVA_OPTIONS`
  setup printed after install only needs doing once. Installed and tested end to end.
- **Scoop** (Windows): `scoop bucket add mcrl https://github.com/Sm0keSkreen/scoop-mcrl`
  then `scoop install mcrl`. `JDK_JAVA_OPTIONS` is set automatically and stays correct
  across `scoop update mcrl`.
- **Nix**: `nix profile install github:Sm0keSkreen/nix-mcrl`, or use the included
  home-manager module (`programs.mcrl.enable = true;`) to also get `JDK_JAVA_OPTIONS`
  set for you. See [that repo](https://github.com/Sm0keSkreen/nix-mcrl) for details.

Scoop's and Nix's packages couldn't be tested on their real platforms from here (this
was built on Linux without Windows or without installing Nix itself); the formats are
well-understood and Homebrew's equivalent worked cleanly, but if something's off with
either, open an issue.

Packaging source lives in this repo but isn't published anywhere yet, each of these
needs an account on that ecosystem's own registry to actually publish, something only
the repo owner can set up:

- **AUR** (Arch Linux): [`packaging/aur`](packaging/aur)
- **Chocolatey** (Windows): [`packaging/chocolatey`](packaging/chocolatey)
- **MacPorts** (macOS): [`packaging/macports`](packaging/macports)
- **.deb** (Debian/Ubuntu): [`packaging/deb`](packaging/deb), a standalone installable
  package (build script verified working here), not an APT repo, so it won't auto-update
  via `apt upgrade`.
- **.rpm** (Fedora/RHEL): [`packaging/rpm`](packaging/rpm), spec file only, `rpmbuild`
  wasn't available to build/test it here.

Each folder's README explains how to build/install it yourself or publish it properly.

None of these hand you the Realms/telemetry/profanity extras automatically, since
those need `config.json`; run the full installer once for that (see above).

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

### Verifying a download

Every release also has a `SHA256SUMS.txt` asset covering `mcrl.jar`, `install.sh`,
and `install.bat`. To check what you downloaded against it:

```
sha256sum -c SHA256SUMS.txt --ignore-missing
```

(on Windows, `CertUtil -hashfile mcrl.jar SHA256` and compare the output by hand.)

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

### Optional: Realms, servers, friends, and privacy

Chat unlock is always on. Both install scripts also ask three more questions, all off
by default: unlock Realms, the multiplayer server list, and friends (where the
account API has that flag); allow telemetry reporting to Mojang; and allow the
in-game chat profanity filter. Your answers get written to a `config.json` next to
the jar, since this agent applies globally rather than per-instance, one shared file
covers every instance instead of a per-instance mod config. Change your mind later
without reinstalling, rerun the install script and choose Reconfigure.

That same "unlock" toggle also stops a locally-flagged forced-name-change or banned-skin
action from blocking third-party server connections (MC 1.20.4 and up; older versions'
authlib doesn't have this API at all, so it's silently skipped there, same as every
other flag-availability check on this page). Mojang's own servers and Realms enforce
their own moderation independently of this and are unaffected either way.

This patches a different target than the chat check: Mojang's `authlib` library,
which unlike Minecraft's own classes is a plain, unobfuscated shared dependency with
stable names across every loader and version, so it's matched by real class/method
name instead of the shape-detection the chat patch needs. Unlocking Realms/servers/
friends adds those flags to your account's real flag set; declining telemetry or the
profanity filter actively removes those flags if your account already has them,
rather than just leaving them alone.

This mirrors what the mod No Chat Restrictions does for the same flags (not
[No Chat Reports](https://github.com/Aizistral-Studios/No-Chat-Reports), a different,
unrelated project despite the similar name, that one's about chat signing, see above).
Two differences worth knowing about. First, like that mod, this doesn't force
`ACCEPT_FRIEND_INVITES` or `CHAT_FRIENDS_ONLY`, leaving those as your account actually
reports them. Second, unlike that mod, unlocking Realms/servers/friends adds those
flags on top of whatever your account's real flag set already is, rather than
rebuilding it from an explicit allowlist, so if Mojang adds some other flag neither
project knows about yet, it stays present here instead of silently disappearing. The
account API's own flag list has grown over time regardless, friends support in
particular is a recent addition, so on older Minecraft versions whichever flags
aren't present yet get silently skipped rather than causing an error.

Banned-username bypass, a real capability No Chat Restrictions also has, turned out to
live in authlib after all (`ProfileResult.actions()`, an unobfuscated record accessor
returning a `Set<ProfileActionType>`), not in Minecraft's own obfuscated classes as
originally assumed here; see [`ProfileActionsTransformer`](agent/src/main/java/mcrl/agent/ProfileActionsTransformer.java).

Prefer to do it by hand instead of the install script's prompts? Put this next to
your `mcrl.jar` as `config.json` (whole file is optional, no file at all means none
of this applies). All three fields are optional too: a missing `extras` defaults to
`false` (off), while a missing `allowTelemetry` or `allowProfanityFilter` means leave
that flag alone, whatever the account already has stays as-is. Only an explicit
`false` actively strips a flag the account has, and only an explicit `true` counts
as opting in:

```json
{
  "extras": true,
  "allowTelemetry": false,
  "allowProfanityFilter": false
}
```

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
