package dev.redisclone.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/** Encoders for RESP2 reply types. Every method returns the bytes to send. */
public final class Resp {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] OK = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.UTF_8);

    private Resp() {}

    public static byte[] ok() {
        return OK;
    }

    public static byte[] simple(String text) {
        return ("+" + text + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] error(String message) {
        return ("-" + message + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] integer(long value) {
        return (":" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] bulk(String text) {
        if (text == null) {
            return NULL_BULK;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        out.writeBytes(("$" + data.length).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
        out.writeBytes(data);
        out.writeBytes(CRLF);
        return out.toByteArray();
    }

    public static byte[] nullBulk() {
        return NULL_BULK;
    }

    public static byte[] nullArray() {
        return NULL_ARRAY;
    }

    /** Array whose elements are bulk strings ({@code null} elements become null bulks). */
    public static byte[] bulkArray(Collection<String> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + items.size() + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String item : items) {
            out.writeBytes(bulk(item));
        }
        return out.toByteArray();
    }

    /** Array of already-encoded elements. */
    public static byte[] array(List<byte[]> encodedElements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + encodedElements.size() + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (byte[] element : encodedElements) {
            out.writeBytes(element);
        }
        return out.toByteArray();
    }

    public static byte[] emptyArray() {
        return "*0\r\n".getBytes(StandardCharsets.UTF_8);
    }
}
