package dev.redisclone.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads client commands from a stream.
 *
 * <p>Supports the two forms Redis accepts:
 * <ul>
 *   <li>RESP arrays of bulk strings: {@code *2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n}</li>
 *   <li>Inline commands: {@code PING\r\n} (handy when testing with telnet / nc)</li>
 * </ul>
 */
public final class RespParser {

    private static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    private static final int MAX_ARRAY_LENGTH = 1024 * 1024;
    private static final int MAX_LINE_LENGTH = 64 * 1024;

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    /**
     * Reads the next command.
     *
     * @return the command as a list of strings (name first), or {@code null} on clean EOF
     */
    public List<String> readCommand() throws IOException {
        while (true) {
            int first = in.read();
            if (first == -1) {
                return null;
            }
            if (first == '\r' || first == '\n') {
                continue; // blank line between commands
            }
            if (first == '*') {
                List<String> command = readArray();
                if (command.isEmpty()) {
                    continue; // empty array: ignore like real Redis
                }
                return command;
            }
            List<String> inline = readInline(first);
            if (inline.isEmpty()) {
                continue;
            }
            return inline;
        }
    }

    private List<String> readArray() throws IOException {
        long count = parseLong(readLine(), "invalid multibulk length");
        if (count > MAX_ARRAY_LENGTH) {
            throw new ProtocolException("invalid multibulk length");
        }
        List<String> items = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            int type = in.read();
            if (type == -1) {
                throw new ProtocolException("unexpected end of stream");
            }
            if (type != '$') {
                throw new ProtocolException("expected '$', got '" + (char) type + "'");
            }
            items.add(readBulkString());
        }
        return items;
    }

    private String readBulkString() throws IOException {
        long length = parseLong(readLine(), "invalid bulk length");
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new ProtocolException("invalid bulk length");
        }
        byte[] data = in.readNBytes((int) length);
        if (data.length != length) {
            throw new ProtocolException("unexpected end of stream");
        }
        if (in.read() != '\r' || in.read() != '\n') {
            throw new ProtocolException("bulk string not terminated by CRLF");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private List<String> readInline(int firstByte) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        line.write(firstByte);
        line.write(readLineBytes());
        String text = line.toString(StandardCharsets.UTF_8).trim();
        List<String> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String part : text.split("\\s+")) {
                parts.add(part);
            }
        }
        return parts;
    }

    /** Reads up to (and consumes) the next LF, returning the line without CR/LF. */
    private String readLine() throws IOException {
        return new String(readLineBytes(), StandardCharsets.UTF_8);
    }

    private byte[] readLineBytes() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new ProtocolException("unexpected end of stream");
            }
            if (b == '\n') {
                break;
            }
            buffer.write(b);
            if (buffer.size() > MAX_LINE_LENGTH) {
                throw new ProtocolException("line too long");
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static long parseLong(String text, String errorMessage) throws ProtocolException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException(errorMessage);
        }
    }
}
