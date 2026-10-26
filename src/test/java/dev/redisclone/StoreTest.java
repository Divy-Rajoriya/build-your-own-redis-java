package dev.redisclone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.redisclone.store.Store;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StoreTest {

    @Test
    void expiredKeysAreInvisibleAndPurged() {
        AtomicLong clock = new AtomicLong(0);
        Store store = new Store(clock::get);
        store.put("a", "1", 50);
        store.put("b", "2", Store.NO_EXPIRY);

        clock.set(50);
        assertNull(store.get("a"));
        assertFalse(store.exists("a"));
        assertEquals("2", store.get("b"));
        assertEquals(1, store.size());
    }

    @Test
    void purgeExpiredRemovesOnlyExpiredKeys() {
        AtomicLong clock = new AtomicLong(0);
        Store store = new Store(clock::get);
        store.put("a", "1", 10);
        store.put("b", "2", 20);
        store.put("c", "3", Store.NO_EXPIRY);

        clock.set(15);
        assertEquals(1, store.purgeExpired());
        assertEquals(2, store.keys().size());
    }
}
