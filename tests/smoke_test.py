#!/usr/bin/env python3
"""
End-to-end smoke test: talks to a running redis-clone over a raw TCP socket using RESP.

    java -jar target/redis-clone.jar --port 6380 &
    python3 tests/smoke_test.py 6380
"""
import socket
import sys
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 6379
sock = socket.create_connection(("127.0.0.1", PORT), timeout=5)
reader = sock.makefile("rb")


def send(*args):
    payload = f"*{len(args)}\r\n".encode()
    for arg in args:
        data = str(arg).encode()
        payload += b"$" + str(len(data)).encode() + b"\r\n" + data + b"\r\n"
    sock.sendall(payload)
    return read_reply()


def read_reply():
    line = reader.readline().rstrip(b"\r\n")
    kind, rest = line[:1], line[1:]
    if kind == b"+":
        return rest.decode()
    if kind == b"-":
        return Exception(rest.decode())
    if kind == b":":
        return int(rest)
    if kind == b"$":
        n = int(rest)
        if n == -1:
            return None
        data = reader.read(n + 2)[:-2]
        return data.decode()
    if kind == b"*":
        n = int(rest)
        return None if n == -1 else [read_reply() for _ in range(n)]
    raise AssertionError(f"bad reply: {line!r}")


passed = failed = 0


def check(label, actual, expected):
    global passed, failed
    ok = (str(actual) == str(expected)) if isinstance(expected, Exception) or isinstance(actual, Exception) else actual == expected
    if isinstance(actual, Exception) and isinstance(expected, str) and expected.startswith("ERR"):
        ok = str(actual).startswith(expected)
    if isinstance(actual, Exception) and isinstance(expected, str) and expected.startswith("WRONGTYPE"):
        ok = str(actual).startswith("WRONGTYPE")
    if ok:
        passed += 1
        print(f"  ok    {label}")
    else:
        failed += 1
        print(f"  FAIL  {label}: expected {expected!r}, got {actual!r}")


send("FLUSHALL")

print("connection")
check("PING", send("PING"), "PONG")
check("PING msg", send("PING", "hey"), "hey")
check("ECHO", send("ECHO", "hello world"), "hello world")
check("unknown command", send("NOPE"), "ERR unknown command")
check("arity", send("GET"), "ERR wrong number")

print("strings")
check("SET", send("SET", "name", "ada"), "OK")
check("GET", send("GET", "name"), "ada")
check("GET missing", send("GET", "missing"), None)
check("utf-8 value", (send("SET", "emoji", "héllo ✓"), send("GET", "emoji"))[1], "héllo ✓")
check("SET NX on existing", send("SET", "name", "bob", "NX"), None)
check("SET XX on missing", send("SET", "ghost", "x", "XX"), None)
check("value unchanged", send("GET", "name"), "ada")
check("APPEND", send("APPEND", "name", "!!"), 5)
check("STRLEN", send("STRLEN", "name"), 5)
check("INCR new", send("INCR", "counter"), 1)
check("INCR", send("INCR", "counter"), 2)
check("INCRBY", send("INCRBY", "counter", 10), 12)
check("DECR", send("DECR", "counter"), 11)
check("INCR non-int", send("INCR", "name"), "ERR value is not an integer")
check("MSET", send("MSET", "a", 1, "b", 2), "OK")
check("MGET", send("MGET", "a", "b", "zzz"), ["1", "2", None])

print("keys & expiry")
check("EXISTS", send("EXISTS", "a", "b", "zzz"), 2)
check("TYPE string", send("TYPE", "a"), "string")
check("TYPE none", send("TYPE", "zzz"), "none")
check("DEL", send("DEL", "a", "b", "zzz"), 2)
send("SET", "temp", "v", "PX", 150)
check("TTL before", send("PTTL", "temp") > 0, True)
time.sleep(0.3)
check("expired GET", send("GET", "temp"), None)
send("SET", "t2", "v", "EX", 100)
check("TTL secs", 98 <= send("TTL", "t2") <= 100, True)
check("PERSIST", send("PERSIST", "t2"), 1)
check("TTL -1", send("TTL", "t2"), -1)
check("TTL missing", send("TTL", "nope"), -2)
check("EXPIRE", send("EXPIRE", "t2", 1), 1)
check("SET clears TTL", (send("SET", "t3", "v", "EX", 100), send("SET", "t3", "w"), send("TTL", "t3"))[2], -1)
send("FLUSHALL")
for k in ("user:1", "user:2", "post:1"):
    send("SET", k, "x")
check("KEYS user:*", sorted(send("KEYS", "user:*")), ["user:1", "user:2"])
check("KEYS ?ost:1", send("KEYS", "?ost:1"), ["post:1"])
check("DBSIZE", send("DBSIZE"), 3)

print("lists")
send("FLUSHALL")
check("RPUSH", send("RPUSH", "l", "a", "b", "c"), 3)
check("LPUSH", send("LPUSH", "l", "z", "y"), 5)
check("LRANGE all", send("LRANGE", "l", 0, -1), ["y", "z", "a", "b", "c"])
check("LRANGE slice", send("LRANGE", "l", 1, 2), ["z", "a"])
check("LRANGE neg", send("LRANGE", "l", -2, -1), ["b", "c"])
check("LRANGE out of range", send("LRANGE", "l", 10, 20), [])
check("LLEN", send("LLEN", "l"), 5)
check("LPOP", send("LPOP", "l"), "y")
check("RPOP", send("RPOP", "l"), "c")
check("TYPE list", send("TYPE", "l"), "list")
check("WRONGTYPE", send("GET", "l"), "WRONGTYPE")
send("LPOP", "l"); send("LPOP", "l"); send("LPOP", "l")
check("empty list removed", send("EXISTS", "l"), 0)
check("LPOP missing", send("LPOP", "l"), None)

print("hashes")
check("HSET", send("HSET", "h", "f1", "v1", "f2", "v2"), 2)
check("HSET update", send("HSET", "h", "f1", "new"), 0)
check("HGET", send("HGET", "h", "f1"), "new")
check("HGET missing", send("HGET", "h", "nope"), None)
check("HEXISTS", send("HEXISTS", "h", "f2"), 1)
check("HLEN", send("HLEN", "h"), 2)
check("HGETALL", send("HGETALL", "h"), ["f1", "new", "f2", "v2"])
check("HDEL", send("HDEL", "h", "f1", "nope"), 1)
check("TYPE hash", send("TYPE", "h"), "hash")

print("sets")
check("SADD", send("SADD", "s", "a", "b", "a"), 2)
check("SISMEMBER", send("SISMEMBER", "s", "a"), 1)
check("SCARD", send("SCARD", "s"), 2)
check("SMEMBERS", sorted(send("SMEMBERS", "s")), ["a", "b"])
check("SREM", send("SREM", "s", "a"), 1)
check("TYPE set", send("TYPE", "s"), "set")

print("pipelining & inline")
sock.sendall(b"*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$1\r\nx\r\n*1\r\n$4\r\nPING\r\n")
check("pipelined 1", read_reply(), "PONG")
check("pipelined 2", read_reply(), "x")
check("pipelined 3", read_reply(), "PONG")
sock.sendall(b"PING\r\n")
check("inline PING", read_reply(), "PONG")

print("concurrency")
import threading
results = []
def worker():
    c = socket.create_connection(("127.0.0.1", PORT)); r = c.makefile("rb")
    for _ in range(200):
        c.sendall(b"*2\r\n$4\r\nINCR\r\n$4\r\nhits\r\n"); r.readline()
    c.close(); results.append(1)
send("DEL", "hits")
threads = [threading.Thread(target=worker) for _ in range(8)]
[t.start() for t in threads]; [t.join() for t in threads]
check("8 clients x 200 INCR is atomic", send("GET", "hits"), "1600")

print("protocol errors")
bad = socket.create_connection(("127.0.0.1", PORT)); bad.sendall(b"*1\r\n+oops\r\n")
check("bad protocol -> error + close", bad.recv(100).startswith(b"-ERR Protocol error"), True)

check("QUIT", send("QUIT"), "OK")
print(f"\n{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
