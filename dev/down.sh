#!/usr/bin/env bash
# Para o ambiente local. O mundo e as compras pendentes ficam em ./server.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
