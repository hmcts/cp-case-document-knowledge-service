#!/usr/bin/env bash
# Copies this repo's support/dashboard-kql/*.kql files to the cp-amp-terraform-az-dashboard
# queries/cdks/ folder. Run manually from a developer machine with both repos checked out as
# sibling directories. Not wired into CI or build.gradle — this is a manual sync step (DD-43432).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/dashboard-kql"
TF_REPO="$SCRIPT_DIR/../../cp-amp-terraform-az-dashboard"

if [[ ! -d "$TF_REPO" ]]; then
    echo "error: cp-amp-terraform-az-dashboard not found at $TF_REPO" >&2
    echo "clone it as a sibling of this repo, e.g.:" >&2
    echo "  git clone git@github.com:hmcts/cp-amp-terraform-az-dashboard.git $TF_REPO" >&2
    exit 1
fi

DEST="$TF_REPO/queries/cdks"
mkdir -p "$DEST"

changed=0
for src_file in "$SRC"/*.kql; do
    file_name="$(basename "$src_file")"
    dest_file="$DEST/$file_name"

    if [[ -f "$dest_file" ]] && diff -q "$src_file" "$dest_file" > /dev/null 2>&1; then
        echo "unchanged: $file_name"
        continue
    fi

    cp "$src_file" "$dest_file"
    echo "synced:    $file_name"
    changed=$((changed + 1))
done

echo ""
echo "$changed file(s) changed."

if [[ "$changed" -gt 0 ]]; then
    echo ""
    echo "Next steps:"
    echo "  1. Review the diff in $TF_REPO"
    echo "  2. Commit, push and raise a PR in cp-amp-terraform-az-dashboard"
    echo "  3. Once merged, run the Terraform pipeline in that repo to apply the changes to Azure"
    echo "  4. Confirm the tile renders correctly against a real Log Analytics workspace (AC-014)"
fi
