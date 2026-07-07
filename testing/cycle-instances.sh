#!/bin/bash
# Launches a representative spread of existing PrismLauncher instances one at a
# time, waits for you to close each one, and automatically advances to the next -
# stopping immediately if a new crash report shows up for whatever's running.
#
# Requires JDK_JAVA_OPTIONS already pointed at mcrl.jar via
#   flatpak override --user --env=JDK_JAVA_OPTIONS=... org.prismlauncher.PrismLauncher
# (already set up earlier - every instance below inherits it automatically since
# they all launch through the same Flatpak app).

set -u

INSTANCES_DIR="/var/home/sm0keskreen/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"

# name -> loader, for the summary printed at the end
declare -A LOADER=(
  ["1.17"]="Fabric"
  ["1.18"]="Fabric"
  ["1.18(1)"]="Forge"
  ["1.19"]="Fabric"
  ["1.19(1)"]="Forge"
  ["1.20"]="Fabric"
  ["1.20(1)"]="Forge"
  ["1.21.11"]="Vanilla"
  ["1.21.11(1)"]="Fabric"
  ["1.21.11(2)"]="Forge"
  ["26.2"]="Fabric"
  ["26.1.2"]="Vanilla"
  ["26.1.2(2)"]="Forge"
)

ORDER=("1.17" "1.18" "1.18(1)" "1.19" "1.19(1)" "1.20" "1.20(1)" \
       "1.21.11" "1.21.11(1)" "1.21.11(2)" "26.2" "26.1.2" "26.1.2(2)")

PASSED=()
STOPPED_AT=""

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

  # baseline: what crash reports already exist before this run
  before=""
  if [ -d "$crash_dir" ]; then
    before=$(ls -1 "$crash_dir" 2>/dev/null | sort)
  fi

  flatpak run org.prismlauncher.PrismLauncher --launch "$name" >/dev/null 2>&1

  # wait for the actual game process to appear (loading can take a while).
  # pgrep -f treats its pattern as a regex, and instance names like "1.18(1)"
  # contain literal parentheses, so the path must be regex-escaped first.
  escaped_dir=$(printf '%s' "$game_dir" | sed -e 's/[.[\*^$()+?{|\\]/\\&/g')
  echo "  Waiting for the game process to start..."
  pid=""
  for i in $(seq 1 90); do
    pid=$(pgrep -f -- "--gameDir ${escaped_dir} " | head -1)
    if [ -n "$pid" ]; then
      break
    fi
    sleep 2
  done

  if [ -z "$pid" ]; then
    echo "  FAILED TO LAUNCH: no game process appeared within 180s for $name"
    STOPPED_AT="$name (never started)"
    break
  fi

  echo "  Game running (pid $pid). Close it whenever you're done looking at it -"
  echo "  this will automatically move to the next instance."

  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
  done

  echo "  $name closed."

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

  echo "  No new crash report - clean exit. Moving on."
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
