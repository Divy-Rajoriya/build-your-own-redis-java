package dev.redisclone.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The in-memory keyspace.
 *
 * <p>Values are plain Java objects: {@code String}, {@code ArrayDeque<String>} (list),
 * {@code LinkedHashMap<String,String>} (hash) or {@code LinkedHashSet<String>} (set).
 *
 * <p>This class is NOT thread-safe on its own. Like real Redis, commands are executed one
 * at a time: callers must {@code synchronized (store)} around every access.
 *
 * <p>Expiry is both lazy (checked on every access) and active (see {@link #purgeExpired()},
 * which a background thread calls periodically).
 */
public final class Store {

    public static final long NO_EXPIRY = -1;

    private static final class Entry {
        Object value;
        long expireAt; // epoch millis, or NO_EXPIRY

        Entry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    private final Map<String, Entry> data = new HashMap<>();
    private final LongSupplier clock;

    public Store() {
        this(System::currentTimeMillis);
    }

    /** Constructor with an injectable clock so tests can fast-forward time. */
    public Store(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Returns the value stored at key, or {@code null} if missing/expired. */
    public Object get(String key) {
        Entry entry = live(key);
        return entry == null ? null : entry.value;
    }

    public boolean exists(String key) {
        return live(key) != null;
    }

    /** Sets key to value with the given absolute expiry (or {@link #NO_EXPIRY}). */
    public void put(String key, Object value, long expireAt) {
        data.put(key, new Entry(value, expireAt));
    }

    /** Replaces the value but keeps the key's existing TTL (used by INCR, APPEND, ...). */
    public void update(String key, Object value) {
        Entry entry = live(key);
        if (entry == null) {
            data.put(key, new Entry(value, NO_EXPIRY));
        } else {
            entry.value = value;
        }
    }

    public boolean delete(String key) {
        return live(key) != null && data.remove(key) != null;
    }

    /** Absolute expiry time in epoch millis, {@link #NO_EXPIRY}, or -2 if the key is missing. */
    public long expiryOf(String key) {
        Entry entry = live(key);
        return entry == null ? -2 : entry.expireAt;
    }

    /** Sets the absolute expiry. Returns false if the key does not exist. */
    public boolean setExpiry(String key, long expireAt) {
        Entry entry = live(key);
        if (entry == null) {
            return false;
        }
        entry.expireAt = expireAt;
        return true;
    }

    public List<String> keys() {
        purgeExpired();
        return new ArrayList<>(data.keySet());
    }

    public int size() {
        purgeExpired();
        return data.size();
    }

    public void clear() {
        data.clear();
    }

    /** Removes every expired key. Returns how many were removed. */
    public int purgeExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Iterator<Map.Entry<String, Entry>> it = data.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            if (entry.expireAt != NO_EXPIRY && entry.expireAt <= now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private Entry live(String key) {
        Entry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt != NO_EXPIRY && entry.expireAt <= clock.getAsLong()) {
            data.remove(key);
            return null;
        }
        return entry;
    }
}
