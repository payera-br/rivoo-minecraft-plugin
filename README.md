# Rivoo para Minecraft

Plugin para servidores **Paper 1.21** que vende VIP, ranks e kits por **Pix** com a [Rivoo](https://rivoopay.com).
O jogador digita `/comprar`, recebe o QR do Pix num mapa na mão e, assim que paga, o plugin entrega executando
os comandos que você cadastrou no painel. Sem loja externa.

## Instalação

1. Baixe o plugin: **[rivoo-minecraft-paper.jar](https://github.com/payera-br/rivoo-minecraft-plugin/releases/latest/download/rivoo-minecraft-paper.jar)**
   (versão mais recente; outras em [Releases](https://github.com/payera-br/rivoo-minecraft-plugin/releases)).
2. Coloque o `.jar` na pasta `plugins/` do servidor e reinicie.
3. O console mostra um código de 8 letras, por exemplo `K7QM-2XPA`.
4. No painel da Rivoo: **Integrações → Servidores de jogo → Parear servidor** e digite o código.

Pronto: o console mostra `Loja conectada` e `/comprar` já lista os produtos (com sku) do painel. Não é
preciso editar `config.yml` nem reiniciar de novo.

**Vindo da 0.x?** A 1.0.0 fala com a API nova da Rivoo (`/v1/agent`). A chave antiga (`cpk_…`) não vale
mais: ao subir, o plugin avisa e mostra um código novo; pareie de novo (dá para escolher a conexão
existente no painel). `rivoo.api-key`, `rivoo.channel`, `delivery.mode` e `products` no `config.yml` são
ignorados.

Requisitos: Paper (ou fork, como Purpur) 1.21.x com Java 21, e uma conta na Rivoo.

### Comandos

| Comando | Quem | O que faz |
|---|---|---|
| `/comprar [pacote]` | jogador | Abre o menu da loja (baú com os pacotes) ou gera o Pix de um deles |
| `/rivoo status` | admin | Servidor conectado, catálogo e Pix abertos |
| `rivoo connect` (ou `conectar`) | console | Gera um novo código de pareamento |
| `rivoo secret <chave>` | console | Conecta com uma chave `rck_…` gerada no painel ("Conectar com chave"), em vez do código |

### Como funciona

1. `/comprar vip` cria o pedido na Rivoo (`POST /v1/agent/orders`). Preço, regras e descontos vêm sempre
   do painel, nunca do servidor.
2. O jogador recebe o "Pergaminho de Pix": um mapa com o QR que só ele vê e que não sai do inventário, mais
   `[Copiar Pix]` e `[Abrir checkout]` no chat.
3. Pagou: o mapa some e o plugin executa os comandos que a Rivoo coloca na fila do servidor (inclusive os
   de compras feitas na loja web). A cada consulta o plugin diz quem está online e com quantos espaços
   livres, e a Rivoo só devolve o que dá para executar agora: comando esperando jogador offline ou
   inventário cheio não segura os outros.
4. Cada comando volta para a Rivoo como ok ou falhou (comando desconhecido, exceção). Falhou: o pedido
   mostra "Entrega falhou" com o motivo e o painel tenta de novo só nesse servidor.
5. Reiniciar o servidor não perde nada: Pix abertos e o QR do mapa continuam valendo, a fila fica na Rivoo
   e os resultados ainda sem ack ficam em `plugins/Rivoo/acks.json` (um comando não roda duas vezes).
6. Chave revogada ou servidor reconectado no painel: o plugin mostra um código de pareamento novo sozinho.

### Opções do comando (painel)

- **Esperar o jogador online**: o comando fica na fila até o jogador entrar.
- **Espaços livres no inventário**: o comando espera o jogador ter N espaços livres no inventário
  principal e avisa no chat ("Libere N espaços…", no máximo a cada 5 minutos).
- **Ícone** do produto: um item do Minecraft (ex.: `diamond_sword`) vira o ícone no menu do `/comprar`;
  sem um item válido, papel.
- **Atraso** e **Repetir**: a Rivoo só põe o comando na fila quando ele vence e agenda a próxima repetição
  depois que o plugin confirma a anterior; nada muda no plugin.

## Desenvolvimento

```
core/   Java 21 puro (sem Bukkit): protocolo do agente da Rivoo (/v1/agent), Pix abertos, fila de comandos, pareamento
paper/  adaptador Paper: comandos, mapa com QR, HUD, eventos
```

Todo o fluxo de dinheiro vive no `core`; o `paper` só mostra e executa comandos.

- `./gradlew build`: compila e roda os testes
- `./gradlew :paper:build`: jar em `paper/build/libs/`
- Release: uma tag `v<versão>` (igual à `version` do `build.gradle.kts`) publica o jar nas Releases

### Ambiente local

Pré-requisito: Docker com Compose e a API da Rivoo (`rivoo-api`) rodando na máquina, por padrão em
`localhost:4100` (outra URL: `RIVOO_API_URL=http://host.docker.internal:<porta> ./dev/up.sh`).

```bash
./dev/up.sh     # compila, instala o plugin e sobe tudo
```

Sobe um Paper 1.21.4 em `localhost:25565` (offline mode, pacífico) e o
[minecraft-web-client](https://github.com/zardoy/minecraft-web-client) em `http://localhost:8080`, com um patch
(`web-client/HeldMapUi.tsx`) que desenha o mapa na mão. O `up.sh` imprime o link para jogar pelo navegador.

O console (`docker compose logs -f minecraft`) mostra o código de pareamento; use-o no painel local. A API
não tem modo teste: com `PAYMENTS_FAKE_PIX=true` na `rivoo-api`, pague o Pix de `/comprar vip` com
`npm run dev:pay -- <paymentId>` (o id `pay_…` está no código Pix copia e cola). `./dev/down.sh` para;
`rm -rf server` recomeça do zero.

## Licença

[GPL-3.0](LICENSE)
