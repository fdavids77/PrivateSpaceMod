#!/usr/bin/env bash
set -euo pipefail

# deploy.sh — Push PrivateSpaceMod to GitHub from Termux
#
# Usage:
#   ./deploy.sh                    # Push to main, CI builds module
#   ./deploy.sh --release v2.1     # Tag + push, CI creates GitHub Release
#   ./deploy.sh --message "fix X"  # Custom commit message
#
# Prerequisites:
#   pkg install git openssh
#   SSH key configured for GitHub

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_NAME="$(basename "$0")"
readonly REPO_URL="git@github.com:fdavids77/PrivateSpaceMod.git"

usage() {
    cat <<EOF
Usage: $SCRIPT_NAME [options]

Options:
    -m, --message MSG     Commit message (default: "update PrivateSpaceMod")
    -r, --release TAG     Create a tagged release (e.g., v2.1)
    -h, --help            Show this help

Examples:
    $SCRIPT_NAME                          # Push changes, CI builds
    $SCRIPT_NAME -m "fix double-tap"      # Push with custom message
    $SCRIPT_NAME --release v2.1           # Tag + push → GitHub Release
EOF
}

log() { echo "[$(date '+%H:%M:%S')] $*"; }
err() { echo "[ERROR] $*" >&2; }
die() { err "$*"; exit 1; }

main() {
    local message="update PrivateSpaceMod"
    local release_tag=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            -m|--message) message="$2"; shift 2 ;;
            -r|--release) release_tag="$2"; shift 2 ;;
            -h|--help) usage; exit 0 ;;
            -*) die "Unknown option: $1" ;;
            *) break ;;
        esac
    done

    cd "$SCRIPT_DIR"

    # Initialize git repo if needed
    if [ ! -d .git ]; then
        log "Initializing git repository..."
        git init
        git remote add origin "$REPO_URL"
        git branch -M main
    fi

    # Stage all changes
    git add -A

    # Check if there are changes
    if git diff --cached --quiet 2>/dev/null; then
        log "No changes to commit"
        if [ -n "$release_tag" ]; then
            log "Creating release tag only..."
        else
            exit 0
        fi
    else
        log "Committing: $message"
        git commit -m "$message"
    fi

    # Push to main
    log "Pushing to GitHub..."
    git push -u origin main

    # Create release tag if requested
    if [ -n "$release_tag" ]; then
        log "Creating release tag: $release_tag"
        git tag -a "$release_tag" -m "Release $release_tag"
        git push origin "$release_tag"
        log "Release tag pushed — GitHub Actions will create the release"
        log "Check: https://github.com/fdavids77/PrivateSpaceMod/actions"
    fi

    log "Done! CI will build the module."
    log "Download: https://github.com/fdavids77/PrivateSpaceMod/actions"
}

main "$@"
