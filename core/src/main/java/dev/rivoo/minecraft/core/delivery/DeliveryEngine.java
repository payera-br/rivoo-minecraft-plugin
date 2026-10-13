package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.AckResult;
import dev.rivoo.minecraft.core.api.AgentOrder;
import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.CommandPage;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.OnlinePlayer;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.platform.CommandRunner;
import dev.rivoo.minecraft.core.platform.Players;
import dev.rivoo.minecraft.core.platform.PurchaseView;
import dev.rivoo.minecraft.core.platform.Scheduler;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Todo o fluxo de dinheiro do plugin no protocolo do agente da Rivoo: catálogo, pedidos com Pix,
 * acompanhamento do pagamento (só para a tela do jogador) e a fila de comandos, que é quem entrega.
 * Não conhece o jogo: fala com ele pelas interfaces de {@code platform}.
 */
public final class DeliveryEngine {
    static final String STARTING = "A loja está iniciando. Tente em alguns segundos.";
    static final String IN_FLIGHT = "Já estamos gerando seu Pix.";
    static final String CREATE_FAILED = "Não foi possível gerar o Pix. Tente novamente.";

    final RivooApi api;
    final PurchaseStore store;
    final Catalog catalog;
    final Scheduler scheduler;
    final Players players;
    final CommandRunner commands;
    final PurchaseView view;
    final DeliveryConfig config;
    final Logger log;
    final Clock clock;

    static final Duration LOCAL_EXPIRY_GRACE = OrderPoller.EXPIRY_GRACE;
    static final Duration CATALOG_RELOAD = Duration.ofSeconds(60);

    public static final String REVOKED_MESSAGE =
            "Chave da Rivoo revogada ou inválida. Um código de pareamento novo aparece no console; ou rode: rivoo secret <chave>";
    private final AtomicBoolean revoked = new AtomicBoolean(false);

    private volatile boolean stopped;
    private volatile boolean catalogLoaded;
    private final Set<UUID> creating = ConcurrentHashMap.newKeySet();
    private final List<Scheduler.Task> tasks = new ArrayList<>();
    private CommandPoller poller;
    /** Runs executados cujo ack ainda não foi confirmado: se voltarem da fila, só recebem o ack guardado. */
    final AckStore acks;
    /** Quem está online (lido na thread do jogo a cada segundo), para a consulta da fila. */
    private volatile List<OnlinePlayer> online = List.of();

    public DeliveryEngine(RivooApi api, PurchaseStore store, Catalog catalog, Scheduler scheduler, Players players,
                          CommandRunner commands, PurchaseView view, DeliveryConfig config, AckStore acks,
                          Logger log, Clock clock) {
        this.acks = acks;
        this.api = api;
        this.store = store;
        this.catalog = catalog;
        this.scheduler = scheduler;
        this.players = players;
        this.commands = commands;
        this.view = view;
        this.config = config;
        this.log = log;
        this.clock = clock;
    }

    /** Catálogo (recarga a cada 60 s), heartbeat, status dos Pix abertos e fila de comandos. */
    public synchronized void start() {
        scheduler.async(this::reloadCatalog);
        track(scheduler.repeatAsync(this::reloadCatalog, CATALOG_RELOAD));
        scheduler.async(this::heartbeat);
        track(scheduler.repeatAsync(this::heartbeat, config.heartbeatInterval()));
        track(scheduler.repeatSync(this::refreshOnline, Duration.ofSeconds(1)));
        scheduler.async(this::flushStoredAcks);
        poller = new CommandPoller(api, config.commandWaitSeconds(), scheduler, () -> online, this::runCommands,
                this::forgetAcked, this::warnSlots, log, clock, this::markRevoked);
        track(scheduler.repeatAsync(poller, config.commandInterval()));
        OrderPoller orders = new OrderPoller(api, store,
                p -> scheduler.sync(() -> confirmPaid(p)),
                p -> scheduler.sync(() -> view.expired(p, store.latestPendingOf(p.playerId).isPresent())),
                this::markRevoked, log, clock);
        track(scheduler.repeatAsync(orders, config.orderInterval()));
        track(scheduler.repeatSync(this::expireLocally, Duration.ofSeconds(1)));
        log.info("Entrega via fila de comandos da Rivoo");
    }

    public synchronized void stop() {
        stopped = true;
        if (poller != null) poller.stop();
        tasks.forEach(Scheduler.Task::cancel);
        tasks.clear();
    }

    /** Só testes. */
    public boolean stoppedForTest() {
        return stopped;
    }

    public boolean revoked() {
        return revoked.get();
    }

    private volatile Runnable onRevoked = () -> {};

    /** Chamado uma vez quando a chave é marcada revogada (o plugin começa o pareamento). */
    public void onRevoked(Runnable listener) {
        this.onRevoked = listener;
    }

    /** Chave revogada ou inválida: avisa uma vez (REVOKED_MESSAGE) e para catálogo e fila. */
    public void markRevoked() {
        if (revoked.compareAndSet(false, true)) {
            log.warning(REVOKED_MESSAGE);
            // Motor já trocado: o 401 veio de uma requisição antiga, a chave atual é outra.
            if (!stopped) onRevoked.run();
        }
    }

    /** Thread assíncrona. Falha mantém o catálogo anterior. */
    void reloadCatalog() {
        if (stopped || revoked.get()) return;
        try {
            List<Product> products = api.catalog();
            catalog.replace(products);
            if (!catalogLoaded) log.info("Catálogo carregado da Rivoo: " + products.size() + " produto(s)");
            catalogLoaded = true;
        } catch (ApiException e) {
            if (e.unauthorized()) markRevoked();
            else log.warning("Falha ao carregar o catálogo da Rivoo: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Falha ao carregar o catálogo da Rivoo: " + e.getMessage());
        }
    }

    /** Thread assíncrona. Só informativo (versão e dados no painel): falha não muda nada. */
    void heartbeat() {
        if (stopped || revoked.get()) return;
        try {
            api.heartbeat(config.agentVersion(), config.details());
        } catch (ApiException e) {
            if (e.unauthorized()) markRevoked();
            else log.fine("Heartbeat recusado: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.fine("Heartbeat falhou: " + e.getMessage());
        }
    }

    static final Duration SLOTS_WARN_EVERY = Duration.ofMinutes(5);
    private final Map<UUID, Instant> slotsWarned = new ConcurrentHashMap<>();

    /** Thread do jogo. Quem tem comando esperando espaço no inventário (a Rivoo diz quem). */
    void warnSlots(List<CommandPage.SlotWait> waits) {
        for (CommandPage.SlotWait w : waits) {
            UUID player = playerOf(new CommandRun("", "", w.uuid(), w.name(), true, w.requiredSlots()));
            if (player != null && players.isOnline(player)) warnSlots(player, w.requiredSlots());
        }
    }

    /** Avisa o jogador que falta espaço, no máximo uma vez a cada 5 minutos. */
    private void warnSlots(UUID player, int slots) {
        Instant now = clock.instant();
        Instant last = slotsWarned.get(player);
        if (last != null && last.plus(SLOTS_WARN_EVERY).isAfter(now)) return;
        slotsWarned.put(player, now);
        players.message(player, "§eLibere " + slots + " espaços no inventário para receber sua compra.");
    }

    /** Thread do jogo. */
    void refreshOnline() {
        try {
            online = List.copyOf(players.online());
        } catch (RuntimeException e) {
            log.fine("Falha ao ler os jogadores online: " + e);
        }
    }

    /** Thread assíncrona. Acks que ficaram para trás (servidor reiniciou antes de confirmar). */
    void flushStoredAcks() {
        List<AckResult> stored = acks.all();
        if (stored.isEmpty() || stopped) return;
        try {
            forgetAcked(api.ackCommands(stored));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.fine("Acks guardados ficam para a próxima consulta: " + e.getMessage());
        }
    }

    /**
     * Thread do jogo. Executa os comandos prontos da Rivoo e devolve o resultado de cada um para o ack.
     * A Rivoo só manda o que dá para executar (jogador online e com espaço), mas o jogador pode ter saído
     * desde a consulta: aí não executa e não dá ack (fica na fila). Comando sem jogador nenhum (ex.:
     * enviado pelo painel) executa direto.
     */
    List<AckResult> runCommands(List<CommandRun> runs) {
        List<AckResult> ackable = new ArrayList<>();
        for (CommandRun run : runs) {
            Optional<AckResult> done = acks.get(run.id());
            if (done.isPresent()) { // executado antes, mas o ack não chegou
                ackable.add(done.get());
                continue;
            }
            UUID player = playerOf(run);
            boolean online = player != null && players.isOnline(player);
            if (run.hasRecipient() && run.requireOnline() && !online) continue;
            if (run.requiredSlots() > 0 && run.hasRecipient()) {
                if (!online) continue;
                if (players.freeSlots(player) < run.requiredSlots()) {
                    warnSlots(player, run.requiredSlots());
                    continue; // sem ack: volta na próxima consulta
                }
            }
            AckResult result = execute(run);
            acks.put(result);
            ackable.add(result);
        }
        return ackable;
    }

    /**
     * Falha não se resolve repetindo sozinha: vai para a Rivoo como falha (o pedido mostra "Entrega
     * falhou" e o painel tenta de novo) e a fila segue.
     */
    private AckResult execute(CommandRun run) {
        try {
            if (commands.runAsConsole(run.command())) {
                log.info("Comando executado: " + run.id() + " " + run.command());
                return AckResult.ok(run.id());
            }
            log.warning("Comando recusado pelo servidor (" + run.id() + "): " + run.command());
            return AckResult.failed(run.id(), "O servidor não reconheceu o comando: " + run.command());
        } catch (RuntimeException e) {
            log.warning("Comando falhou (" + run.id() + ", " + run.command() + "): " + e);
            return AckResult.failed(run.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void forgetAcked(List<String> runIds) {
        acks.forget(runIds);
    }

    /**
     * UUID do comando; sem ele (pedido da loja web com só o nick) ou com um UUID que nunca entra aqui (UUID
     * da Mojang num servidor offline-mode), o jogador online com esse nick.
     */
    private UUID playerOf(CommandRun run) {
        UUID uuid = run.recipientUuid();
        if (uuid != null && players.isOnline(uuid)) return uuid;
        if (run.recipientName() != null) {
            Optional<UUID> byName = players.onlineByName(run.recipientName());
            if (byName.isPresent()) return byName.get();
        }
        return uuid;
    }

    /** Thread do jogo. Pedido pago: tira o mapa e comemora; os comandos chegam pela fila. */
    private void confirmPaid(Purchase p) {
        try {
            if (players.isOnline(p.playerId)) view.paid(p, productFor(p));
            else view.paidWhileOffline(p);
        } catch (RuntimeException e) {
            log.warning("Falha ao avisar o jogador do pagamento de " + p.chargeId + ": " + e);
        }
    }

    /** O Pix vence antes do pedido: passado o vencimento (com tolerância), some da tela mesmo sem a API. */
    void expireLocally() {
        var now = clock.instant();
        for (Purchase p : store.inState(State.PENDING)) {
            if (now.isAfter(p.expiresAt.plus(LOCAL_EXPIRY_GRACE)) && store.transition(p, State.PENDING, State.EXPIRED)) {
                log.info("Expirado: " + p.chargeId);
                view.expired(p, store.latestPendingOf(p.playerId).isPresent());
            }
        }
    }

    /** Registra para cancelar no stop(). Criada depois do stop (thread assíncrona), já nasce cancelada. */
    synchronized Scheduler.Task track(Scheduler.Task task) {
        if (stopped) task.cancel();
        else tasks.add(task);
        return task;
    }

    /** Thread do jogo. O futuro completa na thread do jogo. */
    public CompletableFuture<BuyResult> buy(UUID playerId, String playerName, String sku) {
        if (!catalogLoaded) return CompletableFuture.completedFuture(new BuyResult.Failed(STARTING));
        Optional<Product> product = catalog.find(sku);
        if (product.isEmpty()) return CompletableFuture.completedFuture(new BuyResult.UnknownProduct(sku));
        Optional<Purchase> open = store.latestPendingOf(playerId);
        if (open.isPresent()) {
            return CompletableFuture.completedFuture(new BuyResult.AlreadyPending(open.get(), productFor(open.get())));
        }
        if (!creating.add(playerId)) return CompletableFuture.completedFuture(new BuyResult.Failed(IN_FLIGHT));

        Product p = product.get();
        String idempotencyKey = UUID.randomUUID().toString();
        CompletableFuture<BuyResult> result = new CompletableFuture<>();
        try {
            scheduler.async(() -> {
            BuyResult outcome;
            try {
                // A Rivoo cobra o preço do produto (com regras e descontos); o plugin usa o total que voltou.
                AgentOrder order = api.createOrder(sku, playerId, playerName, idempotencyKey);
                Product sold = new Product(p.sku(), p.name(), order.amount(), p.commands(), p.icon());
                if (!order.payable()) {
                    if ("PAID".equals(order.status())) {
                        log.info("Pedido gratuito: " + order.id() + " (" + playerName + ", " + sku + ")");
                        outcome = new BuyResult.Free(sold);
                    } else {
                        log.warning("Pedido " + order.id() + " sem Pix (status " + order.status() + ")");
                        outcome = new BuyResult.Failed(CREATE_FAILED);
                    }
                } else {
                    Purchase purchase = store.newPurchase(order, playerId, playerName, sku);
                    List<Purchase> superseded = store.inState(State.PENDING).stream()
                            .filter(old -> old.playerId.equals(playerId)).toList();
                    if (store.add(purchase)) {
                        log.info("Pedido criado: " + order.id() + " (" + playerName + ", " + sku + ")");
                        outcome = new BuyResult.Created(purchase, sold, superseded);
                    } else {
                        // Sem o registro local não há QR: o pedido fica órfão e vence sem pagamento.
                        log.warning("Pedido " + order.id() + " criado mas não gravado em disco; compra recusada");
                        outcome = new BuyResult.Failed(CREATE_FAILED);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = new BuyResult.Failed(CREATE_FAILED);
            } catch (ApiException e) {
                log.warning("Falha ao criar pedido para " + playerName + ": " + e.getMessage());
                if (e.unauthorized()) markRevoked();
                // 403/409/422 trazem um título em português feito para o comprador (ex.: "Loja indisponível no
                // momento", "Compra não permitida nesta loja").
                boolean forBuyer = (e.httpStatus == 403 || e.httpStatus == 409 || e.httpStatus == 422) && e.title != null;
                outcome = new BuyResult.Failed(forBuyer ? e.title : CREATE_FAILED);
            } catch (Exception e) {
                log.warning("Falha ao criar pedido para " + playerName + ": " + e.getMessage());
                outcome = new BuyResult.Failed(CREATE_FAILED);
            } finally {
                creating.remove(playerId);
            }
            BuyResult done = outcome;
            scheduler.sync(() -> result.complete(done));
            });
        } catch (RuntimeException e) {
            creating.remove(playerId);
            log.warning("Falha ao agendar criação de pedido: " + e);
            return CompletableFuture.completedFuture(new BuyResult.Failed(CREATE_FAILED));
        }
        return result;
    }

    public Snapshot snapshot() {
        return new Snapshot(catalogLoaded, catalog.all().size(), store.inState(State.PENDING));
    }

    public Product productFor(Purchase purchase) {
        return catalog.find(purchase.sku).orElseGet(() -> Product.fallback(purchase.sku, purchase.amount));
    }
}
