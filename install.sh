#!/usr/bin/env bash
# mcrl installer/uninstaller for Linux and macOS.

set -u

JAR_URL="https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar"
DEFAULT_DIR="$HOME/.local/share/mcrl"
TAG_LINE="# mcrl (added by install.sh)"
ENV_D_FILE="$HOME/.config/environment.d/mcrl.conf"

# Known Flatpak Minecraft launchers, pre-selected by default in the picker below.
KNOWN_FLATPAK_APPS="org.prismlauncher.PrismLauncher org.polymc.PolyMC com.modrinth.ModrinthApp com.mojang.Minecraft"

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

echo ""
echo "mcrl, chat restrictions lifted"
echo ""
echo "What would you like to do?"
echo "  [1] Install (default)"
echo "  [2] Uninstall"
read -r -p "Choose 1 or 2: " CHOICE

if [ "$CHOICE" = "2" ]; then
    echo ""
    echo "Looking for an existing install..."
    RC_FILE="$(detect_rc_file)"
    FOUND=0
    JAR_PATH=""

    if [ -f "$ENV_D_FILE" ]; then
        LINE=$(grep -o 'javaagent:[^"]*mcrl\.jar' "$ENV_D_FILE" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            JAR_PATH="${LINE#javaagent:}"
            rm -f "$ENV_D_FILE"
            echo "Removed $ENV_D_FILE."
        fi
    fi

    if [ -f "$RC_FILE" ]; then
        LINE=$(grep -o 'javaagent:[^"]*mcrl\.jar' "$RC_FILE" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            JAR_PATH="${JAR_PATH:-${LINE#javaagent:}}"
            grep -vx -e "$TAG_LINE" -e 'export JDK_JAVA_OPTIONS="-javaagent:.*mcrl\.jar"' "$RC_FILE" > "$RC_FILE.mcrl_tmp" \
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
                JAR_PATH="${JAR_PATH:-$APP_JAR_PATH}"
                flatpak override --user --unset-env=JDK_JAVA_OPTIONS "$APP"
                flatpak override --user --nofilesystem="$(dirname "$APP_JAR_PATH")" "$APP"
                echo "Removed the Flatpak override for $APP."
            fi
        done
    fi

    if [ "$FOUND" = "0" ]; then
        echo "Didn't find an mcrl install (no environment.d entry, no shell rc entry, no matching Flatpak override)."
        echo "Nothing to do."
        exit 0
    fi

    TARGET_DIR="${JAR_PATH:+$(dirname "$JAR_PATH")}"
    TARGET_DIR="${TARGET_DIR:-$DEFAULT_DIR}"
    if [ -d "$TARGET_DIR" ]; then
        read -r -p "Also delete $TARGET_DIR ? (y/N): " REMOVE
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
echo "Fetching mcrl.jar into $JAR_PATH ..."
if command -v curl >/dev/null 2>&1; then
    curl -fL -o "$JAR_PATH" "$JAR_URL"
elif command -v wget >/dev/null 2>&1; then
    wget -O "$JAR_PATH" "$JAR_URL"
else
    echo "Need curl or wget to download the jar, neither was found."
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo ""
    echo "Download failed, mcrl.jar isn't at $JAR_PATH."
    exit 1
fi

if has_systemd_user; then
    mkdir -p "$(dirname "$ENV_D_FILE")"
    echo "JDK_JAVA_OPTIONS=\"-javaagent:$JAR_PATH\"" > "$ENV_D_FILE"
    echo "Wrote $ENV_D_FILE (covers native, non-Flatpak launchers)."
    NATIVE_NOTE="systemd only reads environment.d at session start, so log out and back in"
else
    RC_FILE="$(detect_rc_file)"
    touch "$RC_FILE"
    if ! grep -qx "$TAG_LINE" "$RC_FILE"; then
        {
            echo ""
            echo "$TAG_LINE"
            echo "export JDK_JAVA_OPTIONS=\"-javaagent:$JAR_PATH\""
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
        flatpak override --user --env=JDK_JAVA_OPTIONS="-javaagent:$JAR_PATH" "$APP"
        flatpak override --user --filesystem="$INSTALL_DIR:ro" "$APP"
        echo "Set the Flatpak override for $APP."
    done <<< "$FLATPAK_TARGETS"
fi

echo ""
echo "Installed. JDK_JAVA_OPTIONS now points at $JAR_PATH"
echo "For native launchers, $NATIVE_NOTE. Close every Minecraft launcher"
echo "window and reopen either way."
