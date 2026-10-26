package dev.redisclone;

import dev.redisclone.server.RedisServer;

/** Entry point. Usage: {@code java -jar redis-clone.jar [--port 6379]} */
public final class Main {

    private static final int DEFAULT_PORT = 6379;

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        RedisServer server = new RedisServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
        server.start();
        System.out.println("redis-clone listening on port " + server.getPort());
        server.join();
    }

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return DEFAULT_PORT;
    }
}
