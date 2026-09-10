#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
if [ "$#" -ne 2 ]; then
  printf '%s\n' 'Usage: sh tools/gunpack/install.sh <built-pack.zip> <game-directory/tacz>' >&2
  exit 2
fi
pack=$1
target=$2
if [ ! -f "$pack" ] || [ ! -d "$target/tacz_default_gun" ]; then
  printf '%s\n' 'The built ZIP and the target default gunpack directory must already exist; nothing was installed.' >&2
  exit 1
fi
destination=$target/bolt_action_mesh-1.0.0-local.zip
temporary=$(mktemp "$target/.bolt_action_mesh.XXXXXX")
trap 'rm -f "$temporary"' EXIT HUP INT TERM
cp "$pack" "$temporary"
# Validate the copied bytes that will be installed, plus actual target dependencies.
# A source ZIP changing during validation must not change the installed payload.
./gradlew --offline --no-daemon -I tools/gunpack/gunpack.init.gradle validateMeshGunpack \
  "-PmeshPack=$temporary" "-PmeshDefaultPack=$target/tacz_default_gun" --console=plain
mv -f "$temporary" "$destination"
printf '%s\n' "Installed independent gunpack: $destination"
