#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROPERTIES_FILE="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [[ ! -f "$PROPERTIES_FILE" ]]; then
    echo "Missing $PROPERTIES_FILE" >&2
    exit 1
fi

distribution_url="$(
    sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | tail -n 1 | sed 's#\\:#:#g'
)"

if [[ -z "$distribution_url" ]]; then
    echo "Missing distributionUrl in $PROPERTIES_FILE" >&2
    exit 1
fi

distribution_zip="${distribution_url##*/}"
distribution_name="${distribution_zip%.zip}"
gradle_user_home="${GRADLE_USER_HOME:-$APP_HOME/.gradle}"
dist_root="$gradle_user_home/wrapper/dists/$distribution_name"
install_root="$dist_root/$distribution_name"
zip_path="$dist_root/$distribution_zip"

download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$distribution_url" -o "$zip_path"
        return
    fi
    if command -v wget >/dev/null 2>&1; then
        wget -qO "$zip_path" "$distribution_url"
        return
    fi
    echo "Neither curl nor wget is available to download $distribution_url" >&2
    exit 1
}

extract() {
    local tmp_dir
    tmp_dir="$dist_root/.extract-$distribution_name"
    rm -rf "$tmp_dir"
    mkdir -p "$tmp_dir"
    unzip -q -o "$zip_path" -d "$tmp_dir"
    local extracted_dir
    extracted_dir="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    if [[ -z "$extracted_dir" ]]; then
        echo "Failed to extract $zip_path" >&2
        exit 1
    fi
    rm -rf "$install_root"
    mv "$extracted_dir" "$install_root"
    rm -rf "$tmp_dir"
}

mkdir -p "$dist_root"

if [[ ! -x "$install_root/bin/gradle" ]]; then
    if [[ ! -f "$zip_path" ]]; then
        download
    fi
    extract
fi

exec "$install_root/bin/gradle" "$@"
