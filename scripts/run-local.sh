#!/usr/bin/env bash
# Builds and runs the Re-Builder from source without packaging it (needs JDK 17+ and curl).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
get() { [ -f "lib/$1" ] || curl -fL -o "lib/$1" "$2"; }
get apktool_2.10.0.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
get uber-apk-signer-1.3.0.jar https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
get flatlaf-3.5.4.jar https://repo1.maven.org/maven2/com/formdev/flatlaf/3.5.4/flatlaf-3.5.4.jar
rm -rf build/classes && mkdir -p build/classes
javac -nowarn -encoding UTF-8 --release 17 -cp "lib/*" -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/classes/
exec java -cp "build/classes:lib/*" com.arialbold.xmrb.Main "$@"
