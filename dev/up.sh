#!/usr/bin/env bash
# Sobe o ambiente local: compila o plugin, instala no servidor, gera a config e roda o compose.
# Uso: RIVOO_API_KEY=cpk_sandbox_... ./dev/up.sh   (a chave pode vir de um arquivo .env)
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
if [ ! -f "$CONFIG" ] && [ ! -f server/plugins/RivooPoc/config.yml ]; then
  : "${RIVOO_API_KEY:?Defina RIVOO_API_KEY (cpk_sandbox_...) no ambiente ou em .env}"
  # Ambiente local usa a chave de empresa (config legada); servidor real conecta com rivoo secret.
  { cat paper/src/main/resources/config.yml; printf '  api-key: "%s"\n' "${RIVOO_API_KEY}"; } > "$CONFIG"
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
echo "  No jogo: /comprar vip  ->  /rivoo simular"
echo "  Logs: docker compose logs -f minecraft    Parar: ./dev/down.sh"
