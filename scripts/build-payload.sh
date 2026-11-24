#!/usr/bin/env bash
# Rebuilds src/main/resources/com/arialbold/xmrb/payload.dex from payload/src.
# Only needed if you change the code that runs inside the Music app.
# Needs a JDK and Android build-tools (for d8), e.g. ANDROID_HOME=~/Library/Android/sdk
set -euo pipefail
cd "$(dirname "$0")/.."
D8="${D8:-$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ | sort -V | tail -1)d8}"
rm -rf build/payload && mkdir -p build/payload/stubs build/payload/classes build/payload/dex
javac -nowarn -encoding UTF-8 --release 8 -d build/payload/stubs $(find payload/stubs -name '*.java')
javac -nowarn -encoding UTF-8 --release 8 -cp build/payload/stubs -d build/payload/classes $(find payload/src -name '*.java')
"$D8" --release --min-api 26 --output build/payload/dex $(find build/payload/classes -name '*.class')
cp build/payload/dex/classes.dex src/main/resources/com/arialbold/xmrb/payload.dex
echo "payload.dex updated"
