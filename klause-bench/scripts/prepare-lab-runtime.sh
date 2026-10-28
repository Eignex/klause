#!/usr/bin/env bash
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Lab MiniZinc runtime requires Linux x86_64' >&2; exit 1; }
destination=$1
version=2.10.1
checksum=b63dd88491d47b05d0eb0ff4aa1112d1e6f77292c023800b20b98851458ce01c
mkdir -p "$destination"
if [[ -f "$destination/sha256" && $(cat "$destination/sha256") == "$checksum" && -x "$destination/bin/minizinc" ]]; then exit 0; fi
scratch=$(mktemp -d "$destination/stage.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
archive="MiniZincIDE-$version-x86_64-linux-gnu"
curl -fsSL --retry 3 "https://github.com/MiniZinc/MiniZincIDE/releases/download/$version/$archive.tgz" -o "$scratch/runtime.tgz"
printf '%s  %s\n' "$checksum" "$scratch/runtime.tgz" | sha256sum --check --status
tar xzf "$scratch/runtime.tgz" -C "$scratch" "$archive/bin/minizinc" "$archive/share/minizinc/std"
mkdir -p "$destination/bin" "$destination/share/minizinc"
cp "$scratch/$archive/bin/minizinc" "$destination/bin/minizinc"
rm -rf "$destination/share/minizinc/std"
cp -R "$scratch/$archive/share/minizinc/std" "$destination/share/minizinc/std"
printf '%s\n' "$checksum" > "$destination/sha256"
"$destination/bin/minizinc" --version
