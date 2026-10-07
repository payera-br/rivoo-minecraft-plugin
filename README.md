# Rivoo para Minecraft

Plugin para servidores **Paper 1.21** que vende VIP, ranks e kits por **Pix** com a [Rivoo](https://rivoopay.com).
O jogador digita `/comprar`, recebe o QR do Pix num mapa na mão e, assim que paga, o plugin entrega executando
os comandos que você cadastrou no painel. Sem loja externa.

## Instalação

1. Baixe o plugin: **[rivoo-minecraft-paper.jar](https://github.com/payera-br/rivoo-minecraft-plugin/releases/latest/download/rivoo-minecraft-paper.jar)**
   (versão mais recente; outras em [Releases](https://github.com/payera-br/rivoo-minecraft-plugin/releases)).
2. Coloque o `.jar` na pasta `plugins/` do servidor e reinicie.
3. O console mostra um código de 8 letras, por exemplo `K7QM-2XPA`.
4. No painel da Rivoo: **Integrações → Minecraft → seu projeto → Conectar servidor** e digite o código.

Pronto: o console mostra `Loja conectada` e `/comprar` já lista os pacotes do painel. Não é preciso editar
`config.yml` nem reiniciar de novo.

Requisitos: Paper (ou fork, como Purpur) 1.21.x com Java 21, e uma conta na Rivoo.

### Comandos

| Comando | Quem | O que faz |
|---|---|---|
| `/comprar [pacote]` | jogador | Lista os pacotes ou gera o Pix de um deles |
| `/rivoo status` | admin | Servidor conectado, modo (teste/produção) e compras pendentes |
| `/rivoo simular [id]` | admin | Simula o pagamento de uma compra no modo teste |
| `rivoo connect` | console | Gera um novo código de pareamento |
| `rivoo secret <chave>` | console | Conecta com uma chave gerada no painel, em vez do código |

### Como funciona

1. `/comprar vip` cria a cobrança na Rivoo. O preço vem sempre do painel, nunca do servidor.
2. O jogador recebe o "Pergaminho de Pix": um mapa com o QR que só ele vê e que não sai do inventário, mais
   `[Copiar Pix]` no chat.
3. Pagou: o plugin executa os comandos do pacote. Se o jogador estiver offline, entrega quando ele voltar.
4. Reiniciar o servidor não perde nada: compras pendentes e o QR do mapa continuam valendo.

## Desenvolvimento

```
core/   Java 21 puro (sem Bukkit): API da Rivoo, compras, entrega, pareamento
paper/  adaptador Paper: comandos, mapa com QR, HUD, eventos
```

Todo o fluxo de dinheiro vive no `core`; o `paper` só mostra e executa comandos.

- `./gradlew build`: testes, com cobertura mínima de 80% no `core`
- `./gradlew :paper:build`: jar em `paper/build/libs/`
- Release: uma tag `v<versão>` (igual à `version` do `build.gradle.kts`) publica o jar nas Releases

### Ambiente local

Pré-requisito: Docker com Compose.

```bash
echo 'RIVOO_API_KEY=cpk_sandbox_...' > .env   # chave sandbox da Rivoo (nunca commitar)
./dev/up.sh                                  # compila, instala o plugin e sobe tudo
```

Sobe um Paper 1.21.4 em `localhost:25565` (offline mode, pacífico) e o
[minecraft-web-client](https://github.com/zardoy/minecraft-web-client) em `http://localhost:8080`, com um patch
(`web-client/HeldMapUi.tsx`) que desenha o mapa na mão. O `up.sh` imprime o link para jogar pelo navegador.

No jogo: `/comprar vip` → `/rivoo simular`. `./dev/down.sh` para; `rm -rf server` recomeça do zero.

## Licença

[GPL-3.0](LICENSE)
