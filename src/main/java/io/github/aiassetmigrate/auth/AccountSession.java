package io.github.aiassetmigrate.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies a current access token for the saved AI account. Tokens close to expiry are
 * refreshed under the store's cross-process lock and the rotated credentials are saved
 * before the new token is returned. Connecting an account never grants migration approval.
 */
public final class AccountSession {
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    @FunctionalInterface
    public interface Refresher {
        OAuthCredentials refresh(OAuthCredentials current) throws OAuthException, IOException, InterruptedException;
    }

    /** Thrown when no account is connected; the message tells the user how to connect. */
    public static final class NotConnectedException extends IOException {
        public NotConnectedException() { super("No AI account is connected; run 'auth connect' or use Connect AI Account"); }
    }

    private final AccountStore store;
    private final Refresher refresher;
    private final Clock clock;

    public AccountSession(AccountStore store, Refresher refresher, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Refreshes through {@link OAuthSession} using the stored host ID and registration. */
    public static AccountSession forStore(AccountStore store) {
        return new AccountSession(store, current -> new OAuthSession(store.hostId(),
                Optional.of(new OAuthSession.Registration(current.clientId(), current.subject()))).refresh(current),
                Clock.systemUTC());
    }

    public AccountStore.Account account() throws IOException {
        return store.load().orElseThrow(NotConnectedException::new);
    }

    /** Returns a token valid for at least the refresh margin; {@code force} refreshes regardless. */
    public String accessToken(boolean force) throws IOException, OAuthException, InterruptedException {
        try {
            return store.locked(() -> {
                AccountStore.Account account = account();
                OAuthCredentials credentials = account.credentials();
                if (force || !credentials.expiresAt().minus(REFRESH_MARGIN).isAfter(clock.instant())) {
                    credentials = refresher.refresh(credentials);
                    store.save(new AccountStore.Account(credentials, account.model()));
                }
                return credentials.accessToken();
            });
        } catch (IOException | OAuthException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AI account session failed", e);
        }
    }
}
