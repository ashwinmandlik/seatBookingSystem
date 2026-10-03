#!/usr/bin/env bash
# One-command on-sale stampede against a running seat-reserve service.
#
#   ./burst.sh <BASE_URL> [--scale N] [--concurrency N] [--timeout SECONDS] [--admin-key KEY] [--wait-for-expiry]
#
#   ./burst.sh http://localhost:8080                                         # ~23,500 requests
#   ADMIN_KEY="..." ./burst.sh https://seat-reserve-lrvt.onrender.com          # the same burst, live
#
# Uses a local JDK 21+ if there is one, otherwise runs the same program in a
# Docker JDK image. Exit code: 0 all checks passed, 1 a check failed, 2 setup failed.
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,10p' "$0"
  exit 2
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
URL="$1"
shift

java_major() {
  java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1
}

# Runs the client in a Docker JDK image: docker_burst <docker network> <base url> [args...]
docker_burst() {
  local network="$1" url="$2" src="$DIR/burst"
  shift 2
  # Git Bash on Windows rewrites /burst/... arguments into Windows paths; keep them as written
  # and hand Docker a Windows-style path for the mount instead.
  if command -v cygpath >/dev/null 2>&1; then src="$(cygpath -m "$src")"; fi
  # A terminal for the container too, so the live seat counts update in place.
  local tty=""
  if [ -t 1 ]; then tty="-t"; fi
  # shellcheck disable=SC2086  # $tty is empty or one flag
  MSYS_NO_PATHCONV=1 exec docker run --rm $tty --network "$network" -e ADMIN_KEY -v "$src:/burst:ro" \
    eclipse-temurin:21-jdk java -Xss512k /burst/Burst.java "$url" "$@"
}

# Docker Desktop (Windows, macOS) forwards a published port through a userspace proxy that
# refuses connections when thousands arrive at once, so a localhost burst would report drops
# that never reached the service. If the target is a container on this machine, fire from
# inside its Docker network instead. Linux Docker (CI) forwards in the kernel: unchanged.
# BURST_FROM_HOST=1 skips this.
if [ "${BURST_FROM_HOST:-}" != 1 ] && command -v docker >/dev/null 2>&1; then
  local_url='^http://(localhost|127\.0\.0\.1)(:([0-9]+))?/*$'
  if printf '%s' "$URL" | grep -Eq "$local_url" \
      && docker info --format '{{.OperatingSystem}}' 2>/dev/null | grep -q 'Docker Desktop'; then
    host_port="$(printf '%s' "$URL" | sed -nE "s#$local_url#\\3#p")"
    host_port="${host_port:-80}"
    container="$(docker ps --filter "publish=$host_port" --format '{{.Names}}' | head -n 1)"
    if [ -n "$container" ]; then
      # "8080/tcp -> 0.0.0.0:8080": the container-side port published on $host_port
      inner_port="$(docker port "$container" | grep -E ":$host_port\$" | head -n 1 | sed 's#/.*##')"
      network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$container" | cut -d' ' -f1)"
      if [ -n "$inner_port" ] && [ -n "$network" ]; then
        echo "Docker Desktop: firing from inside network '$network' at http://$container:$inner_port" \
             "(its port proxy drops connections under a burst; BURST_FROM_HOST=1 to fire from the host)"
        docker_burst "$network" "http://$container:$inner_port" "$@"
      fi
    fi
  fi
fi

if command -v java >/dev/null 2>&1 && [ "$(java_major)" -ge 21 ] 2>/dev/null; then
  exec java -Xss512k "$DIR/burst/Burst.java" "$URL" "$@"
elif command -v docker >/dev/null 2>&1; then
  # --network host so a localhost URL reaches a service on this machine.
  docker_burst host "$URL" "$@"
else
  echo "burst.sh needs either Java 21+ or Docker" >&2
  exit 2
fi
