package dev.vensin.kiln.server.store;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A small client for the Redis protocol (RESP2), enough for key-value storage and pub/sub. It speaks to Redis,
 * Valkey, Dragonfly and anything else that implements the protocol. One connection, reconnected on failure.
 */
public final class RespClient implements AutoCloseable {

    private static final int TIMEOUT_MILLIS = 5000;

    private final URI uri;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public RespClient(URI uri) {
        this.uri = uri;
    }

    /** Sends one command and returns its reply: a String, Long, List, or null for a missing value. */
    public synchronized Object call(String... command) throws IOException {
        try {
            return send(command);
        } catch (IOException first) {
            close();
            return send(command);
        }
    }

    /**
     * Blocks on a second connection and hands every message on channels matching the pattern to the listener,
     * until the connection breaks.
     */
    public void subscribe(String pattern, MessageListener listener) throws IOException {
        try (RespClient subscriber = new RespClient(uri)) {
            subscriber.connect(0);
            subscriber.write(new String[] {"PSUBSCRIBE", pattern});
            if (subscriber.read() instanceof RespError error) {
                throw new IOException("subscribing was refused: " + error.message());
            }
            while (true) {
                if (subscriber.read() instanceof List<?> message && message.size() == 4 && "pmessage".equals(message.get(0))) {
                    listener.accept(String.valueOf(message.get(2)), String.valueOf(message.get(3)));
                }
            }
        }
    }

    public interface MessageListener {
        void accept(String channel, String message);
    }

    private Object send(String[] command) throws IOException {
        if (socket == null) {
            connect(TIMEOUT_MILLIS);
        }
        write(command);
        Object reply = read();
        if (reply instanceof RespError error) {
            throw new IOException(error.message());
        }
        return reply;
    }

    private void connect(int readTimeout) throws IOException {
        Socket created = new Socket();
        created.connect(new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379), TIMEOUT_MILLIS);
        created.setSoTimeout(readTimeout);
        created.setKeepAlive(true);
        socket = created;
        in = new BufferedInputStream(created.getInputStream());
        out = created.getOutputStream();
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] parts = userInfo.split(":", 2);
            String password = decode(parts.length == 2 ? parts[1] : parts[0]);
            check(parts.length == 2 && !parts[0].isEmpty()
                    ? new String[] {"AUTH", decode(parts[0]), password} : new String[] {"AUTH", password});
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            check(new String[] {"SELECT", path.substring(1)});
        }
    }

    private void check(String[] command) throws IOException {
        write(command);
        if (read() instanceof RespError error) {
            throw new IOException(command[0] + " failed: " + error.message());
        }
    }

    private void write(String[] command) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(("*" + command.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String part : command) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            buffer.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buffer.writeBytes(bytes);
            buffer.writeBytes(new byte[] {'\r', '\n'});
        }
        out.write(buffer.toByteArray());
        out.flush();
    }

    private Object read() throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("connection closed");
        }
        String line = readLine();
        return switch (type) {
            case '+' -> line;
            case '-' -> new RespError(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length < 0) {
                    yield null;
                }
                byte[] bytes = in.readNBytes(length);
                in.readNBytes(2);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    yield null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(read());
                }
                yield items;
            }
            default -> throw new IOException("unexpected reply type " + (char) type);
        };
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            line.write(current);
            previous = current;
        }
        throw new IOException("connection closed");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
            socket = null;
        }
    }

    private record RespError(String message) {}
}
