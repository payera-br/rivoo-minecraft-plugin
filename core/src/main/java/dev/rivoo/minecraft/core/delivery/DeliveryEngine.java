package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.Charge;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.Fulfillment;
import dev.rivoo.minecraft.core.api.GamePurchase;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.platform.CommandRunner;
import dev.rivoo.minecraft.core.platform.Players;
import dev.rivoo.minecraft.core.platform.PurchaseView;
import dev.rivoo.minecraft.core.platform.Scheduler;
import dev.rivoo.minecraft.core.purchase.ExternalId;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Todo o fluxo de dinheiro do plugin: cria cobranças, acompanha o pagamento (fila de entregas ou
 * polling de status), entrega uma única vez e confirma a entrega. Não conhece o jogo: fala com ele
 * pelas interfaces de {@code platform}.
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

    static final Duration LOCAL_EXPIRY_GRACE = Duration.ofMinutes(2);

    public static final String REVOKED_MESSAGE =
            "Chave da Rivoo revogada ou inválida. Um código de pareamento novo aparece no console; ou rode: rivoo secret <chave>";
    private final AtomicBoolean revoked = new AtomicBoolean(false);

    private volatile Mode mode;
    private volatile boolean stopped;
    private final Set<UUID> creating = ConcurrentHashMap.newKeySet();

    static final long DETECTION_BACKOFF_MAX_SECONDS = 60;
    private final AtomicBoolean detecting = new AtomicBoolean(false);
    private volatile boolean detectionDone;
    private volatile long detectionBackoffSeconds = 5;
    private volatile Instant nextDetectionAt = Instant.EPOCH;
    private volatile Scheduler.Task detectionRetry;
    private final List<Scheduler.Task> tasks = new ArrayList<>();

    public DeliveryEngine(RivooApi api, PurchaseStore store, Catalog catalog, Scheduler scheduler, Players players,
                          CommandRunner commands, PurchaseView view, DeliveryConfig config, Logger log, Clock clock) {
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

    public void start() {
        String configured = config.mode() == null ? "auto" : config.mode();
        switch (configured) {
            case "fulfillments" -> startMode(Mode.FULFILLMENTS);
            case "status" -> startMode(Mode.STATUS);
            case "game" -> startGameMode();
            default -> {
                if (!configured.equals("auto")) {
                    log.warning("delivery.mode '" + configured + "' desconhecido (use auto, fulfillments, status ou game); usando auto");
                }
                startAuto();
            }
        }
    }

    private void startAuto() {
        scheduler.async(this::attemptDetection);
    }

    /**
     * Thread assíncrona. Uma tentativa de detecção. Só 404 decide STATUS; qualquer outro erro deixa o
     * modo indefinido (buy responde STARTING) e agenda nova tentativa com backoff.
     */
    private void attemptDetection() {
        if (stopped || detectionDone || !detecting.compareAndSet(false, true)) return;
        try {
            Mode chosen = detectMode();
            detectionDone = true;
            Scheduler.Task retry = detectionRetry;
            if (retry != null) retry.cancel();
            scheduler.sync(() -> startMode(chosen));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            long wait = detectionBackoffSeconds;
            detectionBackoffSeconds = Math.min(wait * 2, DETECTION_BACKOFF_MAX_SECONDS);
            nextDetectionAt = clock.instant().plusSeconds(wait);
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warning("Fila de entregas indisponível (" + reason + "); modo de entrega indefinido,"
                    + " nova tentativa em " + wait + "s");
            if (detectionRetry == null) {
                detectionRetry = track(scheduler.repeatAsync(this::retryDetection, Duration.ofSeconds(1)));
            }
        } finally {
            detecting.set(false);
        }
    }

    private void retryDetection() {
        if (clock.instant().isBefore(nextDetectionAt)) return;
        attemptDetection();
    }

    public synchronized void stop() {
        stopped = true;
        tasks.forEach(Scheduler.Task::cancel);
        tasks.clear();
    }

    public Mode mode() {
        return mode;
    }

    /** Descobre se a API tem a fila de entregas: 200 = fila, 404 = status (legado); outro erro propaga. */
    private Mode detectMode() throws Exception {
        try {
            api.listFulfillments(config.channel(), 1);
            return Mode.FULFILLMENTS;
        } catch (ApiException e) {
            if (e.httpStatus == 404) return Mode.STATUS;
            throw e;
        }
    }

    private void startMode(Mode chosen) {
        if (stopped) return;
        mode = chosen;
        if (chosen == Mode.FULFILLMENTS) {
            FulfillmentPoller poller = new FulfillmentPoller(api, config.channel(), scheduler, this::deliverFromQueue, log, clock);
            track(scheduler.repeatAsync(poller, config.queueInterval()));
            track(scheduler.repeatSync(this::expireLocally, Duration.ofSeconds(1)));
            log.info("Entrega via fila de entregas (canal '" + config.channel() + "')");
            // Pagas e não entregues (ex.: pagas no modo status antes de um restart): a fila pode nem tê-las.
            store.inState(State.PAID).forEach(this::deliverPaid);
        } else {
            startStatusMode();
            log.info("Entrega via polling de status por cobrança (API sem fila de entregas)");
        }
    }

    static final Duration CATALOG_RELOAD = Duration.ofSeconds(60);
    private volatile boolean catalogLoaded;
    /** Runs executados cujo ack ainda não foi confirmado: se voltarem da fila, só recebem ack (memória). */
    private final Set<String> executedRuns = ConcurrentHashMap.newKeySet();

    /** Modo game (POC): catálogo da API (recarga a cada 60 s) e fila de comandos prontos da API. */
    private void startGameMode() {
        mode = Mode.GAME;
        scheduler.async(this::reloadCatalog);
        track(scheduler.repeatAsync(this::reloadCatalog, CATALOG_RELOAD));
        CommandPoller poller = new CommandPoller(api, config.channel(), scheduler, this::runCommands,
                this::forgetAcked, log, clock, this::markRevoked);
        track(scheduler.repeatAsync(poller, config.queueInterval()));
        track(scheduler.repeatSync(this::expireLocally, Duration.ofSeconds(1)));
        log.info("Entrega via módulo game da Rivoo (" + channelLabel() + ")");
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

    private String channelLabel() {
        return config.channel() == null ? "chave do servidor" : "canal '" + config.channel() + "'";
    }

    /** Thread assíncrona. Falha mantém o catálogo anterior. */
    void reloadCatalog() {
        if (stopped || revoked.get()) return;
        try {
            List<Product> products = api.gameCatalog(config.channel());
            catalog.replace(products);
            if (!catalogLoaded) log.info("Catálogo carregado da Rivoo: " + products.size() + " pacote(s)");
            catalogLoaded = true;
        } catch (ApiException e) {
            if (e.httpStatus == 401 || e.httpStatus == 403) markRevoked();
            else log.warning("Falha ao carregar o catálogo da Rivoo: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Falha ao carregar o catálogo da Rivoo: " + e.getMessage());
        }
    }

    /**
     * Thread do jogo. Executa os comandos prontos da API e devolve os runIds que podem receber ack.
     * requireOnline com jogador offline: não executa e não dá ack (fica na fila). PURCHASED de uma
     * compra local aberta marca a compra como DELIVERED e mostra a confirmação uma única vez.
     */
    List<String> runCommands(List<CommandRun> runs) {
        List<String> ackable = new ArrayList<>();
        for (CommandRun run : runs) {
            UUID player = playerOf(run.playerRef());
            boolean online = player != null && players.isOnline(player);
            if (executedRuns.contains(run.id())) { // executado antes, mas o ack não chegou
                ackable.add(run.id());
                continue;
            }
            if (run.requireOnline() && !online) continue;
            try {
                commands.runAsConsole(run.command());
            } catch (RuntimeException e) {
                // Exceção do comando não se resolve repetindo: registra e confirma para não travar a fila.
                log.warning("Comando falhou (" + run.id() + ", " + run.command() + "): " + e);
            }
            executedRuns.add(run.id());
            ackable.add(run.id());
            log.info("Comando executado: " + run.id() + " [" + run.trigger() + "] " + run.command());
            if (run.purchased()) confirmPurchase(run.chargeId(), online);
        }
        return ackable;
    }

    void forgetAcked(List<String> runIds) {
        runIds.forEach(executedRuns::remove);
    }

    private void confirmPurchase(String chargeId, boolean online) {
        Purchase p = chargeId == null ? null : store.get(chargeId).orElse(null);
        if (p == null || (p.state != State.PENDING && p.state != State.PAID)) return;
        if (!store.markDelivered(p)) return;
        try {
            if (online) view.paid(p, productFor(p));
            else view.paidWhileOffline(p);
        } catch (RuntimeException e) {
            log.warning("Falha ao avisar o jogador da entrega de " + p.chargeId + ": " + e);
        }
    }

    private static UUID playerOf(String playerRef) {
        if (playerRef == null || !playerRef.startsWith("mc:")) return null;
        try {
            return UUID.fromString(playerRef.substring(3));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void startStatusMode() {
        StatusPoller poller = new StatusPoller(api, store,
                p -> scheduler.sync(() -> deliverPaid(p)),
                p -> scheduler.sync(() -> view.expired(p, store.latestPendingOf(p.playerId).isPresent())),
                log, clock);
        track(scheduler.repeatAsync(poller, config.statusInterval()));
        // Pagas antes de um restart mas ainda não entregues (jogador estava offline).
        store.inState(State.PAID).forEach(this::deliverPaid);
    }

    /**
     * Thread do jogo. Entrega o que der e devolve os chargeIds que podem receber ack. Jogador offline
     * fica fora do ack: o item continua na fila e é entregue quando ele voltar.
     */
    List<String> deliverFromQueue(List<Fulfillment> entries) {
        Set<String> ackable = new LinkedHashSet<>();
        for (Fulfillment f : entries) {
            Purchase p = store.get(f.chargeId()).orElse(null);
            if (p == null) {
                Optional<ExternalId> ext = ExternalId.parse(f.externalId());
                if (ext.isEmpty()) {
                    log.warning("Entrega sem dono reconhecível na fila: " + f.chargeId() + " (" + f.externalId() + ")");
                    continue;
                }
                p = store.recover(f.chargeId(), ext.get(), f.amount(), players.name(ext.get().player()).orElse(null));
                log.info("Compra recuperada pela fila: " + f.chargeId());
            }
            if (p.state == State.DELIVERED) {
                ackable.add(f.chargeId()); // entregue antes, mas o ack não chegou
                continue;
            }
            if (!players.isOnline(p.playerId)) continue;
            if (store.markDelivered(p)) { // false = gravação em disco falhou: sem ack, o item continua na fila
                runDelivery(p);
                ackable.add(f.chargeId());
            }
        }
        return List.copyOf(ackable);
    }

    /** Thread do jogo. Só depois de o estado DELIVERED estar gravado. */
    void runDelivery(Purchase p) {
        Product product = productFor(p);
        String name = players.name(p.playerId).orElse(p.playerName);
        for (String cmd : product.commands()) {
            try {
                commands.runAsConsole(cmd.replace("{player}", name == null ? "" : name)
                        .replace("{uuid}", p.playerId.toString()));
            } catch (RuntimeException e) {
                log.warning("Comando falhou na entrega de " + p.chargeId + " (" + cmd + "): " + e);
            }
        }
        try {
            view.paid(p, product);
        } catch (RuntimeException e) {
            log.warning("Falha ao avisar o jogador da entrega de " + p.chargeId + ": " + e);
        }
        log.info("Entregue: " + p.chargeId + " -> " + name);
    }

    /** Modo fila: o vencimento é local; se o pagamento chegar depois, a fila ainda entrega. */
    void expireLocally() {
        var now = clock.instant();
        for (Purchase p : store.inState(State.PENDING)) {
            if (now.isAfter(p.expiresAt.plus(LOCAL_EXPIRY_GRACE)) && store.transition(p, State.PENDING, State.EXPIRED)) {
                log.info("Expirado: " + p.chargeId);
                view.expired(p, store.latestPendingOf(p.playerId).isPresent());
            }
        }
    }

    /**
     * Thread do jogo. Entrega o que foi pago enquanto o jogador estava fora, nos dois modos: uma compra
     * paga no modo status pode não estar na fila. A transição PAID -> DELIVERED impede entrega dupla;
     * se a fila trouxer a mesma compra depois, ela já está DELIVERED e só recebe ack.
     */
    public void onJoin(UUID playerId) {
        store.inState(State.PAID).stream().filter(p -> p.playerId.equals(playerId)).forEach(this::deliverPaid);
    }

    /** Thread do jogo. A transição PAID -> DELIVERED é atômica e gravada ANTES dos comandos. */
    void deliverPaid(Purchase p) {
        if (!players.isOnline(p.playerId)) {
            view.paidWhileOffline(p);
            log.info("Pago, jogador offline. Entrega no próximo login: " + p.chargeId);
            return;
        }
        if (!store.transition(p, State.PAID, State.DELIVERED)) return;
        runDelivery(p);
    }

    /** Registra para cancelar no stop(). Criada depois do stop (thread assíncrona), já nasce cancelada. */
    synchronized Scheduler.Task track(Scheduler.Task task) {
        if (stopped) task.cancel();
        else tasks.add(task);
        return task;
    }

    /** Thread do jogo. O futuro completa na thread do jogo. */
    public CompletableFuture<BuyResult> buy(UUID playerId, String playerName, String sku) {
        if (mode == Mode.GAME && !catalogLoaded) return CompletableFuture.completedFuture(new BuyResult.Failed(STARTING));
        Optional<Product> product = catalog.find(sku);
        if (product.isEmpty()) return CompletableFuture.completedFuture(new BuyResult.UnknownProduct(sku));
        Optional<Purchase> open = store.latestPendingOf(playerId);
        if (open.isPresent()) {
            return CompletableFuture.completedFuture(new BuyResult.AlreadyPending(open.get(), productFor(open.get())));
        }
        Mode current = mode;
        if (current == null) return CompletableFuture.completedFuture(new BuyResult.Failed(STARTING));
        if (!creating.add(playerId)) return CompletableFuture.completedFuture(new BuyResult.Failed(IN_FLIGHT));

        Product p = product.get();
        // Modo game: a Rivoo monta o externalId (mc:<uuid>:<sku>:<nick>) a partir do pacote.
        String externalId = ExternalId.of(playerId, sku).value();
        String idempotencyKey = UUID.randomUUID().toString();
        String channel = current == Mode.STATUS ? null : config.channel();
        CompletableFuture<BuyResult> result = new CompletableFuture<>();
        try {
            scheduler.async(() -> {
            BuyResult outcome;
            try {
                Charge charge;
                Product sold;
                if (current == Mode.GAME) {
                    // A Rivoo cobra o preço do pacote; o plugin não informa valor e usa o que voltou.
                    GamePurchase gp = api.createGamePurchase(channel, sku, playerId, playerName, idempotencyKey);
                    charge = gp.charge();
                    sold = new Product(p.sku(), p.name(), gp.price(), p.commands());
                } else {
                    charge = api.createPixCharge(p.price(), externalId, config.expiresInSeconds(), idempotencyKey, channel);
                    sold = p;
                }
                Purchase purchase = store.newPurchase(charge, playerId, playerName, sku, sold.price());
                List<Purchase> superseded = store.inState(State.PENDING).stream()
                        .filter(old -> old.playerId.equals(playerId)).toList();
                if (store.add(purchase)) {
                    log.info("Cobrança criada: " + charge.id() + " (" + playerName + ", " + sku + ")");
                    outcome = new BuyResult.Created(purchase, sold, superseded);
                } else {
                    // Sem o registro local não há QR nem entrega confiável: a cobrança fica órfã e vence sem pagamento.
                    log.warning("Cobrança " + charge.id() + " criada mas não gravada em disco; compra recusada");
                    outcome = new BuyResult.Failed(CREATE_FAILED);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = new BuyResult.Failed(CREATE_FAILED);
            } catch (Exception e) {
                log.warning("Falha ao criar cobrança para " + playerName + ": " + e.getMessage());
                outcome = new BuyResult.Failed(CREATE_FAILED);
            } finally {
                creating.remove(playerId);
            }
            BuyResult done = outcome;
            scheduler.sync(() -> result.complete(done));
            });
        } catch (RuntimeException e) {
            creating.remove(playerId);
            log.warning("Falha ao agendar criação de cobrança: " + e);
            return CompletableFuture.completedFuture(new BuyResult.Failed(CREATE_FAILED));
        }
        return result;
    }

    public CompletableFuture<Void> simulate(String chargeId) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        scheduler.async(() -> {
            try {
                if (mode == Mode.GAME && config.channel() == null) api.simulateGamePurchase(chargeId);
                else api.simulatePayment(chargeId);
                result.complete(null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public Snapshot snapshot() {
        return new Snapshot(mode, config.channel(), store.inState(State.PENDING), store.inState(State.PAID));
    }

    public Product productFor(Purchase purchase) {
        return catalog.find(purchase.sku).orElseGet(() -> Product.fallback(purchase.sku, purchase.amount));
    }
}
