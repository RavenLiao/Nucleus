#!/bin/bash
# Compiles nucleus_global_hotkey_linux.c into a shared library.
# Links: libX11 (X11 backend), gio-2.0 (portal/Wayland backend).
# Prerequisites: gcc, libx11-dev, libglib2.0-dev, pkg-config

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_global_hotkey_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"

if [ -z "${JAVA_HOME:-}" ]; then
    for jdir in /usr/lib/jvm/java-*-openjdk-* /usr/lib/jvm/java-*/; do
        [ -d "$jdir/include" ] && { JAVA_HOME="$jdir"; break; }
    done
fi
[ -z "${JAVA_HOME:-}" ] && { echo "ERROR: JAVA_HOME not set." >&2; exit 1; }

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"
[ -d "$JNI_INCLUDE" ] || { echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2; exit 1; }

GIO_CFLAGS=$(pkg-config --cflags gio-2.0)
GIO_LIBS=$(pkg-config --libs gio-2.0)

ARCH="$(uname -m)"
case "$ARCH" in
    x86_64)  OUT_SUBDIR="linux-x64" ;;
    aarch64) OUT_SUBDIR="linux-aarch64" ;;
    *)       echo "ERROR: Unsupported arch: $ARCH" >&2; exit 1 ;;
esac

OUT_DIR="$RESOURCE_DIR/$OUT_SUBDIR"
mkdir -p "$OUT_DIR"
LIB_NAME="libnucleus_global_hotkey.so"

gcc -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
    $GIO_CFLAGS \
    -Os -flto -fvisibility=hidden \
    -Wl,--gc-sections -Wl,--strip-all \
    -o "$OUT_DIR/$LIB_NAME" "$SRC" \
    -lX11 -lpthread $GIO_LIBS

# Clear NativeLibraryLoader cache
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/nucleus/native/$OUT_SUBDIR"
[ -f "$CACHE_DIR/$LIB_NAME" ] && rm -f "$CACHE_DIR/$LIB_NAME" && echo "Cleared cached $CACHE_DIR/$LIB_NAME"

echo "Built: $(ls -lh "$OUT_DIR/$LIB_NAME")"
