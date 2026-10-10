#!/usr/bin/env bash
# MiniZinc Challenge pairwise scoring of saved records, preserving exact objective quality.
set -eu
exec python3 "$(dirname "$0")/../tools/compare_records.py" "$@"
