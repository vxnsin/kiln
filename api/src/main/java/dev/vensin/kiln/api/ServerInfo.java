package dev.vensin.kiln.api;

import java.util.List;

public record ServerInfo(String id, int maxPlayers, List<String> players) {}
