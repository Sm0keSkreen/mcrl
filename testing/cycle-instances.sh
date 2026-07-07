#!/bin/bash
# Launches a representative spread of PrismLauncher instances one at a time.
# For each: launch it, give it a few seconds to start, then just wait until
# nothing Prism-related is running anymore, the moment that happens, check for
# a new crash report and either move on or stop. Doesn't try to precisely catch
# the exact instance's process, just whether anything Prism-related is open.
#
# Requires JDK_JAVA_OPTIONS already pointed at mcrl.jar via
#   flatpak override --user --env=JDK_JAVA_OPTIONS=... org.prismlauncher.PrismLauncher
# (already set up earlier, every instance below inherits it automatically since
# they all launch through the same Flatpak app).

set -u

INSTANCES_DIR="/var/home/sm0keskreen/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"
STARTUP_GRACE_SECONDS=3

# name -> loader, for the summary printed at the end
declare -A LOADER=(
  ["1.17-vanilla"]="Vanilla"
  ["1.17"]="Fabric"
  ["1.18-vanilla"]="Vanilla"
  ["1.18"]="Fabric"
  ["1.18(1)"]="Forge"
  ["1.19-vanilla"]="Vanilla"
  ["1.19"]="Fabric"
  ["1.19(1)"]="Forge"
  ["1.20-vanilla"]="Vanilla"
  ["1.20"]="Fabric"
  ["1.20(1)"]="Forge"
  ["1.21.11"]="Vanilla"
  ["1.21.11(1)"]="Fabric"
  ["1.21.11(2)"]="Forge"
  ["26.2"]="Fabric"
  ["26.1.2"]="Vanilla"
  ["26.1.2(2)"]="Forge"
)

ORDER=("1.17-vanilla" "1.17" "1.18-vanilla" "1.18" "1.18(1)" \
       "1.19-vanilla" "1.19" "1.19(1)" "1.20-vanilla" "1.20" "1.20(1)" \
       "1.21.11" "1.21.11(1)" "1.21.11(2)" "26.2" "26.1.2" "26.1.2(2)")

PASSED=()
STOPPED_AT=""

is_anything_prism_open() {
  # Broader and simpler than chasing the specific instance's java process: the
  # whole PrismLauncher sandbox (launcher + game) seems to tear down together
  # when launched this way (via --launch), so just check whether anything
  # Prism-related is still around at all.
  pgrep -f "bwrap.*prismlauncher|PrismLauncher" >/dev/null 2>&1
}

for name in "${ORDER[@]}"; do
  loader="${LOADER[$name]}"
  game_dir="$INSTANCES_DIR/$name/minecraft"
  crash_dir="$game_dir/crash-reports"

  echo ""
  echo "=================================================="
  echo "  Launching: $name ($loader)"
  echo "=================================================="

  if [ ! -d "$game_dir" ]; then
    echo "  SKIP: instance directory not found at $game_dir"
    continue
  fi

  before=""
  if [ -d "$crash_dir" ]; then
    before=$(ls -1 "$crash_dir" 2>/dev/null | sort)
  fi

  flatpak run org.prismlauncher.PrismLauncher --launch "$name" >/dev/null 2>&1

  echo "  Giving it ${STARTUP_GRACE_SECONDS}s to start, then watching until nothing Prism-related is open..."
  sleep "$STARTUP_GRACE_SECONDS"

  while is_anything_prism_open; do
    sleep 1
  done

  echo "  $name is no longer running."

  after=""
  if [ -d "$crash_dir" ]; then
    after=$(ls -1 "$crash_dir" 2>/dev/null | sort)
  fi
  new_crashes=$(comm -13 <(echo "$before") <(echo "$after"))

  if [ -n "$new_crashes" ]; then
    echo ""
    echo "  !!! CRASH DETECTED on $name ($loader) !!!"
    echo "  New crash report(s):"
    while IFS= read -r f; do
      echo "    $crash_dir/$f"
    done <<< "$new_crashes"
    STOPPED_AT="$name ($loader)"
    break
  fi

  echo "  No new crash report, clean exit. Moving on."
  PASSED+=("$name ($loader)")
done

echo ""
echo "=================================================="
echo "  SUMMARY"
echo "=================================================="
echo "  Passed (${#PASSED[@]}):"
for p in "${PASSED[@]}"; do
  echo "    - $p"
done
if [ -n "$STOPPED_AT" ]; then
  echo "  Stopped at: $STOPPED_AT"
else
  echo "  All instances completed with no crashes."
fi
