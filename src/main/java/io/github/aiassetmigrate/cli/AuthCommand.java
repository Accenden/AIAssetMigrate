package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.SiwcHttpTransport;
import io.github.aiassetmigrate.ai.SiwcInference;
import io.github.aiassetmigrate.auth.AccountSession;
import io.github.aiassetmigrate.auth.AccountStore;
import io.github.aiassetmigrate.auth.OAuthCredentials;
import io.github.aiassetmigrate.auth.OAuthException;
import io.github.aiassetmigrate.auth.OAuthSession;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "auth", mixinStandardHelpOptions = true,
        description = {"Connect, inspect or disconnect the ChatGPT account used for optional AI suggestions.",
                "Connecting never approves a migration plan. There is no API-key or paid fallback."})
public final class AuthCommand implements Callable<Integer> {
    enum Action { connect, status, models, use_model, disconnect }

    @Parameters(index = "0", paramLabel = "ACTION", converter = ActionConverter.class,
            description = "connect | status | models | use-model | disconnect")
    private Action action;
    @Parameters(index = "1", arity = "0..1", paramLabel = "MODEL", description = "Model slug for use-model.")
    private String modelArgument;
    @Option(names = "--model", paramLabel = "SLUG", description = "Model to select after connect (default: first listed).")
    private String model;
    @Spec private CommandSpec spec;

    /** External effects, injectable for tests. */
    interface Environment {
        AccountStore store() throws IOException;
        OAuthCredentials authorize(String hostId, Optional<OAuthSession.Registration> registration)
                throws OAuthException, InterruptedException;
        List<SiwcInference.Model> models(String accessToken) throws SiwcInference.InferenceException;
        boolean revoke(String hostId, OAuthCredentials credentials) throws InterruptedException;
        AccountSession session(AccountStore store);
    }

    static final class ActionConverter implements picocli.CommandLine.ITypeConverter<Action> {
        @Override public Action convert(String value) {
            try { return Action.valueOf(value.replace('-', '_')); }
            catch (IllegalArgumentException e) {
                throw new picocli.CommandLine.TypeConversionException("Expected connect, status, models, use-model or disconnect");
            }
        }
    }

    private final Environment environment;

    public AuthCommand() { this(new DefaultEnvironment()); }
    AuthCommand(Environment environment) { this.environment = environment; }

    @Override public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            AccountStore store = environment.store();
            int status = switch (action) {
                case connect -> connect(store, out);
                case status -> status(store, out);
                case models -> models(store, out);
                case use_model -> useModel(store, out, err);
                case disconnect -> disconnect(store, out);
            };
            out.flush();
            return status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted.");
            return 130;
        } catch (Exception e) {
            err.println("auth " + action.name().replace('_', '-') + " failed: " + ImportCommand.safe(e.getMessage()));
            return 1;
        }
    }

    private int connect(AccountStore store, PrintWriter out) throws Exception {
        Optional<AccountStore.Account> previous = store.load();
        out.println("Opening your browser to sign in with ChatGPT (waits up to 5 minutes)...");
        out.flush();
        OAuthCredentials credentials = environment.authorize(store.hostId(), previous.map(AccountStore.Account::registration));
        List<SiwcInference.Model> models = environment.models(credentials.accessToken());
        Optional<String> selected;
        if (model != null) {
            if (models.stream().noneMatch(m -> m.slug().equals(model))) {
                throw new IOException("Model '" + model + "' is not available to this account; run 'auth models'");
            }
            selected = Optional.of(model);
        } else {
            selected = previous.flatMap(AccountStore.Account::model).filter(slug -> models.stream().anyMatch(m -> m.slug().equals(slug)))
                    .or(() -> models.stream().findFirst().map(SiwcInference.Model::slug));
        }
        store.save(new AccountStore.Account(credentials, selected));
        out.println("Connected as " + ImportCommand.safe(credentials.email().orElse(credentials.subject())) + ".");
        out.println("Credentials saved with " + store.protection() + " in " + store.directory());
        out.println("Model: " + selected.map(ImportCommand::safe).orElse("<none available>"));
        out.println("Use 'analyze KIND --ai' to request AI suggestions. Migration approval is unchanged.");
        return 0;
    }

    private int status(AccountStore store, PrintWriter out) throws IOException {
        Optional<AccountStore.Account> account = store.load();
        if (account.isEmpty()) {
            out.println("Not connected. Run 'auth connect' to enable optional AI suggestions.");
            return 0;
        }
        OAuthCredentials c = account.get().credentials();
        out.println("Connected as " + ImportCommand.safe(c.email().orElse(c.subject())));
        out.println("Model: " + account.get().model().map(ImportCommand::safe).orElse("<none selected>"));
        out.println("Access token expires: " + c.expiresAt() + " (refreshed automatically)");
        out.println("Storage: " + store.protection() + " in " + store.directory());
        return 0;
    }

    private int models(AccountStore store, PrintWriter out) throws Exception {
        AccountStore.Account account = store.load().orElseThrow(AccountSession.NotConnectedException::new);
        List<SiwcInference.Model> models = environment.models(environment.session(store).accessToken(false));
        out.println("Models available to this account (" + models.size() + "):");
        for (var entry : models) {
            boolean current = account.model().map(entry.slug()::equals).orElse(false);
            out.printf("  %s %s%s%n", ImportCommand.safe(entry.slug()), ImportCommand.safe(entry.displayName()),
                    current ? " (selected)" : "");
        }
        return 0;
    }

    private int useModel(AccountStore store, PrintWriter out, PrintWriter err) throws Exception {
        if (modelArgument == null || modelArgument.isBlank()) {
            err.println("Usage: auth use-model MODEL (see 'auth models')");
            return 2;
        }
        AccountSession session = environment.session(store);
        List<SiwcInference.Model> models = environment.models(session.accessToken(false));
        if (models.stream().noneMatch(m -> m.slug().equals(modelArgument))) {
            err.println("Model '" + ImportCommand.safe(modelArgument) + "' is not available to this account; run 'auth models'.");
            return 1;
        }
        AccountStore.Account current = session.account();
        store.save(new AccountStore.Account(current.credentials(), Optional.of(modelArgument)));
        out.println("Model selected: " + ImportCommand.safe(modelArgument));
        return 0;
    }

    private int disconnect(AccountStore store, PrintWriter out) throws Exception {
        Optional<AccountStore.Account> account = store.load();
        if (account.isEmpty()) {
            out.println("Not connected.");
            return 0;
        }
        boolean revoked = environment.revoke(store.hostId(), account.get().credentials());
        store.clear();
        out.println("Local credentials removed.");
        out.println(revoked ? "Remote session revoked."
                : "Remote revocation was not confirmed; you can also disconnect the app in ChatGPT Settings.");
        return 0;
    }

    private static final class DefaultEnvironment implements Environment {
        @Override public AccountStore store() throws IOException { return AccountStore.forCurrentUser(); }
        @Override public OAuthCredentials authorize(String hostId, Optional<OAuthSession.Registration> registration)
                throws OAuthException, InterruptedException {
            return new OAuthSession(hostId, registration).authorize();
        }
        @Override public List<SiwcInference.Model> models(String accessToken) throws SiwcInference.InferenceException {
            return SiwcInference.listModels(new SiwcHttpTransport(), accessToken, Duration.ofSeconds(30));
        }
        @Override public boolean revoke(String hostId, OAuthCredentials credentials) throws InterruptedException {
            return new OAuthSession(hostId, Optional.of(new OAuthSession.Registration(credentials.clientId(),
                    credentials.subject()))).revoke(credentials);
        }
        @Override public AccountSession session(AccountStore store) { return AccountSession.forStore(store); }
    }
}
