#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-/workspace/.toolchains/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/.toolchains/android-user}"
export JAVA_HOME="/workspace/.toolchains/jdk-21"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.toolchains/gradle-cache}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmake/3.22.1/bin:/workspace/.toolchains/gradle-8.13/bin:$PATH"
if [[ -f /etc/ssl/certs/java/cacerts ]]; then export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"; fi
if [[ -f /etc/codex/network-policy.json ]]; then
    export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Dhttp.nonProxyHosts=localhost\|127.*"
fi
exec "$@"
