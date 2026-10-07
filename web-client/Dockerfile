# Cliente web open source (MIT) zardoy/minecraft-web-client, fixado num commit, com um patch local:
# HeldMapUi.tsx desenha o mapa segurado (QR do Pix) na lateral da tela — o cliente original não
# renderiza a arte de mapas na 1.21. Só para testes locais.
FROM node:18-alpine AS build
RUN apk add git
ARG MWC_COMMIT=4f5243c245c96aac44bbe147c61a4ea5612338f9
RUN git init /app && cd /app \
    && git remote add origin https://github.com/zardoy/minecraft-web-client.git \
    && git fetch --depth 1 origin ${MWC_COMMIT} && git checkout FETCH_HEAD
WORKDIR /app
COPY HeldMapUi.tsx src/react/HeldMapUi.tsx
RUN corepack enable
RUN node ./scripts/dockerPrepare.mjs
RUN pnpm i
RUN DISABLE_SERVICE_WORKER=false CONFIG_JSON_SOURCE=REMOTE pnpm run build

FROM node:18-alpine
RUN apk add git
WORKDIR /app
COPY --from=build /app/dist /app/dist
COPY --from=build /app/server.js /app/server.js
RUN npm i -g pnpm@10.8.0 && npm init -yp \
    && pnpm i express github:zardoy/prismarinejs-net-browserify compression cors
EXPOSE 8080
ENTRYPOINT ["node", "server.js", "--prod"]
