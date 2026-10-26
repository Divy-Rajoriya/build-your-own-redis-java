package dev.redisclone.server;

import dev.redisclone.command.CommandHandler;
import dev.redisclone.protocol.ProtocolException;
import dev.redisclone.protocol.Resp;
import dev.redisclone.protocol.RespParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

/** Serves one client connection: read a command, execute it, write the reply, repeat. */
final class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandHandler commands;

    ClientHandler(Socket socket, CommandHandler commands) {
        this.socket = socket;
        this.commands = commands;
    }

    @Override
    public void run() {
        try (Socket s = socket;
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            RespParser parser = new RespParser(s.getInputStream());
            while (true) {
                List<String> command;
                try {
                    command = parser.readCommand();
                } catch (ProtocolException e) {
                    out.write(Resp.error("ERR Protocol error: " + e.getMessage()));
                    out.flush();
                    return;
                }
                if (command == null) {
                    return; // client closed the connection
                }
                out.write(commands.execute(command));
                out.flush();
                if ("QUIT".equalsIgnoreCase(command.get(0))) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // client went away; nothing to do
        }
    }
}
