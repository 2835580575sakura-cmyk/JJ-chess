#!/usr/bin/env bash
set -euo pipefail
task_tools="/workspace/.toolchains"
mkdir -p "$task_tools"
export ANDROID_USER_HOME="$task_tools/android-user"
mkdir -p "$ANDROID_USER_HOME"
download_checked() {
    local url="$1" target="$2" algorithm="$3" expected="$4"
    if [[ ! -f "$target" ]]; then curl --fail --location --silent --show-error --retry 2 "$url" -o "$target.tmp"; mv "$target.tmp" "$target"; fi
    python3 - "$target" "$algorithm" "$expected" <<'PY'
import hashlib,sys
with open(sys.argv[1],'rb') as f: actual=hashlib.file_digest(f,sys.argv[2]).hexdigest()
if actual!=sys.argv[3]: raise SystemExit('Artifact checksum mismatch: '+sys.argv[1])
PY
}
if [[ ! -x "$task_tools/jdk-21/bin/javac" ]]; then
    download_checked https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz "$task_tools/jdk.tar.gz" sha256 ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94
    mkdir -p "$task_tools/jdk-21"
    tar -xzf "$task_tools/jdk.tar.gz" --strip-components=1 -C "$task_tools/jdk-21"
fi
export JAVA_HOME="$task_tools/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ -f /etc/ssl/certs/java/cacerts ]]; then export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"; fi
task_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -f "$task_root/.local-signing/debug.keystore" ]]; then
    mkdir -p "$task_root/.local-signing"
    keytool -genkeypair -keystore "$task_root/.local-signing/debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -validity 10000 -keyalg RSA -keysize 2048
    chmod 600 "$task_root/.local-signing/debug.keystore"
fi
if [[ ! -x "$task_tools/gradle-8.13/bin/gradle" ]]; then
    download_checked https://services.gradle.org/distributions/gradle-8.13-bin.zip "$task_tools/gradle.zip" sha256 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
    unzip -q "$task_tools/gradle.zip" -d "$task_tools"
fi
if [[ ! -x "$task_tools/android-sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
    download_checked https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip "$task_tools/commandline-19.zip" sha1 5fdcc763663eefb86a5b8879697aa6088b041e70
    mkdir -p "$task_tools/android-sdk/cmdline-tools"
    unzip -q "$task_tools/commandline-19.zip" -d "$task_tools/android-sdk/cmdline-tools"
    mv "$task_tools/android-sdk/cmdline-tools/cmdline-tools" "$task_tools/android-sdk/cmdline-tools/latest"
fi
task_sdk=("$task_tools/android-sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$task_tools/android-sdk")
if [[ -f /etc/codex/network-policy.json ]]; then task_sdk+=(--proxy=http --proxy_host=proxy --proxy_port=8080); fi
python3 - "${task_sdk[@]}" <<'PY'
import subprocess,sys
subprocess.run(sys.argv[1:]+['--licenses'],input=('y\n'*100).encode(),check=True)
PY
"${task_sdk[@]}" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1' 'platform-tools'
