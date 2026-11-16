package dev.vensin.kiln.server;

import dev.vensin.kiln.server.console.Ansi;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** java -jar kiln.jar new &lt;name&gt;: a plugin project that builds straight into this server's plugins folder. */
final class PluginScaffold {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final String TEMPLATE = "template/";

    private PluginScaffold() {}

    static int run(String[] args) {
        if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
            System.out.println("  Usage: java -jar kiln.jar new <name>  (lowercase letters, digits and -, e.g. my-plugin)");
            return 1;
        }
        String name = args[1];
        Path project = Path.of(args.length > 2 ? args[2] : name).toAbsolutePath().normalize();
        try {
            if (Files.exists(project) && !isEmpty(project)) {
                System.out.println("  " + Ansi.red(Ansi.ERROR) + " " + project + " already exists and is not empty");
                return 1;
            }
            create(name, project);
        } catch (IOException | URISyntaxException e) {
            System.out.println("  " + Ansi.red(Ansi.ERROR) + " " + e.getMessage());
            return 1;
        }
        Path here = Path.of("").toAbsolutePath();
        String folder = pathFrom(here, project);
        System.out.println();
        System.out.println("  " + Ansi.green(Ansi.OK) + " " + Ansi.bold(name) + Ansi.dim(" created in " + folder));
        System.out.println();
        System.out.println("    cd " + folder);
        System.out.println("    " + (isWindows() ? "gradlew build" : "./gradlew build") + Ansi.dim("   builds it into plugins/"));
        System.out.println();
        System.out.println(Ansi.dim("  With the server running, every build swaps the plugin within a second."));
        System.out.println();
        return 0;
    }


    private static boolean isEmpty(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Relative when both sit on the same drive, absolute otherwise; Windows cannot relate C: to D:. */
    private static String pathFrom(Path project, Path target) {
        try {
            return slashes(project.relativize(target));
        } catch (IllegalArgumentException differentRoots) {
            return slashes(target);
        }
    }
    private static void create(String name, Path project) throws IOException, URISyntaxException {
        String className = className(name);
        String packageName = name.replace("-", "");
        Path server = Path.of("").toAbsolutePath();
        Path jar = Path.of(PluginScaffold.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, String> values = Map.of(
                "{{name}}", name,
                "{{class}}", className,
                "{{package}}", packageName,
                "{{kilnJar}}", pathFrom(project, jar),
                "{{plugins}}", pathFrom(project, server.resolve("plugins")));

        Path sources = project.resolve("src/main/java").resolve(packageName);
        Path resources = project.resolve("src/main/resources");
        write("build.gradle.kts", project.resolve("build.gradle.kts"), values);
        write("settings.gradle.kts", project.resolve("settings.gradle.kts"), values);
        write("gitignore", project.resolve(".gitignore"), values);
        write("kiln-plugin.yml", resources.resolve("kiln-plugin.yml"), values);
        write("en.yml", resources.resolve("lang/en.yml"), values);
        write("Plugin.java", sources.resolve(className + ".java"), values);
        copy("wrapper/gradlew", project.resolve("gradlew"));
        copy("wrapper/gradlew.bat", project.resolve("gradlew.bat"));
        copy("wrapper/gradle/wrapper/gradle-wrapper.jar", project.resolve("gradle/wrapper/gradle-wrapper.jar"));
        copy("wrapper/gradle/wrapper/gradle-wrapper.properties", project.resolve("gradle/wrapper/gradle-wrapper.properties"));
        project.resolve("gradlew").toFile().setExecutable(true);
    }

    private static void write(String template, Path target, Map<String, String> values) throws IOException {
        String text = new String(read(template), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace(value.getKey(), value.getValue());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private static void copy(String template, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, read(template));
    }

    private static byte[] read(String template) throws IOException {
        try (InputStream in = PluginScaffold.class.getClassLoader().getResourceAsStream(TEMPLATE + template)) {
            if (in == null) {
                throw new IOException("template " + template + " is missing from the jar");
            }
            return in.readAllBytes();
        }
    }

    private static String className(String name) {
        StringBuilder result = new StringBuilder();
        for (String part : name.split("-")) {
            if (!part.isEmpty()) {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return result.append("Plugin").toString();
    }

    private static String slashes(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
