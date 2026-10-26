# Build Your Own Redis (in Java)

A from-scratch Redis clone written in Java 17 with **zero runtime dependencies**.
It speaks the real Redis wire protocol (RESP2), so the official `redis-cli` and
most Redis client libraries can talk to it.

Built as a learning project in the spirit of the
[CodeCrafters "Build your own Redis"](https://codecrafters.io/challenges/redis) challenge.

## What you learn by building this

| Concept | Where |
|---|---|
| TCP servers & concurrent clients | `server/RedisServer`, `server/ClientHandler` |
| The RESP wire protocol (parsing + encoding) | `protocol/RespParser`, `protocol/Resp` |
| Command dispatch & Redis error semantics | `command/CommandHandler` |
| In-memory data store, lazy + active key expiry | `store/Store` |
| Atomicity via single-writer command execution | `synchronized (store)` in `CommandHandler` |

## Quick start

Requires JDK 17+ and Maven.

```bash
mvn package
java -jar target/redis-clone.jar            # listens on 6379
java -jar target/redis-clone.jar --port 6380
```

Talk to it with the real `redis-cli`:

```bash
redis-cli -p 6379
127.0.0.1:6379> SET greeting "hello" EX 60
OK
127.0.0.1:6379> GET greeting
"hello"
127.0.0.1:6379> RPUSH queue a b c
(integer) 3
127.0.0.1:6379> LRANGE queue 0 -1
1) "a"
2) "b"
3) "c"
```

...or with plain `nc` / `telnet` (inline commands are supported):

```bash
printf 'PING\r\nSET k v\r\nGET k\r\n' | nc localhost 6379
```

### Docker

```bash
docker build -t redis-clone .
docker run -p 6379:6379 redis-clone
```

## Supported commands

| Group | Commands |
|---|---|
| Connection / server | `PING` `ECHO` `QUIT` `SELECT` `CLIENT` `COMMAND` `CONFIG` `INFO` `DBSIZE` `FLUSHALL` `FLUSHDB` |
| Strings | `SET` (`EX` `PX` `NX` `XX`) `GET` `MSET` `MGET` `APPEND` `STRLEN` `INCR` `DECR` `INCRBY` `DECRBY` |
| Keys | `DEL` `EXISTS` `KEYS` (glob patterns) `TYPE` `EXPIRE` `PEXPIRE` `TTL` `PTTL` `PERSIST` |
| Lists | `LPUSH` `RPUSH` `LPOP` `RPOP` `LLEN` `LRANGE` |
| Hashes | `HSET` `HGET` `HDEL` `HEXISTS` `HLEN` `HGETALL` |
| Sets | `SADD` `SREM` `SISMEMBER` `SCARD` `SMEMBERS` |

Also supported: pipelining, binary-safe length handling for UTF-8 values,
`WRONGTYPE` errors, Redis-style arity errors, and removal of empty lists/hashes/sets.

## Architecture

```
client ──TCP──▶ RedisServer (accept loop)
                   │  one thread per connection
                   ▼
              ClientHandler ──▶ RespParser  (bytes → List<String>)
                   │
                   ▼
              CommandHandler ──▶ Store       (HashMap + TTL metadata)
                   │
                   ▼
                 Resp (encode reply) ──▶ socket
```

* **Concurrency model:** each client gets its own thread for I/O, but every command runs
  inside `synchronized (store)`. That makes every command atomic (e.g. 8 clients doing
  `INCR` concurrently never lose an update), just like Redis' single-threaded core.
* **Expiry:** keys are checked lazily on every access, and a background janitor sweeps
  expired keys 10x per second so unused keys don't leak memory.

```
src/main/java/dev/redisclone/
├── Main.java
├── protocol/   RespParser, Resp, ProtocolException
├── command/    CommandHandler, RedisError, Glob
├── store/      Store
└── server/     RedisServer, ClientHandler
```

## Tests

```bash
mvn test                                   # JUnit 5 unit tests

java -jar target/redis-clone.jar --port 6380 &
python3 tests/smoke_test.py 6380           # 70+ end-to-end checks over a raw socket
```

## Ideas for extending it

These are the next stages of the CodeCrafters challenge, and good next steps:

- [ ] Persistence: RDB snapshots (`SAVE`, `BGSAVE`) or an append-only file
- [ ] Replication (`REPLICAOF`, `PSYNC`)
- [ ] Transactions (`MULTI` / `EXEC` / `DISCARD`)
- [ ] Pub/Sub (`SUBSCRIBE` / `PUBLISH`)
- [ ] Blocking list ops (`BLPOP`)
- [ ] Sorted sets (`ZADD` / `ZRANGE`)
- [ ] Switch to NIO (`Selector`) / virtual threads for better scalability

## License

MIT
