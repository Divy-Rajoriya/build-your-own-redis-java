package dev.redisclone.command;

/** An error that is reported to the client as a RESP error reply (e.g. "-ERR ..."). */
public class RedisError extends RuntimeException {
    public RedisError(String message) {
        super(message, null, false, false); // no stack trace needed
    }

    public static RedisError wrongType() {
        return new RedisError("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    public static RedisError notInteger() {
        return new RedisError("ERR value is not an integer or out of range");
    }

    public static RedisError syntax() {
        return new RedisError("ERR syntax error");
    }

    public static RedisError arity(String command) {
        return new RedisError("ERR wrong number of arguments for '" + command.toLowerCase() + "' command");
    }
}
