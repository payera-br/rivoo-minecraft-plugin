package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.AckResult;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.FakePlayers;
import dev.rivoo.minecraft.core.fixtures.RecordingCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TakeCommandTest {
    static final String DIAMOND = "minecraft:diamond";
    final UUID steve = UUID.randomUUID();
    final FakePlayers players = new FakePlayers().online(steve, "Steve");
    final RecordingCommands console = new RecordingCommands();
    DeliveryEngine engine;

    @BeforeEach void setUp(@TempDir Path dir) {
        CapturingLog log = new CapturingLog();
        engine = new DeliveryEngine(null, null, null, null, players, console, null, null,
                new AckStore(dir.resolve("acks.json"), log.logger), log.logger, Clock.systemUTC());
    }

    List<AckResult> run(String command) {
        return engine.runCommands(List.of(new CommandRun("r1", command, steve, "Steve", true, 0)));
    }

    @Test void takesExactlyWhenThePlayerHasEnough() {
        players.give(steve, DIAMOND, 10);
        assertThat(run("rivoo:take Steve minecraft:diamond 4")).containsExactly(AckResult.ok("r1"));
        assertThat(players.held(steve, DIAMOND)).isEqualTo(6);
        assertThat(console.ran).isEmpty();
    }

    @Test void acceptsBareMaterialName() {
        players.give(steve, DIAMOND, 3);
        assertThat(run("rivoo:take Steve diamond 3")).containsExactly(AckResult.ok("r1"));
        assertThat(players.held(steve, DIAMOND)).isZero();
    }

    @Test void failsAndRemovesNothingWhenShort() {
        players.give(steve, DIAMOND, 3);
        assertThat(run("rivoo:take Steve minecraft:diamond 5"))
                .containsExactly(AckResult.failed("r1", "Steve tem só 3"));
        assertThat(players.held(steve, DIAMOND)).isEqualTo(3);
    }

    @Test void countsStacksAcrossTheInventory() {
        players.give(steve, DIAMOND, 64).give(steve, DIAMOND, 64).give(steve, DIAMOND, 10);
        assertThat(run("rivoo:take Steve minecraft:diamond 130")).containsExactly(AckResult.ok("r1"));
        assertThat(players.held(steve, DIAMOND)).isEqualTo(8);
    }

    @Test void itemsWithCustomMetaDoNotCount() {
        players.give(steve, DIAMOND, 2).giveCustom(steve, DIAMOND, 64);
        assertThat(run("rivoo:take Steve minecraft:diamond 3"))
                .containsExactly(AckResult.failed("r1", "Steve tem só 2"));
        assertThat(players.held(steve, DIAMOND)).isEqualTo(66);
    }

    @Test void malformedCommandFailsWithoutTouchingTheInventory() {
        players.give(steve, DIAMOND, 10);
        for (String bad : List.of("rivoo:take Steve minecraft:diamond", "rivoo:take Steve minecraft:diamond 0",
                "rivoo:take Steve minecraft:diamond -1", "rivoo:take Steve minecraft:diamond 2305",
                "rivoo:take Steve minecraft:diamond abc", "rivoo:take Steve minecraft:diamond 1 2",
                "rivoo:take Steve min!craft:diamond 1")) {
            List<AckResult> acks = run(bad);
            assertThat(acks).hasSize(1);
            assertThat(acks.get(0).ok()).as(bad).isFalse();
            assertThat(acks.get(0).error()).contains("inválido");
        }
        assertThat(players.held(steve, DIAMOND)).isEqualTo(10);
        assertThat(console.ran).isEmpty();
    }

    @Test void offlinePlayerLeavesTheCommandUnacked() {
        players.give(steve, DIAMOND, 10).offline(steve);
        assertThat(run("rivoo:take Steve minecraft:diamond 4")).isEmpty();
        assertThat(players.held(steve, DIAMOND)).isEqualTo(10);
    }
}
