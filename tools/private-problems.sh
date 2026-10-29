#!/bin/bash
# Runs a command on a private problem set (waza), which is not kept in this repository.
#   tools/private-problems.sh <set> <command...>
# The problems come from $MCTAS_PRIVATE_PROBLEMS (default /mnt/project-files/benchmarks/waza):
# the files <set>*.problem.json there are linked into a temporary directory, and an argument
# "{}" in the command is replaced by that directory. When the problems are missing, the set is
# skipped with a note and the script succeeds.
#   e.g. tools/private-problems.sh waza1 planner/build/install/mctas-planner/bin/mctas-planner solve {} out --budget 120
set -e
src="${MCTAS_PRIVATE_PROBLEMS:-/mnt/project-files/benchmarks/waza}"
set_name="$1"
shift
files=("$src/$set_name"*.problem.json)
if [ ! -e "${files[0]}" ]; then
  echo "skipping $set_name: no private problems at $src (set MCTAS_PRIVATE_PROBLEMS)" >&2
  exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for f in "${files[@]}"; do ln -s "$f" "$tmp/"; done
args=()
for a in "$@"; do [ "$a" = "{}" ] && args+=("$tmp") || args+=("$a"); done
"${args[@]}"
