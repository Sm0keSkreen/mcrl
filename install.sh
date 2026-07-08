#!/usr/bin/env bash
# mcrl installer/uninstaller for Linux and macOS.

set -u

JAR_URL="https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar"
DEFAULT_DIR="$HOME/.local/share/mcrl"
TAG_LINE="# mcrl (added by install.sh)"
ENV_D_FILE="$HOME/.config/environment.d/mcrl.conf"
LAUNCH_AGENT_LABEL="space.mcrl.env"
LAUNCH_AGENT_PLIST="$HOME/Library/LaunchAgents/$LAUNCH_AGENT_LABEL.plist"

# Known Flatpak Minecraft launchers, pre-selected by default in the picker below.
KNOWN_FLATPAK_APPS="org.prismlauncher.PrismLauncher org.polymc.PolyMC com.modrinth.ModrinthApp com.mojang.Minecraft"

is_macos() {
    [ "$(uname -s)" = "Darwin" ]
}

# environment.d is only loaded by systemd's per-user manager (Linux only).
has_systemd_user() {
    [ -d /run/systemd/system ]
}

detect_rc_file() {
    case "$(basename "${SHELL:-}")" in
        zsh) echo "$HOME/.zshrc" ;;
        bash) echo "$HOME/.bashrc" ;;
        *) echo "$HOME/.profile" ;;
    esac
}

# Read-only: prints the currently-configured jar path and returns 1 if none is found.
find_existing_jar_path() {
    local rc_file line extracted app override
    rc_file="$(detect_rc_file)"

    if [ -f "$LAUNCH_AGENT_PLIST" ]; then
        line=$(grep -o 'javaagent:.*mcrl\.jar' "$LAUNCH_AGENT_PLIST" | head -n1 || true)
        if [ -n "$line" ]; then
            extracted="${line#javaagent:}"
            echo "${extracted#\"}"
            return 0
        fi
    fi

    if [ -f "$ENV_D_FILE" ]; then
        line=$(grep -o 'javaagent:.*mcrl\.jar' "$ENV_D_FILE" | head -n1 || true)
        if [ -n "$line" ]; then
            extracted="${line#javaagent:}"
            echo "${extracted#\"}"
            return 0
        fi
    fi

    if [ -f "$rc_file" ]; then
        line=$(grep -o 'javaagent:.*mcrl\.jar' "$rc_file" | head -n1 || true)
        if [ -n "$line" ]; then
            extracted="${line#javaagent:}"
            extracted="${extracted#\\}"
            echo "${extracted#\"}"
            return 0
        fi
    fi

    if command -v flatpak >/dev/null 2>&1; then
        for app in $(flatpak list --app --columns=application 2>/dev/null); do
            override=$(flatpak override --user --show "$app" 2>/dev/null | grep '^JDK_JAVA_OPTIONS=' || true)
            if echo "$override" | grep -q 'mcrl\.jar'; then
                extracted="${override#JDK_JAVA_OPTIONS=-javaagent:}"
                extracted="${extracted#\"}"
                echo "${extracted%\"*}"
                return 0
            fi
        done
    fi

    return 1
}

download_jar() {
    local target="$1"
    echo ""
    echo "Fetching mcrl.jar into $target ..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL -o "$target" "$JAR_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$target" "$JAR_URL"
    else
        echo "Need curl or wget to download the jar, neither was found."
        exit 1
    fi
    if [ ! -f "$target" ]; then
        echo ""
        echo "Download failed, mcrl.jar isn't at $target."
        exit 1
    fi
}

# Prompts for the three optional toggles and writes config.json into the given directory. Any of
# extras_override/telemetry_override/profanity_override that's "true" or "false" skips that one
# prompt and uses the given value instead, so this can also run fully non-interactively.
prompt_and_write_config() {
    local target_dir="$1" extras_override="${2:-}" telemetry_override="${3:-}" profanity_override="${4:-}"
    local answer extras_bool telemetry_bool profanity_bool

    case "$extras_override" in
        true|false) extras_bool="$extras_override" ;;
        *)
            read -r -p "Also unlock Realms, the multiplayer server list, and friends where the account API supports it? (y/N): " answer
            case "$answer" in y|Y) extras_bool=true ;; *) extras_bool=false ;; esac
            ;;
    esac

    case "$telemetry_override" in
        true|false) telemetry_bool="$telemetry_override" ;;
        *)
            read -r -p "Allow telemetry reporting to Mojang? (y/N): " answer
            case "$answer" in y|Y) telemetry_bool=true ;; *) telemetry_bool=false ;; esac
            ;;
    esac

    case "$profanity_override" in
        true|false) profanity_bool="$profanity_override" ;;
        *)
            read -r -p "Allow the in-game chat profanity filter? (y/N): " answer
            case "$answer" in y|Y) profanity_bool=true ;; *) profanity_bool=false ;; esac
            ;;
    esac

    cat > "$target_dir/config.json" <<CONFIGJSON
{
  "extras": $extras_bool,
  "allowTelemetry": $telemetry_bool,
  "allowProfanityFilter": $profanity_bool
}
CONFIGJSON
    echo "Wrote $target_dir/config.json."
}

list_installed_flatpak_apps() {
    command -v flatpak >/dev/null 2>&1 && flatpak list --app --columns=application,name 2>/dev/null
}

# Numbered picker over every installed Flatpak app; Enter defaults to the known launchers.
select_flatpak_targets() {
    local app name mark i idx picks
    local -a apps=() names=()
    while IFS=$'\t' read -r app name; do
        [ -z "$app" ] && continue
        apps+=("$app")
        names+=("$name")
    done < <(list_installed_flatpak_apps)

    [ "${#apps[@]}" -eq 0 ] && return

    echo "" >&2
    echo "Installed Flatpak apps:" >&2
    for i in "${!apps[@]}"; do
        mark=""
        case " $KNOWN_FLATPAK_APPS " in
            *" ${apps[$i]} "*) mark=" (known launcher)" ;;
        esac
        printf "  [%d] %s - %s%s\n" "$((i + 1))" "${apps[$i]}" "${names[$i]}" "$mark" >&2
    done
    echo "" >&2
    read -r -p "Numbers to cover (space-separated, Enter for known launchers only): " picks

    if [ -z "$picks" ]; then
        for i in "${!apps[@]}"; do
            case " $KNOWN_FLATPAK_APPS " in
                *" ${apps[$i]} "*) echo "${apps[$i]}" ;;
            esac
        done
        return
    fi

    for i in $picks; do
        idx=$((i - 1))
        if [ "$idx" -ge 0 ] && [ "$idx" -lt "${#apps[@]}" ]; then
            echo "${apps[$idx]}"
        fi
    done
}

# For package managers (Homebrew, AUR, etc.) that already know exactly where their own jar
# lives: writes config.json there directly, skipping the interactive menu entirely. Any of the
# three toggles can be preset via flags for fully non-interactive/scripted use; whichever aren't
# still prompt normally.
if [ "${1:-}" = "--configure-only" ]; then
    CONFIGURE_DIR="${2:-}"
    if [ -z "$CONFIGURE_DIR" ] || [ ! -d "$CONFIGURE_DIR" ]; then
        echo "Usage: install.sh --configure-only <directory-containing-mcrl.jar> [--extras=true|false] [--telemetry=true|false] [--profanity=true|false]"
        exit 1
    fi
    shift 2
    EXTRAS_FLAG=""
    TELEMETRY_FLAG=""
    PROFANITY_FLAG=""
    for arg in "$@"; do
        case "$arg" in
            --extras=*) EXTRAS_FLAG="${arg#--extras=}" ;;
            --telemetry=*) TELEMETRY_FLAG="${arg#--telemetry=}" ;;
            --profanity=*) PROFANITY_FLAG="${arg#--profanity=}" ;;
        esac
    done
    prompt_and_write_config "$CONFIGURE_DIR" "$EXTRAS_FLAG" "$TELEMETRY_FLAG" "$PROFANITY_FLAG"
    exit 0
fi

echo ""
echo "mcrl, chat restrictions lifted"
echo ""
echo "What would you like to do?"
echo "  [1] Install (default)"
echo "  [2] Uninstall"
echo "  [3] Reconfigure (change Realms/telemetry/profanity choices)"
echo "  [4] Upgrade (re-download the jar, keep everything else)"
read -r -p "Choose 1-4: " CHOICE

if [ "$CHOICE" = "3" ]; then
    echo ""
    JAR_PATH="$(find_existing_jar_path || true)"
    if [ -z "$JAR_PATH" ]; then
        echo "Didn't find an existing mcrl install to reconfigure. Run install first."
        exit 1
    fi
    if [ ! -d "$(dirname "$JAR_PATH")" ]; then
        echo "mcrl is configured to use $JAR_PATH, but that install directory no longer exists."
        echo "Run install again to recreate it."
        exit 1
    fi
    echo "Found existing install at $JAR_PATH"
    echo ""
    prompt_and_write_config "$(dirname "$JAR_PATH")"
    exit 0
fi

if [ "$CHOICE" = "4" ]; then
    echo ""
    JAR_PATH="$(find_existing_jar_path || true)"
    if [ -z "$JAR_PATH" ]; then
        echo "Didn't find an existing mcrl install to upgrade. Run install first."
        exit 1
    fi
    echo "Found existing install at $JAR_PATH"
    download_jar "$JAR_PATH"
    echo ""
    echo "Upgraded $JAR_PATH. Config and environment setup unchanged."
    echo "Close every Minecraft launcher window and reopen."
    exit 0
fi

if [ "$CHOICE" = "2" ]; then
    echo ""
    echo "Looking for an existing install..."
    RC_FILE="$(detect_rc_file)"
    FOUND=0
    JAR_PATH=""

    if [ -f "$LAUNCH_AGENT_PLIST" ]; then
        LINE=$(grep -o 'javaagent:.*mcrl\.jar' "$LAUNCH_AGENT_PLIST" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            JAR_PATH="${LINE#javaagent:}"
            JAR_PATH="${JAR_PATH#\"}"
            launchctl bootout "gui/$(id -u)" "$LAUNCH_AGENT_PLIST" >/dev/null 2>&1 || true
            launchctl unsetenv JDK_JAVA_OPTIONS >/dev/null 2>&1 || true
            rm -f "$LAUNCH_AGENT_PLIST"
            echo "Unloaded and removed $LAUNCH_AGENT_PLIST."
        fi
    fi

    if [ -f "$ENV_D_FILE" ]; then
        LINE=$(grep -o 'javaagent:.*mcrl\.jar' "$ENV_D_FILE" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            EXTRACTED="${LINE#javaagent:}"
            EXTRACTED="${EXTRACTED#\"}"
            JAR_PATH="${JAR_PATH:-$EXTRACTED}"
            rm -f "$ENV_D_FILE"
            echo "Removed $ENV_D_FILE."
        fi
    fi

    if [ -f "$RC_FILE" ]; then
        LINE=$(grep -o 'javaagent:.*mcrl\.jar' "$RC_FILE" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            EXTRACTED="${LINE#javaagent:}"
            EXTRACTED="${EXTRACTED#\\}"
            EXTRACTED="${EXTRACTED#\"}"
            JAR_PATH="${JAR_PATH:-$EXTRACTED}"
            grep -vx -e "$TAG_LINE" -e 'export JDK_JAVA_OPTIONS=.*mcrl\.jar.*' "$RC_FILE" > "$RC_FILE.mcrl_tmp" \
                && mv "$RC_FILE.mcrl_tmp" "$RC_FILE"
            echo "Removed the JDK_JAVA_OPTIONS line from $RC_FILE."
        fi
    fi

    if command -v flatpak >/dev/null 2>&1; then
        for APP in $(flatpak list --app --columns=application 2>/dev/null); do
            CURRENT_OVERRIDE=$(flatpak override --user --show "$APP" 2>/dev/null | grep '^JDK_JAVA_OPTIONS=' || true)
            if echo "$CURRENT_OVERRIDE" | grep -q 'mcrl\.jar'; then
                FOUND=1
                APP_JAR_PATH="${CURRENT_OVERRIDE#JDK_JAVA_OPTIONS=-javaagent:}"
                APP_JAR_PATH="${APP_JAR_PATH#\"}"
                APP_JAR_PATH="${APP_JAR_PATH%\"*}"
                JAR_PATH="${JAR_PATH:-$APP_JAR_PATH}"
                flatpak override --user --unset-env=JDK_JAVA_OPTIONS "$APP"
                flatpak override --user --nofilesystem="$(dirname "$APP_JAR_PATH")" "$APP"
                echo "Removed the Flatpak override for $APP."
            fi
        done
    fi

    if [ "$FOUND" = "0" ]; then
        echo "Didn't find an mcrl install (no LaunchAgent, no environment.d entry, no shell rc entry, no matching Flatpak override)."
        echo "Nothing to do."
        exit 0
    fi

    TARGET_DIR="${JAR_PATH:+$(dirname "$JAR_PATH")}"
    TARGET_DIR="${TARGET_DIR:-$DEFAULT_DIR}"
    if [ -d "$TARGET_DIR" ]; then
        read -r -p "Also delete $TARGET_DIR (jar and config.json)? (y/N): " REMOVE
        case "$REMOVE" in
            y|Y) rm -rf "$TARGET_DIR"; echo "Deleted $TARGET_DIR." ;;
        esac
    fi

    echo ""
    echo "All done. Close every Minecraft launcher window and reopen."
    exit 0
fi

read -r -p "Install folder (Enter for default: $DEFAULT_DIR): " INSTALL_DIR
INSTALL_DIR="${INSTALL_DIR:-$DEFAULT_DIR}"
mkdir -p "$INSTALL_DIR"
JAR_PATH="$INSTALL_DIR/mcrl.jar"

echo ""
prompt_and_write_config "$INSTALL_DIR"

download_jar "$JAR_PATH"

if is_macos; then
    mkdir -p "$(dirname "$LAUNCH_AGENT_PLIST")"
    cat > "$LAUNCH_AGENT_PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LAUNCH_AGENT_LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/launchctl</string>
        <string>setenv</string>
        <string>JDK_JAVA_OPTIONS</string>
        <string>-javaagent:"$JAR_PATH"</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
PLIST
    launchctl bootout "gui/$(id -u)" "$LAUNCH_AGENT_PLIST" >/dev/null 2>&1 || true
    launchctl bootstrap "gui/$(id -u)" "$LAUNCH_AGENT_PLIST"
    echo "Wrote and loaded $LAUNCH_AGENT_PLIST (covers native, non-Flatpak launchers)."
    NATIVE_NOTE="already active for this login session, no restart needed for it specifically"
elif has_systemd_user; then
    mkdir -p "$(dirname "$ENV_D_FILE")"
    echo "JDK_JAVA_OPTIONS=-javaagent:\"$JAR_PATH\"" > "$ENV_D_FILE"
    echo "Wrote $ENV_D_FILE (covers native, non-Flatpak launchers)."
    NATIVE_NOTE="systemd only reads environment.d at session start, so log out and back in"
else
    RC_FILE="$(detect_rc_file)"
    touch "$RC_FILE"
    if ! grep -qx "$TAG_LINE" "$RC_FILE"; then
        {
            echo ""
            echo "$TAG_LINE"
            echo "export JDK_JAVA_OPTIONS=\"-javaagent:\\\"$JAR_PATH\\\"\""
        } >> "$RC_FILE"
        echo "Added JDK_JAVA_OPTIONS to $RC_FILE (covers native, non-Flatpak launchers)."
    else
        echo "$RC_FILE already has an mcrl entry, leaving it as-is."
    fi
    NATIVE_NOTE="open a new terminal so $RC_FILE gets re-read"
fi

FLATPAK_TARGETS="$(select_flatpak_targets)"
if [ -n "$FLATPAK_TARGETS" ]; then
    while IFS= read -r APP; do
        [ -z "$APP" ] && continue
        flatpak override --user --env=JDK_JAVA_OPTIONS="-javaagent:\"$JAR_PATH\"" "$APP"
        flatpak override --user --filesystem="$INSTALL_DIR:ro" "$APP"
        echo "Set the Flatpak override for $APP."
    done <<< "$FLATPAK_TARGETS"
fi

echo ""
echo "Installed. JDK_JAVA_OPTIONS now points at $JAR_PATH"
echo "Options are read from $INSTALL_DIR/config.json at game launch; rerun this script"
echo "and choose Reconfigure to change them without reinstalling."
echo "For native launchers, $NATIVE_NOTE. Close every Minecraft launcher"
echo "window and reopen either way."
