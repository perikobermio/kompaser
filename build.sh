#!/usr/bin/env bash
# Compila el APK firmado y lo deja en ./kompaser-<versión>.apk  (--install lo instala por adb)
set -euo pipefail
cd "$(dirname "$0")"
PROXY=()
if [[ -n "${https_proxy:-}" ]]; then
	# Java no lee https_proxy del entorno: se lo pasamos a Gradle.
	hp=${https_proxy#*://}; hp=${hp%/}; host=${hp%:*}; port=${hp##*:}
	PROXY=(-Dhttp.proxyHost="$host" -Dhttp.proxyPort="$port" -Dhttps.proxyHost="$host" -Dhttps.proxyPort="$port"
		"-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.eitb.eus|*.eitb.lan")
fi
if [[ ! -f keystore.properties ]]; then
	pass=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
	keytool -genkeypair -keystore release.jks -alias kompaser -keyalg RSA -keysize 2048 -validity 10000 \
		-storepass "$pass" -keypass "$pass" -dname "CN=Kompaser" >/dev/null
	printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=kompaser\nkeyPassword=%s\n' "$pass" "$pass" > keystore.properties
fi
./gradlew "${PROXY[@]}" assembleRelease
ver=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
cp app/build/outputs/apk/release/app-release.apk "kompaser-$ver.apk"
echo "APK: $PWD/kompaser-$ver.apk"
if [[ "${1:-}" == "--install" ]]; then adb install -r "kompaser-$ver.apk"; fi
