package io.github.aiassetmigrate.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Per-user AI account storage, kept outside project state and source control.
 * Tokens are written only through a {@link Protector}: Windows DPAPI (current user) or, on
 * POSIX systems, an owner-only file. Non-secret account details are stored separately.
 * Never log or display the stored token fields.
 */
public final class AccountStore {
    private static final int MAX_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /** Encrypts or otherwise protects credential bytes at rest. */
    public interface Protector {
        byte[] protect(byte[] plain) throws IOException;
        byte[] unprotect(byte[] stored) throws IOException;
        /** Human-readable description shown by account status, for example "Windows DPAPI". */
        String description();
    }

    /** A saved connection. The model slug is the user's explicit selection, if any. */
    public record Account(OAuthCredentials credentials, Optional<String> model) {
        public Account {
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(model, "model");
        }
        public OAuthSession.Registration registration() {
            return new OAuthSession.Registration(credentials.clientId(), credentials.subject());
        }
    }

    private final Path directory;
    private final Protector protector;

    public AccountStore(Path directory, Protector protector) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    /** Uses the per-user configuration directory and the platform's protector. */
    public static AccountStore forCurrentUser() throws IOException {
        return new AccountStore(defaultDirectory(), defaultProtector());
    }

    public static Path defaultDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String appData = System.getenv("APPDATA");
        if (os.contains("windows") && appData != null && !appData.isBlank()) return Path.of(appData, "AIAssetMigrate");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "aiassetmigrate");
        return Path.of(System.getProperty("user.home"), ".config", "aiassetmigrate");
    }

    public static Protector defaultProtector() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return new WindowsDpapi();
        if (Path.of(System.getProperty("user.home")).getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new OwnerOnlyFile();
        }
        throw new IOException("No protected credential storage is available on this platform");
    }

    public Path directory() { return directory; }
    public String protection() { return protector.description(); }

    /** Returns the stable host ID for this installation, creating it once. */
    public synchronized String hostId() throws IOException {
        Path file = directory.resolve("host-id");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
            try {
                UUID id = UUID.fromString(value.substring("urn:uuid:".length()));
                if (value.equals("urn:uuid:" + id) && id.version() == 4) return value;
            } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                // Fall through to the explicit error below.
            }
            throw new IOException("Stored host ID is invalid: " + file);
        }
        String value = "urn:uuid:" + UUID.randomUUID();
        writeAtomically(file, value.getBytes(StandardCharsets.US_ASCII));
        return value;
    }

    public synchronized Optional<Account> load() throws IOException {
        Path file = directory.resolve("credentials.dat");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw new IOException("Stored credentials are not a regular bounded file");
        }
        JsonNode json;
        try {
            json = JSON.readTree(protector.unprotect(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new IOException("Stored AI credentials cannot be read; disconnect and sign in again", e);
        }
        if (json == null || json.path("version").asInt() != 1) throw new IOException("Unsupported stored credentials");
        Set<String> scopes = new HashSet<>();
        json.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        try {
            var credentials = new OAuthCredentials(text(json, "clientId"), text(json, "subject"),
                    json.hasNonNull("email") ? Optional.of(json.get("email").asText()) : Optional.empty(),
                    text(json, "accessToken"), text(json, "refreshToken"), text(json, "idToken"), scopes,
                    Instant.parse(text(json, "expiresAt")));
            return Optional.of(new Account(credentials,
                    json.hasNonNull("model") ? Optional.of(json.get("model").asText()) : Optional.empty()));
        } catch (RuntimeException e) {
            throw new IOException("Stored AI credentials are invalid; disconnect and sign in again", e);
        }
    }

    public synchronized void save(Account account) throws IOException {
        OAuthCredentials c = account.credentials();
        ObjectNode json = JSON.createObjectNode();
        json.put("version", 1);
        json.put("clientId", c.clientId());
        json.put("subject", c.subject());
        c.email().ifPresent(email -> json.put("email", email));
        json.put("accessToken", c.accessToken());
        json.put("refreshToken", c.refreshToken());
        json.put("idToken", c.idToken());
        c.scopes().stream().sorted().forEach(json.putArray("scopes")::add);
        json.put("expiresAt", c.expiresAt().toString());
        account.model().ifPresent(model -> json.put("model", model));
        writeAtomically(directory.resolve("credentials.dat"), protector.protect(JSON.writeValueAsBytes(json)));
    }

    /** Removes saved credentials; the host ID is kept so a later sign-in is recognized. */
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(directory.resolve("credentials.dat"));
    }

    /**
     * Runs an action while holding an exclusive cross-process lock, so rotating refresh
     * tokens are never used by two processes at once.
     */
    public <T> T locked(LockedAction<T> action) throws Exception {
        ensureDirectory();
        try (FileChannel channel = FileChannel.open(directory.resolve("credentials.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    @FunctionalInterface
    public interface LockedAction<T> { T run() throws Exception; }

    private static String text(JsonNode json, String field) throws IOException {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IOException("Missing " + field);
        return value.textValue();
    }

    private void ensureDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Account directory must not be a link: " + directory);
        Files.createDirectories(directory);
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        ensureDirectory();
        Path temp = directory.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(temp, PosixFilePermissions.asFileAttribute(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            }
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(bytes);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Windows DPAPI with current-user scope, via the in-box Windows PowerShell. */
    static final class WindowsDpapi implements Protector {
        private static final String SCRIPT = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;"
                + "$d=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim());"
                + "$e=[Text.Encoding]::UTF8.GetBytes('AIAssetMigrate-credentials-v1');"
                + "$s=[Security.Cryptography.DataProtectionScope]::CurrentUser;"
                + "$r=[Security.Cryptography.ProtectedData]::%s($d,$e,$s);"
                + "[Console]::Out.Write([Convert]::ToBase64String($r))";

        @Override public byte[] protect(byte[] plain) throws IOException { return run("Protect", plain); }
        @Override public byte[] unprotect(byte[] stored) throws IOException { return run("Unprotect", stored); }
        @Override public String description() { return "Windows DPAPI (current user)"; }

        private static byte[] run(String operation, byte[] input) throws IOException {
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            Process process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", String.format(SCRIPT, operation))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(Base64.getEncoder().encode(input));
            }
            byte[] output;
            try (InputStream stdout = process.getInputStream()) {
                output = stdout.readNBytes(MAX_BYTES * 2);
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Windows DPAPI timed out");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Windows DPAPI interrupted", e);
            }
            if (process.exitValue() != 0) throw new IOException("Windows DPAPI " + operation + " failed");
            try {
                return Base64.getDecoder().decode(new String(output, StandardCharsets.US_ASCII).strip());
            } catch (IllegalArgumentException e) {
                throw new IOException("Windows DPAPI returned invalid output", e);
            }
        }
    }

    /** Owner-only (0600) file in an owner-only (0700) directory; contents are not encrypted. */
    static final class OwnerOnlyFile implements Protector {
        @Override public byte[] protect(byte[] plain) { return plain.clone(); }
        @Override public byte[] unprotect(byte[] stored) { return stored.clone(); }
        @Override public String description() { return "owner-only file permissions"; }
    }
}
