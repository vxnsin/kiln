package dev.vensin.kiln.api;

import java.util.List;

/** {@code address} is host:port as players reach the server, empty when it does not take transfers. */
public record ServerInfo(String id, String address, int maxPlayers, List<String> players) {}
