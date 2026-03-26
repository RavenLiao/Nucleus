#!/bin/bash
# Compiles nucleus_notification_linux.c into a shared library for the current architecture.
# The output is placed in the JAR resources so it ships with the library.
#
# Prerequisites: gcc, libgio-2.0-dev (or glib2-devel on Fedora).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_notification_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"

# Detect architecture
ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  RESOURCE_ARCH="linux-x64" ;;
    aarch64) RESOURCE_ARCH="linux-aarch64" ;;
    *)       echo "ERROR: Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

OUT_DIR="$RESOURCE_DIR/$RESOURCE_ARCH"

# Detect JAVA_HOME for JNI headers
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-arm64 \
                     /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-arm64 \
                     /usr/lib/jvm/java /usr/lib/jvm/default-java; do
        if [ -d "$candidate/include" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and no JDK found in common locations." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"

if [ ! -d "$JNI_INCLUDE" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

# Resolve GIO flags via pkg-config
if ! command -v pkg-config &>/dev/null; then
    echo "ERROR: pkg-config not found." >&2
    exit 1
fi

GIO_CFLAGS=$(pkg-config --cflags gio-2.0)
GIO_LIBS=$(pkg-config --libs gio-2.0)

mkdir -p "$OUT_DIR"

echo "Compiling for $ARCH ($RESOURCE_ARCH)..."

# shellcheck disable=SC2086
gcc -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
    $GIO_CFLAGS \
    -O2 \
    -fvisibility=hidden \
    -ffunction-sections \
    -fdata-sections \
    -Wl,--gc-sections \
    -Wl,-s \
    -lpthread \
    -o "$OUT_DIR/libnucleus_notification_linux.so" \
    "$SRC" \
    $GIO_LIBS

echo "Built Linux shared library:"
ls -lh "$OUT_DIR/libnucleus_notification_linux.so"

# Clear NativeLibraryLoader cache so the new .so is picked up at next run
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/nucleus/native"
CACHED="$CACHE_BASE/$RESOURCE_ARCH/libnucleus_notification_linux.so"
if [ -f "$CACHED" ]; then
    rm -f "$CACHED"
    echo "Cleared cache: $CACHED"
fi
