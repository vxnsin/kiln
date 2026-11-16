package dev.vensin.kiln.server.console;

import java.io.PrintStream;
import java.util.Locale;

/** The block printed once at startup; everything after it goes through the logger. */
public final class StartReport {

    private static final String AUTHOR_URL = "https://github.com/vxnsin";
    private static final String REPO_URL = "https://github.com/vxnsin/kiln";
    private static final int LABEL_WIDTH = 8;
    private static final String[] SPINNER = {
        "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏",
    };
    private static final long FRAME_MILLIS = 80;

    private final PrintStream out;
    private final long startedAt = System.nanoTime();

    public StartReport(PrintStream out) {
        this.out = out;
    }

    public void header(String version, String minecraft) {
        String[] lines = {
            "",
            Ansi.bold("Kiln") + Ansi.dim(" " + version),
            Ansi.dim("a Minestom server that is ready to run"),
            Ansi.dim("Minecraft " + minecraft + " " + Ansi.DOT + " Java "
                    + Runtime.version().feature()),
            Ansi.dim("made by ") + Ansi.orange(Ansi.link(AUTHOR_URL, "vxnsin")) + Ansi.dim(" " + Ansi.DOT + " "
                    + Ansi.link(REPO_URL, "github.com/vxnsin/kiln")),
        };
        out.println();
        for (int row = 0; row < lines.length; row++) {
            out.println(" " + iconLine(row) + "   " + lines[row]);
        }
        out.println();
    }

    private static String iconLine(int row) {
        if (!Ansi.UNICODE || !Ansi.COLOR) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        String top = KilnIcon.PIXELS[row * 2];
        String bottom = KilnIcon.PIXELS[row * 2 + 1];
        for (int column = 0; column < top.length(); column++) {
            line.append(Ansi.pixels(iconColor(top.charAt(column)), iconColor(bottom.charAt(column))));
        }
        return line.toString();
    }

    private static int iconColor(char pixel) {
        int index = KilnIcon.KEYS.indexOf(pixel);
        return index < 0 ? 0 : KilnIcon.TERMINAL[index];
    }

    /** Shows a spinner while the step runs when a person is watching; otherwise only the result line appears. */
    public Step begin(String label, String doing) {
        return new Step(label, doing, true);
    }

    /** A step without a spinner, for work that writes log lines of its own while it runs. */
    public Step beginQuietly(String label) {
        return new Step(label, "", false);
    }

    public void fail(String label, String text) {
        row(Ansi.red(Ansi.ERROR), label, Ansi.red(text));
    }

    /** An indented line under the last step, e.g. one entry of a list. */
    public void detail(String text) {
        out.println("    " + text);
    }

    public void done() {
        out.println();
    }

    public String elapsed() {
        return String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - startedAt) / 1e9);
    }

    private void row(String symbol, String label, String text) {
        out.println("  " + symbol + " " + Ansi.bold(String.format("%-" + LABEL_WIDTH + "s", label)) + " " + text);
    }

    public final class Step {

        private final String label;
        private final long begunAt = System.nanoTime();
        private final Thread animation;
        private volatile boolean running = true;

        private Step(String label, String doing, boolean animate) {
            this.label = label;
            if (animate && Terminal.interactive() && Ansi.UNICODE && Ansi.COLOR) {
                animation = Thread.ofPlatform().daemon().start(() -> animate(doing));
            } else {
                animation = null;
            }
        }

        public void ok(String text) {
            finish();
            row(Ansi.green(Ansi.OK), label, text);
        }

        public void warn(String text) {
            finish();
            row(Ansi.yellow(Ansi.WARN), label, Ansi.yellow(text));
        }

        public void fail(String text) {
            finish();
            row(Ansi.red(Ansi.ERROR), label, Ansi.red(text));
        }

        public long millis() {
            return (System.nanoTime() - begunAt) / 1_000_000;
        }

        private void animate(String doing) {
            int frame = 0;
            while (running) {
                out.print(Ansi.CLEAR_LINE + "  " + Ansi.orange(SPINNER[frame++ % SPINNER.length]) + " "
                        + Ansi.bold(String.format("%-" + LABEL_WIDTH + "s", label)) + " " + Ansi.dim(doing));
                out.flush();
                try {
                    Thread.sleep(FRAME_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private void finish() {
            running = false;
            if (animation != null) {
                animation.interrupt();
                try {
                    animation.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.print(Ansi.CLEAR_LINE);
            }
        }
    }
}
