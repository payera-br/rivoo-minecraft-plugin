#!/usr/bin/env bash
# Sobe o ambiente local: compila o plugin, instala no servidor, gera a config e roda o compose.
# Uso: ./dev/up.sh   (RIVOO_API_URL opcional, também via .env; padrão: a rivoo-api local em :4100)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

echo "==> Compilando o plugin"
docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/home/gradle/project/.gradle \
  -v "$PWD":/home/gradle/project -w /home/gradle/project gradle:8.12-jdk21 ./gradlew :paper:build --no-daemon -q

echo "==> Instalando no servidor"
mkdir -p server/plugins/Rivoo
rm -f server/plugins/rivoo-minecraft-poc-*.jar server/plugins/rivoo-minecraft-paper-*.jar
cp paper/build/libs/rivoo-minecraft-paper-*.jar server/plugins/
CONFIG=server/plugins/Rivoo/config.yml
if [ ! -f "$CONFIG" ]; then
  # O servidor conecta pelo código de pareamento que aparece no console; só a URL da API muda.
  sed "s|https://api.rivoopay.com|${RIVOO_API_URL:-http://host.docker.internal:4100}|" \
    paper/src/main/resources/config.yml > "$CONFIG"
  echo "    config criada em $CONFIG"
fi

echo "==> Subindo servidor e cliente web (o primeiro build do cliente leva alguns minutos)"
WAS_RUNNING="$(docker inspect -f '{{.State.Running}}' rivoo-mc 2>/dev/null || echo false)"
MC_UID="$(id -u)" MC_GID="$(id -g)" docker compose up -d --build
if [ "$WAS_RUNNING" = "true" ]; then
  # O compose não recria um container sem mudanças: reinicia para carregar o plugin recompilado.
  echo "    reiniciando o servidor para carregar o plugin novo"
  docker compose restart minecraft
  sleep 5
fi

echo "==> Aguardando o servidor ficar pronto"
until [ "$(docker inspect -f '{{.State.Health.Status}}' rivoo-mc 2>/dev/null)" = "healthy" ]; do sleep 3; done
docker exec rivoo-mc rcon-cli "gamerule doDaylightCycle false" >/dev/null
docker exec rivoo-mc rcon-cli "time set day" >/dev/null

NICK="${MC_OPS:-Steve}"
echo
echo "Pronto."
echo "  Jogar no navegador: http://localhost:8080/?ip=minecraft:25565&proxy=localhost:8080&version=1.21.4&username=${NICK}&autoConnect=true"
echo "  Cliente Java:       localhost:25565 (offline mode)"
echo "  Pareamento: o código aparece em docker compose logs minecraft (painel: Integrações → Servidores de jogo)"
echo "  No jogo: /comprar vip; pague com npm run dev:pay -- <paymentId> na rivoo-api"
echo "  Logs: docker compose logs -f minecraft    Parar: ./dev/down.sh"
