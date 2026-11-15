#!/usr/bin/env bash
# Run from WSL. Windows remains the editing checkout; each run gets a fresh ext4 snapshot.
set -euo pipefail

if (( $# > 1 )); then
    printf 'Usage: bash scripts/test-wsl.sh [lab-directory]\n' >&2
    exit 2
fi

for tool in git rsync findmnt realpath mktemp sha256sum java javac; do
    command -v "$tool" >/dev/null || { printf 'Missing tool: %s\n' "$tool" >&2; exit 2; }
done

source_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)"
lab_root="${1:-$HOME/quorumlab-lab}"
mkdir -p -- "$lab_root"
lab_root="$(realpath -e -- "$lab_root")"

require_ext4() {
    local filesystem
    filesystem="$(findmnt -n -o FSTYPE -T "$1")"
    if [[ "$filesystem" != ext4 ]]; then
        printf 'Expected ext4 for the Linux lab, got %s at %s. Do not use /mnt/c.\n' \
            "$filesystem" "$1" >&2
        exit 2
    fi
}

require_ext4 "$lab_root"
if [[ "$lab_root" == "$source_root" || "$lab_root" == "$source_root/"* ]]; then
    printf 'The lab must be outside the editing checkout.\n' >&2
    exit 2
fi

mkdir -p -- "$lab_root/runs" "$lab_root/data"
require_ext4 "$lab_root/runs"
require_ext4 "$lab_root/data"
run_dir="$(mktemp -d "$lab_root/runs/run-XXXXXXXX")"
snapshot="$run_dir/source"
mkdir -- "$snapshot"

# Include current edits and new non-ignored files. Skip deleted tracked files.
# No --delete: prior runs and future node data must never be overwritten by this script.
git -C "$source_root" ls-files --cached --others --exclude-standard --deduplicate -z |
    while IFS= read -r -d '' relative; do
        if [[ -e "$source_root/$relative" || -L "$source_root/$relative" ]]; then
            printf '%s\0' "$relative"
        fi
    done > "$run_dir/files.list0"
rsync -a --from0 --files-from="$run_dir/files.list0" -- "$source_root/" "$snapshot/"
chmod +x "$snapshot/gradlew"

{
    printf 'Source checkout: %s\nSnapshot: %s\nData directory: %s\n' \
        "$source_root" "$snapshot" "$lab_root/data"
    printf 'Source HEAD: '
    git -C "$source_root" rev-parse HEAD
    printf '\nSource working tree (empty means clean):\n'
    git -C "$source_root" status --short
    printf '\nKernel:\n'
    uname -r
    printf '\nDistribution:\n'
    cat /etc/os-release
    printf '\nFilesystem:\n'
    findmnt -T "$snapshot" -o TARGET,SOURCE,FSTYPE,OPTIONS
    printf '\nJava:\n'
    java -version 2>&1
} > "$run_dir/environment.txt"

(
    cd "$snapshot"
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
) > "$run_dir/source.sha256"

printf 'Linux snapshot: %s\nEvidence: %s\n' "$snapshot" "$run_dir"
# Filesystem-specific checks must execute here, not reuse test results from another environment.
if (cd "$snapshot" && ./gradlew --no-daemon --no-build-cache --console=plain --warning-mode=all clean test build) \
    2>&1 | tee "$run_dir/build.log"; then
    printf '\nBuild passed on ext4. Future storage data: %s/data\n' "$lab_root"
else
    printf '\nBuild failed. Evidence retained at %s\n' "$run_dir" >&2
    exit 1
fi
