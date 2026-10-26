package dev.redisclone.command;

import dev.redisclone.protocol.Resp;
import dev.redisclone.store.Store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Executes parsed commands against the {@link Store} and returns the RESP-encoded reply.
 *
 * <p>All commands run under a single lock on the store, so every command is atomic and
 * the server behaves like Redis' single-threaded command loop.
 */
public final class CommandHandler {

    private final Store store;
    private final long startedAtMillis;

    public CommandHandler(Store store) {
        this.store = store;
        this.startedAtMillis = store.now();
    }

    /** @param args the full command, with the command name at index 0 */
    public byte[] execute(List<String> args) {
        String name = args.get(0).toUpperCase(Locale.ROOT);
        try {
            synchronized (store) {
                return dispatch(name, args);
            }
        } catch (RedisError e) {
            return Resp.error(e.getMessage());
        }
    }

    private byte[] dispatch(String name, List<String> a) {
        switch (name) {
            // ---- connection / server ----
            case "PING":
                arity(a, 1, 2);
                return a.size() == 2 ? Resp.bulk(a.get(1)) : Resp.simple("PONG");
            case "ECHO":
                arity(a, 2, 2);
                return Resp.bulk(a.get(1));
            case "QUIT":
                return Resp.ok();
            case "SELECT":
            case "CLIENT":
                return Resp.ok();
            case "COMMAND":
            case "CONFIG":
                return Resp.emptyArray();
            case "DBSIZE":
                return Resp.integer(store.size());
            case "FLUSHALL":
            case "FLUSHDB":
                store.clear();
                return Resp.ok();
            case "INFO":
                return Resp.bulk(info());

            // ---- generic keys ----
            case "DEL":
                arity(a, 2, -1);
                return Resp.integer(a.subList(1, a.size()).stream().filter(store::delete).count());
            case "EXISTS":
                arity(a, 2, -1);
                return Resp.integer(a.subList(1, a.size()).stream().filter(store::exists).count());
            case "KEYS":
                arity(a, 2, 2);
                return keys(a.get(1));
            case "TYPE":
                arity(a, 2, 2);
                return Resp.simple(typeOf(store.get(a.get(1))));
            case "EXPIRE":
                arity(a, 3, 3);
                return expire(a.get(1), parseLong(a.get(2)) * 1000);
            case "PEXPIRE":
                arity(a, 3, 3);
                return expire(a.get(1), parseLong(a.get(2)));
            case "TTL":
                arity(a, 2, 2);
                return ttl(a.get(1), true);
            case "PTTL":
                arity(a, 2, 2);
                return ttl(a.get(1), false);
            case "PERSIST":
                arity(a, 2, 2);
                return persist(a.get(1));

            // ---- strings ----
            case "SET":
                arity(a, 3, -1);
                return set(a);
            case "GET":
                arity(a, 2, 2);
                return Resp.bulk(getString(a.get(1)));
            case "MSET":
                if (a.size() < 3 || a.size() % 2 == 0) throw RedisError.arity("mset");
                for (int i = 1; i < a.size(); i += 2) {
                    store.put(a.get(i), a.get(i + 1), Store.NO_EXPIRY);
                }
                return Resp.ok();
            case "MGET":
                arity(a, 2, -1);
                return mget(a.subList(1, a.size()));
            case "APPEND": {
                arity(a, 3, 3);
                String current = getString(a.get(1));
                String updated = (current == null ? "" : current) + a.get(2);
                store.update(a.get(1), updated);
                return Resp.integer(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            case "STRLEN": {
                arity(a, 2, 2);
                String s = getString(a.get(1));
                return Resp.integer(s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            case "INCR":
                arity(a, 2, 2);
                return incrBy(a.get(1), 1);
            case "DECR":
                arity(a, 2, 2);
                return incrBy(a.get(1), -1);
            case "INCRBY":
                arity(a, 3, 3);
                return incrBy(a.get(1), parseLong(a.get(2)));
            case "DECRBY":
                arity(a, 3, 3);
                return incrBy(a.get(1), -parseLong(a.get(2)));

            // ---- lists ----
            case "LPUSH":
                arity(a, 3, -1);
                return push(a, true);
            case "RPUSH":
                arity(a, 3, -1);
                return push(a, false);
            case "LPOP":
                arity(a, 2, 2);
                return pop(a.get(1), true);
            case "RPOP":
                arity(a, 2, 2);
                return pop(a.get(1), false);
            case "LLEN": {
                arity(a, 2, 2);
                ArrayDeque<String> list = getList(a.get(1));
                return Resp.integer(list == null ? 0 : list.size());
            }
            case "LRANGE":
                arity(a, 4, 4);
                return lrange(a.get(1), parseLong(a.get(2)), parseLong(a.get(3)));

            // ---- hashes ----
            case "HSET":
                if (a.size() < 4 || a.size() % 2 != 0) throw RedisError.arity("hset");
                return hset(a);
            case "HGET": {
                arity(a, 3, 3);
                Map<String, String> hash = getHash(a.get(1));
                return Resp.bulk(hash == null ? null : hash.get(a.get(2)));
            }
            case "HDEL":
                arity(a, 3, -1);
                return hdel(a);
            case "HEXISTS": {
                arity(a, 3, 3);
                Map<String, String> hash = getHash(a.get(1));
                return Resp.integer(hash != null && hash.containsKey(a.get(2)) ? 1 : 0);
            }
            case "HLEN": {
                arity(a, 2, 2);
                Map<String, String> hash = getHash(a.get(1));
                return Resp.integer(hash == null ? 0 : hash.size());
            }
            case "HGETALL":
                arity(a, 2, 2);
                return hgetall(a.get(1));

            // ---- sets ----
            case "SADD":
                arity(a, 3, -1);
                return sadd(a);
            case "SREM":
                arity(a, 3, -1);
                return srem(a);
            case "SISMEMBER": {
                arity(a, 3, 3);
                Set<String> set = getSet(a.get(1));
                return Resp.integer(set != null && set.contains(a.get(2)) ? 1 : 0);
            }
            case "SCARD": {
                arity(a, 2, 2);
                Set<String> set = getSet(a.get(1));
                return Resp.integer(set == null ? 0 : set.size());
            }
            case "SMEMBERS": {
                arity(a, 2, 2);
                Set<String> set = getSet(a.get(1));
                return Resp.bulkArray(set == null ? List.<String>of() : set);
            }

            default:
                throw new RedisError("ERR unknown command '" + a.get(0) + "'");
        }
    }

    // ------------------------------------------------------------------ strings

    private byte[] set(List<String> a) {
        String key = a.get(1);
        String value = a.get(2);
        long expireAt = Store.NO_EXPIRY;
        boolean nx = false;
        boolean xx = false;

        for (int i = 3; i < a.size(); i++) {
            switch (a.get(i).toUpperCase(Locale.ROOT)) {
                case "EX" -> expireAt = store.now() + positiveTtl(a, ++i) * 1000;
                case "PX" -> expireAt = store.now() + positiveTtl(a, ++i);
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                default -> throw RedisError.syntax();
            }
        }
        if (nx && xx) {
            throw RedisError.syntax();
        }
        boolean exists = store.exists(key);
        if ((nx && exists) || (xx && !exists)) {
            return Resp.nullBulk();
        }
        store.put(key, value, expireAt);
        return Resp.ok();
    }

    private long positiveTtl(List<String> a, int index) {
        if (index >= a.size()) {
            throw RedisError.syntax();
        }
        long amount = parseLong(a.get(index));
        if (amount <= 0) {
            throw new RedisError("ERR invalid expire time in 'set' command");
        }
        return amount;
    }

    private byte[] mget(List<String> keys) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            Object v = store.get(key);
            values.add(v instanceof String s ? s : null); // wrong type => nil, like Redis
        }
        return Resp.bulkArray(values);
    }

    private byte[] incrBy(String key, long delta) {
        String current = getString(key);
        long value = 0;
        if (current != null) {
            try {
                value = Long.parseLong(current);
            } catch (NumberFormatException e) {
                throw RedisError.notInteger();
            }
        }
        try {
            value = Math.addExact(value, delta);
        } catch (ArithmeticException e) {
            throw new RedisError("ERR increment or decrement would overflow");
        }
        store.update(key, Long.toString(value));
        return Resp.integer(value);
    }

    // ------------------------------------------------------------------ keys / expiry

    private byte[] keys(String pattern) {
        Pattern compiled = Glob.compile(pattern);
        List<String> matches = new ArrayList<>();
        for (String key : store.keys()) {
            if (compiled.matcher(key).matches()) {
                matches.add(key);
            }
        }
        return Resp.bulkArray(matches);
    }

    private byte[] expire(String key, long millis) {
        if (!store.exists(key)) {
            return Resp.integer(0);
        }
        if (millis <= 0) {
            store.delete(key);
        } else {
            store.setExpiry(key, store.now() + millis);
        }
        return Resp.integer(1);
    }

    private byte[] ttl(String key, boolean seconds) {
        long expireAt = store.expiryOf(key);
        if (expireAt == -2) {
            return Resp.integer(-2);
        }
        if (expireAt == Store.NO_EXPIRY) {
            return Resp.integer(-1);
        }
        long remaining = Math.max(0, expireAt - store.now());
        return Resp.integer(seconds ? (remaining + 500) / 1000 : remaining);
    }

    private byte[] persist(String key) {
        if (store.expiryOf(key) < 0) { // missing (-2) or already persistent (-1)
            return Resp.integer(0);
        }
        store.setExpiry(key, Store.NO_EXPIRY);
        return Resp.integer(1);
    }

    private static String typeOf(Object value) {
        if (value == null) return "none";
        if (value instanceof String) return "string";
        if (value instanceof ArrayDeque) return "list";
        if (value instanceof Map) return "hash";
        return "set";
    }

    // ------------------------------------------------------------------ lists

    private byte[] push(List<String> a, boolean left) {
        String key = a.get(1);
        ArrayDeque<String> list = getList(key);
        if (list == null) {
            list = new ArrayDeque<>();
            store.put(key, list, Store.NO_EXPIRY);
        }
        for (String value : a.subList(2, a.size())) {
            if (left) list.addFirst(value);
            else list.addLast(value);
        }
        return Resp.integer(list.size());
    }

    private byte[] pop(String key, boolean left) {
        ArrayDeque<String> list = getList(key);
        if (list == null) {
            return Resp.nullBulk();
        }
        String value = left ? list.pollFirst() : list.pollLast();
        if (list.isEmpty()) {
            store.delete(key); // Redis removes empty lists
        }
        return Resp.bulk(value);
    }

    private byte[] lrange(String key, long start, long stop) {
        ArrayDeque<String> list = getList(key);
        if (list == null) {
            return Resp.emptyArray();
        }
        long size = list.size();
        if (start < 0) start += size;
        if (stop < 0) stop += size;
        start = Math.max(start, 0);
        stop = Math.min(stop, size - 1);
        if (start > stop || start >= size) {
            return Resp.emptyArray();
        }
        List<String> out = new ArrayList<>();
        long index = 0;
        for (String item : list) {
            if (index > stop) break;
            if (index >= start) out.add(item);
            index++;
        }
        return Resp.bulkArray(out);
    }

    // ------------------------------------------------------------------ hashes

    private byte[] hset(List<String> a) {
        String key = a.get(1);
        Map<String, String> hash = getHash(key);
        if (hash == null) {
            hash = new LinkedHashMap<>();
            store.put(key, hash, Store.NO_EXPIRY);
        }
        int added = 0;
        for (int i = 2; i < a.size(); i += 2) {
            if (hash.put(a.get(i), a.get(i + 1)) == null) {
                added++;
            }
        }
        return Resp.integer(added);
    }

    private byte[] hdel(List<String> a) {
        Map<String, String> hash = getHash(a.get(1));
        if (hash == null) {
            return Resp.integer(0);
        }
        int removed = 0;
        for (String field : a.subList(2, a.size())) {
            if (hash.remove(field) != null) removed++;
        }
        if (hash.isEmpty()) store.delete(a.get(1));
        return Resp.integer(removed);
    }

    private byte[] hgetall(String key) {
        Map<String, String> hash = getHash(key);
        if (hash == null) {
            return Resp.emptyArray();
        }
        List<String> flat = new ArrayList<>(hash.size() * 2);
        for (Map.Entry<String, String> e : hash.entrySet()) {
            flat.add(e.getKey());
            flat.add(e.getValue());
        }
        return Resp.bulkArray(flat);
    }

    // ------------------------------------------------------------------ sets

    private byte[] sadd(List<String> a) {
        String key = a.get(1);
        Set<String> set = getSet(key);
        if (set == null) {
            set = new LinkedHashSet<>();
            store.put(key, set, Store.NO_EXPIRY);
        }
        int added = 0;
        for (String member : a.subList(2, a.size())) {
            if (set.add(member)) added++;
        }
        return Resp.integer(added);
    }

    private byte[] srem(List<String> a) {
        Set<String> set = getSet(a.get(1));
        if (set == null) {
            return Resp.integer(0);
        }
        int removed = 0;
        for (String member : a.subList(2, a.size())) {
            if (set.remove(member)) removed++;
        }
        if (set.isEmpty()) store.delete(a.get(1));
        return Resp.integer(removed);
    }

    // ------------------------------------------------------------------ typed access

    private String getString(String key) {
        Object v = store.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw RedisError.wrongType();
    }

    private ArrayDeque<String> getList(String key) {
        Object v = store.get(key);
        if (v == null) return null;
        if (v instanceof ArrayDeque<?>) {
            @SuppressWarnings("unchecked")
            ArrayDeque<String> list = (ArrayDeque<String>) v;
            return list;
        }
        throw RedisError.wrongType();
    }

    private Map<String, String> getHash(String key) {
        Object v = store.get(key);
        if (v == null) return null;
        if (v instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, String> hash = (Map<String, String>) v;
            return hash;
        }
        throw RedisError.wrongType();
    }

    private Set<String> getSet(String key) {
        Object v = store.get(key);
        if (v == null) return null;
        if (v instanceof LinkedHashSet<?>) {
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) v;
            return set;
        }
        throw RedisError.wrongType();
    }

    // ------------------------------------------------------------------ helpers

    /** @param max maximum allowed size of {@code a}, or -1 for unlimited */
    private static void arity(List<String> a, int min, int max) {
        if (a.size() < min || (max != -1 && a.size() > max)) {
            throw RedisError.arity(a.get(0));
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw RedisError.notInteger();
        }
    }

    private String info() {
        long uptimeSeconds = (store.now() - startedAtMillis) / 1000;
        return "# Server\r\n"
                + "redis_version:0.1.0-java\r\n"
                + "redis_mode:standalone\r\n"
                + "uptime_in_seconds:" + uptimeSeconds + "\r\n"
                + "\r\n# Keyspace\r\n"
                + "db0:keys=" + store.size() + "\r\n";
    }
}
