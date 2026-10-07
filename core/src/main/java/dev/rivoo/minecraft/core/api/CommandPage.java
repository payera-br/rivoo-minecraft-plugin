package dev.rivoo.minecraft.core.api;

import java.util.List;

public record CommandPage(List<CommandRun> runs, boolean hasMore) {}
