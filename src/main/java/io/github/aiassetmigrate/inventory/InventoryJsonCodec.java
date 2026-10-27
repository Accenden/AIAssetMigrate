package io.github.aiassetmigrate.inventory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.aiassetmigrate.domain.InventorySnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/** In-memory JSON representation of an inventory, independent of host path separators. */
public final class InventoryJsonCodec {
    private final ObjectMapper mapper;

    public InventoryJsonCodec() {
        SimpleModule paths = new SimpleModule();
        paths.addSerializer(Path.class, new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                String text = value.toString().replace(value.getFileSystem().getSeparator(), "/");
                if (value.isAbsolute()) {
                    // Construct the URI directly: Path.toUri() can inspect whether a directory exists.
                    String authority = "";
                    if (text.startsWith("//")) {
                        int separator = text.indexOf('/', 2);
                        authority = text.substring(2, separator);
                        text = text.substring(separator);
                    } else if (!text.startsWith("/")) {
                        text = "/" + text;
                    }
                    try {
                        text = new URI("file", authority, text, null, null).toASCIIString();
                    } catch (URISyntaxException exception) {
                        throw new IOException("Cannot encode absolute path", exception);
                    }
                } else {
                    validateRelativePath(text);
                }
                generator.writeString(text);
            }
        });
        paths.addDeserializer(Path.class, new JsonDeserializer<Path>() {
            @Override
            public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                    throw new IOException("Path must be a JSON string");
                }
                String text = parser.getText();
                try {
                    if (text.startsWith("file:")) {
                        URI uri = URI.create(text);
                        if (uri.isOpaque() || uri.getQuery() != null || uri.getFragment() != null) {
                            throw new IllegalArgumentException("Expected an absolute file URI");
                        }
                        Path path = Path.of(uri);
                        if (!path.isAbsolute()) {
                            throw new IllegalArgumentException("Expected an absolute file URI");
                        }
                        return path;
                    }
                    validateRelativePath(text);
                    return Path.of(text);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid portable path: " + text, exception);
                }
            }
        });
        mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(paths)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public byte[] encode(InventorySnapshot snapshot) throws IOException {
        return mapper.writeValueAsBytes(Objects.requireNonNull(snapshot, "snapshot"));
    }

    public InventorySnapshot decode(byte[] json) throws IOException {
        JsonNode tree = mapper.readTree(Objects.requireNonNull(json, "json"));
        JsonNode version = tree == null ? null : tree.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != InventorySnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported or missing inventory schemaVersion");
        }
        try (JsonParser parser = tree.traverse(mapper)) {
            return mapper.readValue(parser, InventorySnapshot.class);
        }
    }

    private static void validateRelativePath(String text) throws IOException {
        // Empty is the inventory root; domain constructors restrict it to allowed fields.
        if (text.isEmpty()) {
            return;
        }
        if (text.indexOf('\\') >= 0 || text.indexOf(':') >= 0 || text.indexOf('\0') >= 0) {
            throw new IOException("Invalid portable relative path: " + text);
        }
        for (String component : text.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Noncanonical portable relative path: " + text);
            }
        }
    }
}
