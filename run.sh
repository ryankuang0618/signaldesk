#!/usr/bin/env bash
# Run SignalDesk pinned to JDK 21.
# Homebrew's default `openjdk` is a newer JDK that silently breaks Lombok's
# annotation processing (getters/setters never get generated). JDK 21 is what
# this project targets and what Spring Boot 3.4 + Lombok support, so pin it here.
set -euo pipefail

BREW_PREFIX="$(brew --prefix)"
export JAVA_HOME="${BREW_PREFIX}/opt/openjdk@21"
export PATH="${BREW_PREFIX}/bin:${JAVA_HOME}/bin:${PATH}"

echo "Using $(java -version 2>&1 | head -1)"
exec mvn spring-boot:run "$@"
