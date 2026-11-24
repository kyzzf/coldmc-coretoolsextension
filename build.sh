#!/bin/sh
# Builds CoreToolsExtension.jar - ColdMC's own Typewriter extension (per-profile facts through
# CoreTools player variables, and per-profile PremiumAdvancements progress). README.md says why.
#
# No Gradle and no KSP: compiled with the Kotlin compiler in tools/ against the live server's own
# jars, and extension.json (what KSP would generate) is hand-written in res/. Typewriter and every
# extension must share one engine version: res/extension.json says 0.9.0-beta-177.
#
# Install: mv (never cp over a live jar) into core/server/plugins/Typewriter/extensions/, then
# restart - Typewriter only loads extension jars at boot.
set -e
cd "$(dirname "$0")"
S=${COLDMC_SERVER:-../../../../core/server}; P=$S/plugins
export JAVA_HOME=/usr/local/opt/openjdk@21
K=tools/kotlinc/bin/kotlinc
CP="$P/Typewriter-0.9.0-beta-177.jar:$P/CoreTools.jar:$P/MMOProfiles-1.2-27.jar:$P/UltimateAdvancementAPI.jar"
CP="$CP:$S/libraries/cn/dreeam/leaf/leaf-api/1.21.11-R0.1-SNAPSHOT/leaf-api-1.21.11-R0.1-SNAPSHOT.jar"
CP="$CP:$S/libraries/org/jetbrains/kotlin/kotlin-stdlib/2.3.0/kotlin-stdlib-2.3.0.jar"
CP="$CP:$S/libraries/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar"
for j in $(find $S/libraries/net/kyori -name '*4.26*.jar' -o -name 'examination*.jar' -o -name 'adventure-key*.jar') \
         $(find $S/libraries/net/md-5 -name "bungeecord-chat*.jar") $(find $S/libraries/com/google/guava/guava -name "guava-*.jar") \
         $(find $S/libraries/org/jetbrains/annotations -name '*.jar' | head -1); do CP="$CP:$j"; done
rm -rf build && mkdir build
$K -no-stdlib -no-reflect -jvm-target 21 -jvm-default=enable -nowarn -cp "$CP" -d build $(find src -name "*.kt")
cp -R res/. build/
rm -f CoreToolsExtension.jar && (cd build && $JAVA_HOME/bin/jar cf ../CoreToolsExtension.jar .)
echo built CoreToolsExtension.jar
