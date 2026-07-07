#!/usr/bin/env bash
# mcrl installer/uninstaller for Linux and macOS.

set -u

JAR_URL="https://raw.githubusercontent.com/Sm0keSkreen/mcrl/master/mcrl.jar"
DEFAULT_DIR="$HOME/.local/share/mcrl"
TAG_LINE="# mcrl (added by install.sh)"

# Known Flatpak Minecraft launchers. Flatpak apps don't see the host shell's
# environment at all, so JDK_JAVA_OPTIONS from the rc file below never reaches
# them, each one needs its own explicit override.
KNOWN_FLATPAK_APPS="org.prismlauncher.PrismLauncher org.polymc.PolyMC com.modrinth.ModrinthApp com.mojang.Minecraft"

detect_rc_file() {
    case "$(basename "${SHELL:-}")" in
        zsh) echo "$HOME/.zshrc" ;;
        bash) echo "$HOME/.bashrc" ;;
        *) echo "$HOME/.profile" ;;
    esac
}

is_flatpak_app_installed() {
    command -v flatpak >/dev/null 2>&1 && flatpak list --app --columns=application 2>/dev/null | grep -qx "$1"
}

installed_flatpak_targets() {
    local app
    for app in $KNOWN_FLATPAK_APPS; do
        if is_flatpak_app_installed "$app"; then
            echo "$app"
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

    if [ -f "$RC_FILE" ]; then
        LINE=$(grep -o 'javaagent:[^"]*mcrl\.jar' "$RC_FILE" | head -n1 || true)
        if [ -n "$LINE" ]; then
            FOUND=1
            JAR_PATH="${LINE#javaagent:}"
            grep -vx -e "$TAG_LINE" -e 'export JDK_JAVA_OPTIONS="-javaagent:.*mcrl\.jar"' "$RC_FILE" > "$RC_FILE.mcrl_tmp" \
                && mv "$RC_FILE.mcrl_tmp" "$RC_FILE"
            echo "Removed the JDK_JAVA_OPTIONS line from $RC_FILE."
        fi
    fi

    for APP in $(installed_flatpak_targets); do
        CURRENT_OVERRIDE=$(flatpak override --user --show "$APP" 2>/dev/null | grep '^JDK_JAVA_OPTIONS=' || true)
        if echo "$CURRENT_OVERRIDE" | grep -q 'mcrl\.jar'; then
            FOUND=1
            if [ -z "$JAR_PATH" ]; then
                JAR_PATH="${CURRENT_OVERRIDE#JDK_JAVA_OPTIONS=-javaagent:}"
            fi
            flatpak override --user --unset-env=JDK_JAVA_OPTIONS "$APP"
            echo "Removed the Flatpak override for $APP."
        fi
    done

    read -r -p "Any other Flatpak launcher app ID to check (Enter to skip): " EXTRA_APP
    if [ -n "$EXTRA_APP" ] && is_flatpak_app_installed "$EXTRA_APP"; then
        CURRENT_OVERRIDE=$(flatpak override --user --show "$EXTRA_APP" 2>/dev/null | grep '^JDK_JAVA_OPTIONS=' || true)
        if echo "$CURRENT_OVERRIDE" | grep -q 'mcrl\.jar'; then
            FOUND=1
            flatpak override --user --unset-env=JDK_JAVA_OPTIONS "$EXTRA_APP"
            echo "Removed the Flatpak override for $EXTRA_APP."
        fi
    fi

    if [ "$FOUND" = "0" ]; then
        echo "Didn't find an mcrl install (no shell rc entry, no matching Flatpak override)."
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

FLATPAK_TARGETS="$(installed_flatpak_targets)"
if [ -n "$FLATPAK_TARGETS" ]; then
    for APP in $FLATPAK_TARGETS; do
        flatpak override --user --env=JDK_JAVA_OPTIONS="-javaagent:$JAR_PATH" "$APP"
        echo "Also set the Flatpak override for $APP (Flatpak apps don't see your shell's environment otherwise)."
    done
fi

read -r -p "Any other Flatpak launcher app ID to cover (Enter to skip): " EXTRA_APP
if [ -n "$EXTRA_APP" ]; then
    if is_flatpak_app_installed "$EXTRA_APP"; then
        flatpak override --user --env=JDK_JAVA_OPTIONS="-javaagent:$JAR_PATH" "$EXTRA_APP"
        echo "Also set the Flatpak override for $EXTRA_APP."
    else
        echo "Didn't find a Flatpak app installed with ID $EXTRA_APP, skipped."
    fi
fi

echo ""
echo "Installed. JDK_JAVA_OPTIONS now points at $JAR_PATH"
echo "Open a new terminal (so $RC_FILE gets re-read), close every Minecraft"
echo "launcher window, and reopen."
