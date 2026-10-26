package dev.redisclone.server;

import dev.redisclone.command.CommandHandler;
import dev.redisclone.store.Store;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A TCP server speaking RESP. One thread per client; commands are serialized on the store. */
public final class RedisServer implements AutoCloseable {

    private final int requestedPort;
    private final Store store = new Store();
    private final CommandHandler commands = new CommandHandler(store);
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "expiry-janitor");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    public RedisServer(int port) {
        this.requestedPort = port;
    }

    /** Binds the port and starts accepting clients in the background. */
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(requestedPort));
        this.serverSocket = socket;

        // Active expiry: sweep expired keys 10 times a second.
        janitor.scheduleWithFixedDelay(() -> {
            synchronized (store) {
                store.purgeExpired();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        acceptThread = new Thread(this::acceptLoop, "acceptor");
        acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Blocks until the server is closed. */
    public void join() throws InterruptedException {
        acceptThread.join();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                clients.execute(new ClientHandler(client, commands));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
        janitor.shutdownNow();
        clients.shutdownNow();
    }
}
