package dev.vensin.kiln.server.store;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Just enough of a Redis server for the client tests: AUTH, SELECT, PING, GET, SET, DEL and a one-page SCAN. */
public final class FakeRedis implements AutoCloseable {

    public final Map<String, String> data = new ConcurrentHashMap<>();
    public final List<String> commands = new CopyOnWriteArrayList<>();
    private static final String CRLF = "\r\n";
    private final Map<OutputStream, String> subscribers = new ConcurrentHashMap<>();
    private final ServerSocket server = new ServerSocket(0);

    public FakeRedis() throws IOException {
        Thread.ofPlatform().daemon().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Thread.ofPlatform().daemon().start(() -> serve(client));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    public int port() {
        return server.getLocalPort();
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            while (true) {
                List<String> command = readCommand(in);
                if (command == null) {
                    return;
                }
                commands.add(String.join(" ", command));
                if (command.getFirst().equals("PSUBSCRIBE")) {
                    subscribers.put(out, command.get(1));
                    out.write(("*3" + CRLF + bulk("psubscribe") + bulk(command.get(1)) + ":1" + CRLF)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                out.write(answer(command).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException gone) {
            // client closed the connection
        }
    }

    private String answer(List<String> command) {
        return switch (command.getFirst()) {
            case "AUTH", "SELECT", "SET" -> {
                if (command.getFirst().equals("SET")) {
                    data.put(command.get(1), command.get(2));
                }
                yield "+OK\r\n";
            }
            case "PING" -> "+PONG\r\n";
            case "PUBLISH" -> ":" + publish(command.get(1), command.get(2)) + CRLF;
            case "GET" -> bulk(data.get(command.get(1)));
            case "DEL" -> ":" + (data.remove(command.get(1)) != null ? 1 : 0) + "\r\n";
            case "SCAN" -> {
                String prefix = command.get(3).substring(0, command.get(3).length() - 1);
                List<String> keys = data.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
                StringBuilder reply = new StringBuilder("*2\r\n").append(bulk("0")).append("*").append(keys.size()).append("\r\n");
                keys.forEach(key -> reply.append(bulk(key)));
                yield reply.toString();
            }
            default -> "-ERR unknown command\r\n";
        };
    }


    private int publish(String channel, String message) {
        int delivered = 0;
        for (Map.Entry<OutputStream, String> subscriber : subscribers.entrySet()) {
            String pattern = subscriber.getValue();
            if (channel.startsWith(pattern.substring(0, pattern.length() - 1))) {
                try {
                    synchronized (subscriber.getKey()) {
                        subscriber.getKey().write(("*4\r\n" + bulk("pmessage") + bulk(pattern) + bulk(channel) + bulk(message))
                                .getBytes(StandardCharsets.UTF_8));
                        subscriber.getKey().flush();
                    }
                    delivered++;
                } catch (IOException gone) {
                    subscribers.remove(subscriber.getKey());
                }
            }
        }
        return delivered;
    }
    private static String bulk(String value) {
        if (value == null) {
            return "$-1\r\n";
        }
        return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
    }

    private static List<String> readCommand(InputStream in) throws IOException {
        String header = line(in);
        if (header == null) {
            return null;
        }
        int count = Integer.parseInt(header.substring(1));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = Integer.parseInt(line(in).substring(1));
            parts.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
            in.readNBytes(2);
        }
        return parts;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return line.substring(0, line.length() - 1);
            }
            line.append((char) c);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
