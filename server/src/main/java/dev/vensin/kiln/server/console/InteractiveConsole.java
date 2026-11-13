package dev.vensin.kiln.server.console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.TerminalBuilder;

/** The prompt for a person at the keyboard: history, editing and tab completion of commands. */
public final class InteractiveConsole {

    private static volatile LineReader reader;

    private InteractiveConsole() {}

    /** Log lines go through here once the prompt is up, so they appear above it instead of through it. */
    static boolean print(String text) {
        LineReader active = reader;
        if (active == null) {
            return false;
        }
        active.printAbove(text.stripTrailing());
        return true;
    }

    public static void start() throws IOException {
        var terminal = TerminalBuilder.builder().system(true).build();
        LineReader lines = LineReaderBuilder.builder().terminal(terminal).appName("kiln")
                .completer((line, parsed, candidates) -> complete(parsed, candidates))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        reader = lines;
        Thread.ofPlatform().daemon().name("console-input").start(() -> read(lines));
    }

    private static void read(LineReader lines) {
        String prompt = Ansi.orange(Ansi.PROMPT) + " ";
        while (true) {
            try {
                ConsoleInput.run(lines.readLine(prompt));
            } catch (UserInterruptException ctrlC) {
                ConsoleInput.run("stop");
            } catch (EndOfFileException closed) {
                return;
            }
        }
    }

    /** Walks the command tree along the words typed so far and offers what can follow. */
    private static void complete(ParsedLine parsed, List<Candidate> candidates) {
        List<String> words = parsed.words();
        int index = parsed.wordIndex();
        List<String> options = new ArrayList<>();
        if (index == 0) {
            MinecraftServer.getCommandManager().getCommands().forEach(command -> options.add(command.getName()));
        } else {
            String first = words.getFirst().startsWith("/") ? words.getFirst().substring(1) : words.getFirst();
            Command command = MinecraftServer.getCommandManager().getCommand(first);
            for (int i = 1; i < index && command != null; i++) {
                String word = words.get(i);
                command = command.getSubcommands().stream().filter(sub -> sub.getName().equals(word)).findFirst().orElse(null);
            }
            if (command != null) {
                command.getSubcommands().forEach(sub -> options.add(sub.getName()));
                if (command.getSubcommands().isEmpty() && !command.getSyntaxes().isEmpty()) {
                    MinecraftServer.getConnectionManager().getOnlinePlayers().stream().map(Player::getUsername)
                            .forEach(options::add);
                }
            }
        }
        options.stream().sorted().map(Candidate::new).forEach(candidates::add);
    }
}
