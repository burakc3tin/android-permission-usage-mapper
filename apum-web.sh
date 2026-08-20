#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"
if [ -z "$1" ]; then
  ./gradlew --quiet --console=plain run --args="--serve"
else
  ./gradlew --quiet --console=plain run --args="--serve --port $1"
fi
