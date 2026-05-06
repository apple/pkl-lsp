#!/bin/bash

set -eo pipefail

VERSION="$1"
if [ -z "$VERSION" ]; then
  echo "Usage: update_zig.sh <version>"
  exit 1
fi

if ! which minisign > /dev/null 2> /dev/null; then
  echo "minisign is required to verify zig updates"
  echo "Install via homebrew or https://jedisct1.github.io/minisign/"
  exit 1
fi

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
GRADLE_VERSIONS="$SCRIPT_DIR/../gradle/libs.versions.toml"
DIR="$(mktemp -d -t pkl-lsp-zig)"
echo "+ cd $DIR"
cd "$DIR"

VARIANTS=(
  "macos-aarch64"
  "linux-amd64"
  "windows-amd64"
  "linux-aarch64"
)

FILENAMES=(
  "zig-aarch64-macos-$VERSION.tar.xz"
  "zig-x86_64-linux-$VERSION.tar.xz"
  "zig-x86_64-windows-$VERSION.zip"
  "zig-aarch64-linux-$VERSION.tar.xz"
)

# from https://ziglang.org/download/
PUBKEY="RWSGOq2NVecA2UPNdBUZykf1CCb147pkmdtYxgb3Ti+JO/wCYvhbAb/U"

MIRROR="https://zigmirror.com" # this one is fast!
#MIRROR="$(curl -sSL https://ziglang.org/download/community-mirrors.txt | sort -R | head -n 1)"

DOWNLOAD_ARGS="--parallel"
for i in "${!FILENAMES[@]}"; do
  DOWNLOAD_ARGS="$DOWNLOAD_ARGS -LO $MIRROR/${FILENAMES[$i]}"
  DOWNLOAD_ARGS="$DOWNLOAD_ARGS -LO $MIRROR/${FILENAMES[$i]}.minisig"
done

echo "+ curl $DOWNLOAD_ARGS"
# shellcheck disable=SC2086
curl $DOWNLOAD_ARGS

for i in "${!FILENAMES[@]}"; do
  echo "Verifying ${FILENAMES[$i]}"
  minisign -Vm "${FILENAMES[$i]}" -P "$PUBKEY"
done

echo "Updating version catalog"
perl -pi -e "s/zig = \"[^\"]+\"/zig = \"$VERSION\"/" "$GRADLE_VERSIONS"
for i in "${!FILENAMES[@]}"; do
  echo "[$i/${#FILENAMES[@]}]   Hashing ${FILENAMES[$i]}"
  HASH="$(sha256sum "${FILENAMES[$i]}" | awk '{print $1}')"
  perl -pi -e "s/zigSha256-${VARIANTS[$i]} = \"[a-z0-9]+\"/zigSha256-${VARIANTS[$i]} = \"$HASH\"/" "$GRADLE_VERSIONS"
done

echo "Cleaning up"
#rm -rf "$DIR"

echo "Done!"
