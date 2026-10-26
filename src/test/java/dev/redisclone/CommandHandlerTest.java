package dev.redisclone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.redisclone.command.CommandHandler;
import dev.redisclone.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandHandlerTest {

    private AtomicLong clock;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000);
        handler = new CommandHandler(new Store(clock::get));
    }

    private String run(String... args) {
        return new String(handler.execute(Arrays.asList(args)), StandardCharsets.UTF_8);
    }

    @Test
    void pingAndEcho() {
        assertEquals("+PONG\r\n", run("PING"));
        assertEquals("$2\r\nhi\r\n", run("ECHO", "hi"));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        assertEquals("+OK\r\n", run("set", "k", "v"));
        assertEquals("$1\r\nv\r\n", run("GeT", "k"));
    }

    @Test
    void getMissingReturnsNullBulk() {
        assertEquals("$-1\r\n", run("GET", "nope"));
    }

    @Test
    void setWithPxExpiresAfterTheTimeout() {
        run("SET", "k", "v", "PX", "100");
        clock.addAndGet(99);
        assertEquals("$1\r\nv\r\n", run("GET", "k"));
        clock.addAndGet(1);
        assertEquals("$-1\r\n", run("GET", "k"));
    }

    @Test
    void setNxDoesNotOverwrite() {
        run("SET", "k", "first");
        assertEquals("$-1\r\n", run("SET", "k", "second", "NX"));
        assertEquals("$5\r\nfirst\r\n", run("GET", "k"));
    }

    @Test
    void incrKeepsTtlButSetClearsIt() {
        run("SET", "n", "1", "EX", "10");
        run("INCR", "n");
        assertEquals(":10\r\n", run("TTL", "n"));
        run("SET", "n", "5");
        assertEquals(":-1\r\n", run("TTL", "n"));
    }

    @Test
    void incrOnNonIntegerIsAnError() {
        run("SET", "k", "abc");
        assertTrue(run("INCR", "k").startsWith("-ERR value is not an integer"));
    }

    @Test
    void wrongTypeIsReported() {
        run("RPUSH", "l", "a");
        assertTrue(run("GET", "l").startsWith("-WRONGTYPE"));
        run("SET", "s", "a");
        assertTrue(run("LPUSH", "s", "x").startsWith("-WRONGTYPE"));
    }

    @Test
    void listOperations() {
        assertEquals(":3\r\n", run("RPUSH", "l", "a", "b", "c"));
        assertEquals("*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", run("LRANGE", "l", "0", "-1"));
        assertEquals("$1\r\na\r\n", run("LPOP", "l"));
        assertEquals(":2\r\n", run("LLEN", "l"));
    }

    @Test
    void unknownCommandAndArity() {
        assertTrue(run("FOO").startsWith("-ERR unknown command"));
        assertTrue(run("GET").startsWith("-ERR wrong number of arguments"));
    }

    @Test
    void keysSupportsGlobs() {
        run("MSET", "user:1", "a", "user:2", "b", "other", "c");
        String reply = run("KEYS", "user:*");
        assertTrue(reply.startsWith("*2\r\n"));
        assertTrue(reply.contains("user:1") && reply.contains("user:2") && !reply.contains("other"));
    }
}
