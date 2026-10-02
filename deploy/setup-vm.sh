#!/usr/bin/env bash
# One-shot setup of a fresh Ubuntu 24.04 VM (built for Oracle Cloud Always
# Free, ARM). Safe to re-run: it pulls the latest code and redeploys.
#
#   bash setup-vm.sh https://github.com/<you>/<repo>.git
#
# Result: https://<ip-with-dashes>.sslip.io serving the API, /readyz,
# /actuator/prometheus and password-protected live logs at /logs.
set -euo pipefail

REPO="${1:?usage: setup-vm.sh <git-repo-url>}"
DIR="$HOME/seat-reserve"
COMPOSE=(sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml)

echo "==> Docker"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi

echo "==> Firewall: open 80/443 (Oracle's Ubuntu images reject all but SSH)"
for port in 80 443; do
  sudo iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null \
    || sudo iptables -I INPUT 5 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
if command -v netfilter-persistent >/dev/null 2>&1; then
  sudo netfilter-persistent save >/dev/null
fi

echo "==> Kernel network limits for an on-sale burst"
sudo tee /etc/sysctl.d/99-seat-reserve.conf >/dev/null <<'EOF'
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
fs.file-max = 2097152
EOF
sudo sysctl --system >/dev/null

echo "==> Code"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only
else
  git clone "$REPO" "$DIR"
fi
cd "$DIR"

if [ ! -f .env ]; then
  echo "==> Secrets (generated once, kept across redeploys)"
  IP="$(curl -fsS https://api.ipify.org)"
  LOGS_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=')"
  HASH="$(sudo docker run --rm caddy:2-alpine caddy hash-password --plaintext "$LOGS_PASSWORD")"
  # bcrypt hashes contain '$', which compose would treat as variables in .env.
  HASH="${HASH//\$/\$\$}"
  umask 077
  cat > .env <<EOF
DOMAIN=${IP//./-}.sslip.io
POSTGRES_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)
ADMIN_KEY=$(openssl rand -hex 16)
LOGS_USER=grader
LOGS_PASSWORD_HASH=${HASH}
DB_POOL_SIZE=24
EOF
  echo "$LOGS_PASSWORD" > .logs-password
fi

echo "==> Build and start"
"${COMPOSE[@]}" up -d --build --remove-orphans

DOMAIN="$(grep '^DOMAIN=' .env | cut -d= -f2)"
echo "==> Waiting for https://$DOMAIN/readyz"
for _ in $(seq 1 120); do
  if curl -fsS "https://$DOMAIN/readyz" >/dev/null 2>&1; then
    echo
    echo "Live:     https://$DOMAIN"
    echo "Ready:    https://$DOMAIN/readyz"
    echo "Metrics:  https://$DOMAIN/actuator/prometheus"
    echo "Logs:     https://$DOMAIN/logs   (user grader, password in $DIR/.logs-password)"
    echo "Admin key for POST /shows and burst.sh: $(grep '^ADMIN_KEY=' .env | cut -d= -f2)"
    exit 0
  fi
  sleep 2
done
echo "Not ready after 4 minutes; recent logs:" >&2
"${COMPOSE[@]}" logs --tail 50 app caddy >&2
exit 1
