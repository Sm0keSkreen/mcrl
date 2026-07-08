#!/usr/bin/env bash
# Cuts a full mcrl release: bumps the version, builds and tests, tags, publishes the GitHub
# release with mcrl.jar/Mcrl.sh/Mcrl.bat/SHA256SUMS.txt, then updates and pushes every live
# package-manager repo (Homebrew, Scoop, Nix) to match. One command instead of the ~15 manual
# steps this used to take, specifically so a version bump in one place is never forgotten in
# another (that gap caused real breakage more than once).
#
# Usage: scripts/release.sh <new-version>   e.g. scripts/release.sh 1.3.4
#
# Sibling repo checkouts are auto-cloned next to this repo if not already present; override
# their locations with MCRL_HOMEBREW_REPO / MCRL_SCOOP_REPO / MCRL_NIX_REPO env vars.
set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <new-version>  (e.g. $0 1.3.4, no leading v)"
    exit 1
fi
VERSION="$1"
TAG="v$VERSION"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT_DIR="$(dirname "$REPO_ROOT")"
HOMEBREW_REPO="${MCRL_HOMEBREW_REPO:-$PARENT_DIR/homebrew-mcrl}"
SCOOP_REPO="${MCRL_SCOOP_REPO:-$PARENT_DIR/scoop-mcrl}"
NIX_REPO="${MCRL_NIX_REPO:-$PARENT_DIR/nix-mcrl}"

cd "$REPO_ROOT"

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree isn't clean, commit or stash first."
    exit 1
fi
git fetch origin master
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/master)" ]; then
    echo "Local HEAD differs from origin/master, push or pull first."
    exit 1
fi

clone_if_missing() {
    local dir="$1" repo="$2"
    if [ ! -d "$dir" ]; then
        echo "== cloning $repo into $dir (not found locally) =="
        gh repo clone "$repo" "$dir"
    fi
}
clone_if_missing "$HOMEBREW_REPO" Sm0keSkreen/homebrew-mcrl
clone_if_missing "$SCOOP_REPO" Sm0keSkreen/scoop-mcrl
clone_if_missing "$NIX_REPO" Sm0keSkreen/nix-mcrl

echo "== bumping version to $VERSION in agent/build.gradle =="
sed -i "s/^version = '.*'/version = '$VERSION'/" agent/build.gradle
git add agent/build.gradle
git commit -m "Bump version to $VERSION"
git push origin master

echo "== building and testing =="
(cd agent && ./gradlew clean shadowJar test)

JAR_SHA=$(sha256sum agent/build/libs/mcrl.jar | cut -d' ' -f1)

echo "== tagging $TAG =="
git tag "$TAG"
git push origin "$TAG"

sha256sum agent/build/libs/mcrl.jar Mcrl.sh Mcrl.bat | sed 's#agent/build/libs/##' > SHA256SUMS.txt

echo "== creating GitHub release =="
read -r -p "Release notes (one line; edit further in the GitHub UI afterward if needed): " NOTES
gh release create "$TAG" agent/build/libs/mcrl.jar Mcrl.sh Mcrl.bat SHA256SUMS.txt \
    --title "Mcrl $TAG" \
    --notes "${NOTES:-See commit history for what changed.}"
rm SHA256SUMS.txt

echo "== waiting for the release CDN to catch up =="
for _ in 1 2 3 4 5 6; do
    LIVE_SHA=$(curl -fsSL "https://github.com/Sm0keSkreen/mcrl/releases/latest/download/mcrl.jar" | sha256sum | cut -d' ' -f1)
    [ "$LIVE_SHA" = "$JAR_SHA" ] && break
    sleep 5
done
if [ "$LIVE_SHA" != "$JAR_SHA" ]; then
    echo "  WARNING: releases/latest/download/mcrl.jar still doesn't match after waiting."
    echo "  Expected $JAR_SHA, got $LIVE_SHA. Check the release manually before trusting it."
else
    echo "  Confirmed: releases/latest/download/mcrl.jar matches what was just built."
fi

echo "== updating Homebrew tap =="
sed -i "s#releases/download/v[0-9.]*/mcrl\.jar#releases/download/$TAG/mcrl.jar#" "$HOMEBREW_REPO/Formula/mcrl.rb"
sed -i "s/sha256 \"[a-f0-9]*\"/sha256 \"$JAR_SHA\"/" "$HOMEBREW_REPO/Formula/mcrl.rb"
( cd "$HOMEBREW_REPO" && brew style Formula/mcrl.rb && git add Formula/mcrl.rb \
    && git commit -m "Update to $TAG" && git push origin main )

echo "== updating Scoop bucket =="
sed -i "s/\"version\": \"[0-9.]*\"/\"version\": \"$VERSION\"/" "$SCOOP_REPO/bucket/mcrl.json"
sed -i "s#releases/download/v[0-9.]*/mcrl\.jar#releases/download/$TAG/mcrl.jar#" "$SCOOP_REPO/bucket/mcrl.json"
sed -i "s/\"hash\": \"[a-f0-9]*\"/\"hash\": \"$JAR_SHA\"/" "$SCOOP_REPO/bucket/mcrl.json"
( cd "$SCOOP_REPO" && jq . bucket/mcrl.json > /dev/null && git add bucket/mcrl.json \
    && git commit -m "Update to $TAG" && git push origin main )

echo "== updating Nix flake =="
JAR_SHA_SRI="sha256-$(echo -n "$JAR_SHA" | xxd -r -p | base64)"
sed -i "s#releases/download/v[0-9.]*/mcrl\.jar#releases/download/$TAG/mcrl.jar#" "$NIX_REPO/flake.nix"
sed -i "s#hash = \"sha256-[^\"]*\"#hash = \"$JAR_SHA_SRI\"#" "$NIX_REPO/flake.nix"
sed -i "s/version = \"[0-9.]*\"/version = \"$VERSION\"/" "$NIX_REPO/flake.nix"
( cd "$NIX_REPO" && git add flake.nix && git commit -m "Update to $TAG" && git push origin main )

echo ""
echo "Done. $TAG released, jar sha256 $JAR_SHA, Homebrew/Scoop/Nix repos updated and pushed."
echo "Chocolatey (packaging/chocolatey) and the .deb build (packaging/deb) are NOT auto-updated"
echo "by this script since neither is published anywhere yet; bump their hardcoded"
echo "version/hash by hand if you're about to actually publish or test-build one of them."
