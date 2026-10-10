#!/bin/sh
# Builds and signs the APK without an IDE. The five voice samples go to assets/voice, native libraries to lib/;
# the speech model itself is a separate pack the app fetches (see tools/make_voice_module.py).
set -e
AJ=${ANDROID_JAR:?set ANDROID_JAR to a platform jar, API 34 or newer}
KS=${KEYSTORE:?set KEYSTORE}; KA=${KEY_ALIAS:?set KEY_ALIAS}
rm -rf out && mkdir -p out/gen out/classes out/apk
# Content stamps: the app unpacks its voice and stress list again only when these change, not with every build.
if [ -d assets/voice ]; then
  ( cd assets/voice && ls | grep -v '^stamp.txt$' | sort | xargs cat | md5sum | cut -c1-32 ) > assets/voice/stamp.txt
fi
if [ -f assets/lexicons/stress-big.tsv ]; then
  md5sum < assets/lexicons/stress-big.tsv | cut -c1-32 > assets/lexicons/stress-big.stamp
fi
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -J out/gen -F out/base.apk
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d out/classes \
  $(find src out/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output=out/apk/classes.dex out/classes
cp out/base.apk out/unsigned.apk
( cd out/apk && zip -q ../unsigned.apk classes.dex )
( zip -q -r out/unsigned.apk lib )
zipalign -f -p 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks "$KS" --ks-key-alias "$KA" --out out/hora.apk out/aligned.apk
apksigner verify out/hora.apk
