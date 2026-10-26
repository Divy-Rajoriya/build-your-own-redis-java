package dev.redisclone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.redisclone.protocol.ProtocolException;
import dev.redisclone.protocol.RespParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespParserTest {

    private static RespParser parserFor(String input) {
        return new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesArrayOfBulkStrings() throws IOException {
        RespParser parser = parserFor("*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n");
        assertEquals(List.of("ECHO", "hello"), parser.readCommand());
        assertNull(parser.readCommand());
    }

    @Test
    void parsesPipelinedCommands() throws IOException {
        RespParser parser = parserFor("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");
        assertEquals(List.of("PING"), parser.readCommand());
        assertEquals(List.of("PING"), parser.readCommand());
        assertNull(parser.readCommand());
    }

    @Test
    void bulkStringsMayContainCrlf() throws IOException {
        RespParser parser = parserFor("*2\r\n$3\r\nSET\r\n$4\r\na\r\nb\r\n");
        assertEquals(List.of("SET", "a\r\nb"), parser.readCommand());
    }

    @Test
    void lengthIsMeasuredInBytesNotChars() throws IOException {
        // "\u00e9" is 2 bytes in UTF-8
        RespParser parser = parserFor("*1\r\n$2\r\n\u00e9\r\n");
        assertEquals(List.of("\u00e9"), parser.readCommand());
    }

    @Test
    void parsesInlineCommands() throws IOException {
        RespParser parser = parserFor("SET  key   value\r\n");
        assertEquals(List.of("SET", "key", "value"), parser.readCommand());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n+oops\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> parserFor("*x\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n$5\r\nabc").readCommand());
    }
}
