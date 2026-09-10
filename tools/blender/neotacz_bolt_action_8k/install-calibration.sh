#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
# Compatibility entry only. Installation now accepts a validated independent ZIP.
exec sh tools/gunpack/install.sh "$@"
