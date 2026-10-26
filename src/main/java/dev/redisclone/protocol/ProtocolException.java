package dev.redisclone.protocol;

import java.io.IOException;

/** Thrown when a client sends bytes that are not valid RESP. */
public class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}
