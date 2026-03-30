#!/bin/bash
# Compiles nucleus_layout_direction_linux.c into a shared library for the host architecture.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_layout_direction_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"

# --- Detect host architecture ---
HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    x86_64)  RESOURCE_ARCH="linux-x64"     ;;
    aarch64) RESOURCE_ARCH="linux-aarch64"  ;;
    *)
        echo "ERROR: Unsupported architecture: $HOST_ARCH" >&2
        exit 1
        ;;
esac

OUT_DIR="$RESOURCE_DIR/$RESOURCE_ARCH"

# --- Locate JAVA_HOME ---
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-*-openjdk-* \
                     /usr/lib/jvm/java-21-openjdk-amd64 \
                     /usr/lib/jvm/java-17-openjdk-amd64 \
                     /usr/lib/jvm/default-java; do
        if [ -d "$candidate/include" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi

if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and auto-detection failed." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"

if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "=== Building layout direction native library ($RESOURCE_ARCH) ==="

gcc -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
    -O2 -flto \
    -fvisibility=hidden \
    -Wl,--gc-sections -Wl,--strip-all \
    -Wall -Wextra -Werror \
    -o "$OUT_DIR/libnucleus_layout_direction.so" \
    "$SRC" \
    -ldl

echo "Built: $OUT_DIR/libnucleus_layout_direction.so ($(wc -c < "$OUT_DIR/libnucleus_layout_direction.so") bytes)"

# Clear cached library so the new build is picked up
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/nucleus/native"
CACHED="$CACHE_BASE/$RESOURCE_ARCH/libnucleus_layout_direction.so"
if [ -f "$CACHED" ]; then
    rm -f "$CACHED"
    echo "Cleared cache: $CACHED"
fi
