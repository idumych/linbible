#!/usr/bin/env bash
# Builds LinBible-x86_64.AppImage:
#   1. fetches the pinned And Bible sources (+ JSword submodule) and build tools if missing
#   2. builds the BibleView Vue.js frontend
#   3. builds the Kotlin/JavaFX desktop app
#   4. jlinks a minimal Java runtime (incl. JavaFX WebView)
#   5. assembles the AppDir and packs it with appimagetool
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
BUILD="$ROOT/build"
APPDIR="$BUILD/LinBible.AppDir"

UPSTREAM_REPO="https://github.com/AndBible/and-bible.git"
UPSTREAM_COMMIT="7e73c11508f32e4f531d531f6d5b110dd2a1f204"
JDK_URL="https://github.com/bell-sw/Liberica/releases/download/21.0.12.1+1/bellsoft-jdk21.0.12.1+1-linux-amd64-full.tar.gz"
JDK_SHA1="a119fa045f8f6f436e7097173a469cf977346c5b"
JDK_DIR="$TOOLS/jdk-21.0.12.1-full"
APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/desktop/build.gradle.kts")"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- sources & tools
if [ ! -d "$ROOT/upstream/.git" ]; then
    step "Cloning And Bible ($UPSTREAM_COMMIT)"
    git clone "$UPSTREAM_REPO" "$ROOT/upstream"
    git -C "$ROOT/upstream" checkout "$UPSTREAM_COMMIT"
fi
if [ ! -f "$ROOT/upstream/jsword/build.gradle.kts" ]; then
    step "Fetching JSword submodule"
    git -C "$ROOT/upstream" submodule update --init --depth 1
fi

mkdir -p "$TOOLS"
if [ ! -x "$JDK_DIR/bin/java" ]; then
    step "Downloading Liberica JDK 21 (with JavaFX)"
    curl -fL -o "$TOOLS/jdk.tgz" "$JDK_URL"
    echo "$JDK_SHA1  $TOOLS/jdk.tgz" | sha1sum -c -
    tar -xzf "$TOOLS/jdk.tgz" -C "$TOOLS"
    rm "$TOOLS/jdk.tgz"
fi
if [ ! -x "$TOOLS/appimagetool" ]; then
    step "Downloading appimagetool"
    curl -fL -o "$TOOLS/appimagetool" "$APPIMAGETOOL_URL"
    chmod +x "$TOOLS/appimagetool"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------------------------------------------------------------- build
step "Building BibleView frontend"
"$ROOT/scripts/build-frontend.sh"

step "Building LinBible"
(cd "$ROOT/desktop" && ./gradlew --no-daemon -q clean installDist)
DIST="$ROOT/desktop/build/install/linbible"

# ---------------------------------------------------------------- runtime
step "Creating Java runtime with jlink"
rm -rf "$BUILD"
mkdir -p "$BUILD"
MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.scripting,java.xml,java.prefs,java.sql,\
jdk.httpserver,jdk.jsobject,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs,\
javafx.base,javafx.graphics,javafx.controls,javafx.web,javafx.media,javafx.swing"
jlink --module-path "$JAVA_HOME/jmods" --add-modules "$MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output "$APPDIR/usr/lib/linbible/runtime"

# ---------------------------------------------------------------- AppDir
step "Assembling AppDir"
mkdir -p "$APPDIR/usr/lib/linbible/lib" "$APPDIR/usr/bin" \
    "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps" "$APPDIR/usr/share/icons/hicolor/scalable/apps" \
    "$APPDIR/usr/share/metainfo"
cp "$DIST"/lib/*.jar "$APPDIR/usr/lib/linbible/lib/"

cat > "$APPDIR/usr/bin/linbible" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
LIB="$HERE/../lib/linbible"
exec "$LIB/runtime/bin/java" \
    -Xss4m -XX:+UseSerialGC -Xmx1g \
    -Dprism.lcdtext=false \
    -Djdk.gtk.version=3 \
    $LINBIBLE_JAVA_OPTS \
    -cp "$LIB/lib/*" org.linbible.MainAppKt "$@"
EOF
chmod +x "$APPDIR/usr/bin/linbible"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/linbible" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cp "$ROOT/packaging/linbible.desktop" "$APPDIR/linbible.desktop"
cp "$ROOT/packaging/linbible.desktop" "$APPDIR/usr/share/applications/"
cp "$ROOT/desktop/src/main/resources/icons/linbible-256.png" "$APPDIR/linbible.png"
cp "$ROOT/desktop/src/main/resources/icons/linbible-256.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/linbible.png"
cp "$ROOT/packaging/linbible.svg" "$APPDIR/usr/share/icons/hicolor/scalable/apps/linbible.svg"
ln -sf linbible.png "$APPDIR/.DirIcon"
sed "s/@VERSION@/$VERSION/" "$ROOT/packaging/org.linbible.LinBible.appdata.xml" > "$APPDIR/usr/share/metainfo/org.linbible.LinBible.appdata.xml"
cp "$ROOT/upstream/LICENSE.txt" "$APPDIR/usr/lib/linbible/LICENSE.txt"

# ---------------------------------------------------------------- pack
step "Packing AppImage"
OUT="$ROOT/LinBible-$VERSION-x86_64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOLS/appimagetool" --no-appstream "$APPDIR" "$OUT"
step "Done: $OUT ($(du -h "$OUT" | cut -f1))"
