#!/usr/bin/env bash
# Source this before building:  . ./env.sh
#
# Kept out of ~/.bashrc deliberately — this toolchain belongs to this project, and a
# machine-wide JAVA_HOME is the kind of thing that silently breaks something else.

export JAVA_HOME="$(ls -d "$HOME"/.local/jdk/jdk-* 2>/dev/null | head -1)"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"   # legacy name; some tools still read it
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
