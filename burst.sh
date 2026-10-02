#!/usr/bin/env bash
# One-command on-sale stampede against a running seat-reserve service.
#
#   ./burst.sh <BASE_URL> [--scale N] [--concurrency N] [--timeout SECONDS] [--admin-key KEY]
#
#   ./burst.sh http://localhost:8080
#   ADMIN_KEY=... ./burst.sh https://seat-reserve-lrvt.onrender.com --scale 4 --timeout 100   # ~23k requests
#
# Uses a local JDK 21+ if there is one, otherwise runs the same program in a
# Docker JDK image. Exit code: 0 all checks passed, 1 a check failed, 2 setup failed.
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,10p' "$0"
  exit 2
fi

DIR="$(cd "$(dirname "$0")" && pwd)"

java_major() {
  java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1
}

if command -v java >/dev/null 2>&1 && [ "$(java_major)" -ge 21 ] 2>/dev/null; then
  exec java -Xss512k "$DIR/burst/Burst.java" "$@"
elif command -v docker >/dev/null 2>&1; then
  # --network host so a localhost URL reaches a service on this machine.
  exec docker run --rm --network host -e ADMIN_KEY -v "$DIR/burst:/burst:ro" \
    eclipse-temurin:21-jdk java /burst/Burst.java "$@"
else
  echo "burst.sh needs either Java 21+ or Docker" >&2
  exit 2
fi
