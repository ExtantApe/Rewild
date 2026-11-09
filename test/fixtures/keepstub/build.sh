#!/bin/bash
# Builds the test stand-in plugin against the Paper API and Rewild's classes.
set -e
cd "$(dirname "$0")"
J=/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home/bin
API=$(find ~/.gradle/caches/modules-2 -name "paper-api-26.1.2.build.74-stable.jar" | head -1)
CP="$API:../../../build/classes/java/main:$(find ~/.gradle/caches/modules-2 -name 'adventure-api-*.jar' -o -name 'adventure-key-*.jar' | grep -v sources | tr '\n' ':')"
rm -rf out && mkdir out
$J/javac --release 25 -cp "$CP" -d out src/stub/KeepStub.java
cp paper-plugin.yml out/
(cd out && $J/jar cf ../../../../build/keepstub.jar .)
echo built
