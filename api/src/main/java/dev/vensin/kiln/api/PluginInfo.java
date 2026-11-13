package dev.vensin.kiln.api;

import java.util.List;

public record PluginInfo(String name, String version, String main, String description, List<String> authors,
                         List<String> depends) {}
