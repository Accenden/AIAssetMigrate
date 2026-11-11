package io.github.aiassetmigrate.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AccountStoreTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    /** Reversible test protector so tests can prove tokens are not stored in plain text. */
    static final AccountStore.Protector XOR = new AccountStore.Protector() {
        @Override public byte[] protect(byte[] plain) { return flip(plain); }
        @Override public byte[] unprotect(byte[] stored) { return flip(stored); }
        @Override public String description() { return "test protector"; }
        private byte[] flip(byte[] bytes) {
            byte[] out = bytes.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= 0x5a;
            return out;
        }
    };

    static OAuthCredentials credentials(String access, String refresh, Instant expires) {
        return new OAuthCredentials("oaiapp_client", "subject-1", Optional.of("dev@example.com"), access, refresh,
                "id-token", Set.of("openid", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"), expires);
    }

    @Test void savesLoadsAndClearsProtectedCredentialsWithAStableHostId() throws Exception {
        var store = new AccountStore(directory.resolve("account"), XOR);
        assertEquals(Optional.empty(), store.load());
        String host = store.hostId();
        assertTrue(host.startsWith("urn:uuid:"));
        assertEquals(host, store.hostId());
        var account = new AccountStore.Account(credentials("access-secret", "refresh-secret", NOW), Optional.of("gpt-test"));
        store.save(account);
        String raw = new String(Files.readAllBytes(directory.resolve("account/credentials.dat")), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("access-secret") || raw.contains("refresh-secret"), "tokens are not stored in plain text");
        var loaded = store.load().orElseThrow();
        assertEquals(account.credentials(), loaded.credentials());
        assertEquals(Optional.of("gpt-test"), loaded.model());
        assertFalse(loaded.credentials().toString().contains("secret"));
        store.clear();
        assertEquals(Optional.empty(), store.load());
        assertEquals(host, store.hostId(), "the host ID survives disconnect");
    }

    @Test void corruptOrForeignCredentialsFailClosed() throws Exception {
        var store = new AccountStore(directory, XOR);
        store.save(new AccountStore.Account(credentials("a", "r", NOW), Optional.empty()));
        Files.write(directory.resolve("credentials.dat"), "{\"version\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, store::load);
        Files.writeString(directory.resolve("host-id"), "not-a-host");
        assertThrows(IOException.class, store::hostId);
    }

    @Test void sessionRefreshesNearExpiryAndPersistsTheRotatedToken() throws Exception {
        var store = new AccountStore(directory, XOR);
        store.save(new AccountStore.Account(credentials("old-access", "old-refresh", NOW.plusSeconds(60)), Optional.of("m")));
        AtomicInteger refreshes = new AtomicInteger();
        var session = new AccountSession(store, current -> {
            refreshes.incrementAndGet();
            assertEquals("old-refresh", current.refreshToken());
            return credentials("new-access", "new-refresh", NOW.plusSeconds(3600));
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("new-access", session.accessToken(false));
        assertEquals("new-refresh", store.load().orElseThrow().credentials().refreshToken());
        assertEquals(Optional.of("m"), store.load().orElseThrow().model());
        assertEquals("new-access", session.accessToken(false), "a fresh token is reused");
        assertEquals(1, refreshes.get());
        var forced = new AccountSession(store, current -> credentials("forced", current.refreshToken(), NOW.plusSeconds(3600)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("forced", forced.accessToken(true));
        store.clear();
        assertThrows(AccountSession.NotConnectedException.class, () -> session.accessToken(false));
    }

    @Test void windowsDpapiRoundTripsForTheCurrentUser() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"), "Windows only");
        var dpapi = new AccountStore.WindowsDpapi();
        byte[] plain = "token-value".getBytes(StandardCharsets.UTF_8);
        byte[] stored = dpapi.protect(plain);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("token-value"));
        assertArrayEquals(plain, dpapi.unprotect(stored));
        assertThrows(IOException.class, () -> dpapi.unprotect("tampered".getBytes(StandardCharsets.UTF_8)));
    }
}
