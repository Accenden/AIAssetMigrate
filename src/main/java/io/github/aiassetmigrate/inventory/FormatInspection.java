package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded evidence only: none of these checks validates complete asset contents. */
final class FormatInspection {
    private static final int MAX_FBX_PREFIX = 64 * 1024;
    private static final byte[] FBX_BINARY = "Kaydara FBX Binary  \0\u001a\0".getBytes(StandardCharsets.US_ASCII);
    private final String extension;
    private final long size;
    private final byte[] fbxPrefix;
    private final TextInspection text;
    private int prefixLength;

    FormatInspection(String extension, long size, ScanLimits limits) {
        this.extension = extension;
        this.size = size;
        this.fbxPrefix = extension.equals("fbx") ? new byte[(int) Math.min(size, MAX_FBX_PREFIX)] : null;
        this.text = extension.equals("obj") || extension.equals("mtl") ? new TextInspection(extension, limits) : null;
    }

    void accept(byte[] bytes, int count) throws InterruptedIOException {
        if (fbxPrefix != null) {
            int copied = Math.min(count, fbxPrefix.length - prefixLength);
            System.arraycopy(bytes, 0, fbxPrefix, prefixLength, copied);
            prefixLength += copied;
        }
        if (text != null) text.accept(bytes, count);
    }

    Result finish() throws InterruptedIOException {
        return switch (extension) {
            case "fbx" -> fbx();
            case "obj", "mtl" -> text.finish(size);
            default -> throw new IllegalStateException("Unexpected inspected extension: " + extension);
        };
    }

    private Result fbx() {
        if (startsWith(FBX_BINARY)) {
            if (size < FBX_BINARY.length + Integer.BYTES) {
                return unknown("FBX_TRUNCATED_HEADER", "Binary FBX header does not contain a complete version field");
            }
            long version = unsignedLittleEndian(FBX_BINARY.length);
            if (version == 0) return unknown("FBX_UNKNOWN_HEADER", "Binary FBX header has an invalid version field");
            return checked(Asset.InspectionStatus.HEADER_ONLY, "FBX_BINARY_HEADER",
                    "Binary FBX header and version " + version + " checked; document is otherwise unverified");
        }
        if (isBinaryPrefix()) {
            return unknown("FBX_TRUNCATED_HEADER", "Binary FBX header is truncated");
        }
        String ascii = new String(fbxPrefix, 0, prefixLength, StandardCharsets.US_ASCII);
        if (ascii.matches("(?s)^\\uFEFF?[ \\t\\r\\n]*;[ \\t]*FBX[ \\t]+[0-9]+(?:\\.[0-9]+){1,3}[ \\t]+project[ \\t]+file(?:[ \\t\\r\\n].*)?$")) {
            return checked(Asset.InspectionStatus.HEADER_ONLY, "FBX_ASCII_HEADER",
                    "ASCII FBX header checked; document is otherwise unverified");
        }
        return unknown("FBX_UNKNOWN_HEADER", "FBX format could not be recognized by bounded header inspection");
    }

    private boolean isBinaryPrefix() {
        if (prefixLength >= FBX_BINARY.length) return false;
        for (int i = 0; i < prefixLength; i++) if (fbxPrefix[i] != FBX_BINARY[i]) return false;
        return prefixLength > 0;
    }

    private boolean startsWith(byte[] signature) {
        if (prefixLength < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (fbxPrefix[i] != signature[i]) return false;
        return true;
    }

    private long unsignedLittleEndian(int offset) {
        return (fbxPrefix[offset] & 255L) | ((fbxPrefix[offset + 1] & 255L) << 8)
                | ((fbxPrefix[offset + 2] & 255L) << 16) | ((fbxPrefix[offset + 3] & 255L) << 24);
    }

    private static Result checked(Asset.InspectionStatus status, String method, String message) {
        return new Result(false, status, method, message, List.of(), List.of());
    }

    private static Result unknown(String method, String message) {
        return new Result(false, Asset.InspectionStatus.UNKNOWN, method, message, List.of(), List.of());
    }

    record ExtractedReference(AssetReference.Kind kind, String originalText, boolean unresolved) {
        ExtractedReference {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(originalText, "originalText");
        }
    }

    record Limitation(String code, String message) {
        Limitation {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    record Result(boolean corrupt, Asset.InspectionStatus status, String method, String message,
                  List<ExtractedReference> references, List<Limitation> limitations) {
        Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(message, "message");
            references = List.copyOf(references);
            limitations = List.copyOf(limitations);
        }
    }

    /** Incremental UTF-8 text inspection keeps retained input to a line and references only. */
    private static final class TextInspection {
        private final String extension;
        private final ScanLimits limits;
        private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        private final StringBuilder line = new StringBuilder();
        private final List<ExtractedReference> references = new ArrayList<>();
        private final List<Limitation> limitations = new ArrayList<>();
        private byte[] remainder = new byte[0];
        private long parsedBytes;
        private int lineBytes;
        private boolean firstCharacter = true;
        private boolean invalidUtf8;
        private boolean malformed;
        private String malformedMessage;
        private boolean textLimit;
        private boolean lineLimit;
        private boolean referenceLimit;
        private boolean syntaxLimitation;
        private boolean relevantSyntax;
        private boolean previousCarriageReturn;

        TextInspection(String extension, ScanLimits limits) {
            this.extension = extension;
            this.limits = limits;
        }

        void accept(byte[] bytes, int count) throws InterruptedIOException {
            checkInterrupted();
            long remaining = limits.maxParsedTextBytes() - parsedBytes;
            if (remaining <= 0) {
                textLimit = true;
                return;
            }
            int accepted = (int) Math.min((long) count, remaining);
            if (accepted < count) textLimit = true;
            parsedBytes += accepted;
            trackLineBytes(bytes, accepted);
            if (invalidUtf8 || lineLimit) return;
            decode(bytes, accepted);
        }

        Result finish(long fileSize) throws InterruptedIOException {
            checkInterrupted();
            if (fileSize > parsedBytes) textLimit = true;
            if (!invalidUtf8 && !lineLimit && !textLimit) finishDecoding();
            if (!invalidUtf8 && !lineLimit && !textLimit && !malformed) parseLine();
            if (invalidUtf8) {
                limitations.add(new Limitation("REFERENCE_SYNTAX_LIMITATION",
                        "Text is not valid UTF-8; references were not interpreted"));
                List<ExtractedReference> unresolved = references.stream().map(reference ->
                        new ExtractedReference(reference.kind(), reference.originalText(), true)).toList();
                return new Result(false, Asset.InspectionStatus.UNVERIFIED, "TEXT_ENCODING_LIMITATION",
                        "Text is not valid UTF-8; document is unverified", unresolved, limitations);
            }
            if (malformed) return corrupt(malformedMessage);
            if (lineLimit) return limited("TEXT_LINE_LIMIT", "Text line exceeds inspection budget; document is unverified");
            if (textLimit) return limited("TEXT_PREFIX_LIMIT", "Text inspection budget reached; document is unverified");
            if (referenceLimit) return limited("REFERENCE_LIMIT", "Reference inspection limit reached; document is unverified");
            if (syntaxLimitation) return limited("REFERENCE_SYNTAX_LIMITATION",
                    "Reference syntax is unsupported or ambiguous; document is unverified");
            if (relevantSyntax) {
                return new Result(false, Asset.InspectionStatus.BASIC_CHECKED,
                        extension.equals("obj") ? "OBJ_BASIC_SYNTAX" : "MTL_BASIC_SYNTAX",
                        "Basic " + extension.toUpperCase(Locale.ROOT) + " syntax checked; document is otherwise unverified",
                        references, limitations);
            }
            return new Result(false, Asset.InspectionStatus.UNVERIFIED, "TEXT_PREFIX",
                    "Text sampled without recognized basic format syntax", references, limitations);
        }

        private Result corrupt(String message) {
            return new Result(true, Asset.InspectionStatus.UNKNOWN, "", message, List.of(), List.of());
        }

        private Result limited(String method, String message) {
            return new Result(false, Asset.InspectionStatus.UNVERIFIED, method, message, references, limitations);
        }

        private void trackLineBytes(byte[] bytes, int count) {
            for (int i = 0; i < count; i++) {
                int value = bytes[i] & 255;
                if (value == '\n' || value == '\r') lineBytes = 0;
                else if (++lineBytes > limits.maxTextLineBytes()) lineLimit = true;
            }
        }

        private void decode(byte[] bytes, int count) throws InterruptedIOException {
            byte[] combined = new byte[remainder.length + count];
            System.arraycopy(remainder, 0, combined, 0, remainder.length);
            System.arraycopy(bytes, 0, combined, remainder.length, count);
            ByteBuffer input = ByteBuffer.wrap(combined);
            while (input.hasRemaining()) {
                checkInterrupted();
                CharBuffer output = CharBuffer.allocate(Math.max(8, input.remaining()));
                CoderResult result = decoder.decode(input, output, false);
                output.flip();
                consume(output);
                if (result.isError()) {
                    invalidUtf8 = true;
                    return;
                }
                if (result.isUnderflow()) break;
            }
            remainder = new byte[input.remaining()];
            input.get(remainder);
        }

        private void finishDecoding() throws InterruptedIOException {
            ByteBuffer input = ByteBuffer.wrap(remainder);
            CharBuffer output = CharBuffer.allocate(Math.max(8, remainder.length));
            CoderResult result = decoder.decode(input, output, true);
            output.flip();
            consume(output);
            if (result.isError() || input.hasRemaining()) {
                invalidUtf8 = true;
                return;
            }
            output.clear();
            result = decoder.flush(output);
            output.flip();
            consume(output);
            if (result.isError()) invalidUtf8 = true;
            remainder = new byte[0];
        }

        private void consume(CharBuffer characters) throws InterruptedIOException {
            while (characters.hasRemaining()) {
                checkInterrupted();
                char character = characters.get();
                if (firstCharacter) {
                    firstCharacter = false;
                    if (character == '\uFEFF') continue;
                }
                if (character == '\r' || character == '\n') {
                    if (!(character == '\n' && previousCarriageReturn)) parseLine();
                    previousCarriageReturn = character == '\r';
                } else {
                    previousCarriageReturn = false;
                    line.append(character);
                }
            }
        }

        private void parseLine() throws InterruptedIOException {
            if (line.isEmpty()) return;
            String value = line.toString();
            line.setLength(0);
            int comment = value.indexOf('#');
            String clean = (comment < 0 ? value : value.substring(0, comment)).trim();
            if (clean.isEmpty()) return;
            int split = 0;
            while (split < clean.length() && !Character.isWhitespace(clean.charAt(split))) split++;
            String directive = clean.substring(0, split);
            String arguments = clean.substring(split).trim();
            if (extension.equals("obj")) parseObj(directive, arguments);
            else parseMtl(directive, arguments);
        }

        private void parseObj(String directive, String arguments) throws InterruptedIOException {
            switch (directive) {
                case "v" -> {
                    relevantSyntax = true;
                    String[] fields = tokens(arguments);
                    if ((fields.length != 3 && fields.length != 4) || !allFinite(fields)) {
                        malformed("OBJ vertex must contain three or four finite numeric values");
                    }
                }
                case "f" -> {
                    relevantSyntax = true;
                    String[] fields = tokens(arguments);
                    if (fields.length < 3 || !allFaceVertices(fields)) {
                        malformed("OBJ face must contain at least three valid vertex references");
                    }
                }
                case "mtllib" -> {
                    relevantSyntax = true;
                    if (arguments.isEmpty()) {
                        malformed("OBJ material-library directive requires a filename");
                        return;
                    }
                    if (arguments.indexOf('"') >= 0 || arguments.indexOf('\'') >= 0) {
                        unresolved(AssetReference.Kind.MATERIAL_LIBRARY, arguments,
                                "Quoted OBJ material-library names are not interpreted");
                        return;
                    }
                    for (String name : tokens(arguments)) {
                        checkInterrupted();
                        addReference(AssetReference.Kind.MATERIAL_LIBRARY, name);
                    }
                }
                default -> { /* The bounded check intentionally ignores unsupported OBJ directives. */ }
            }
        }

        private void parseMtl(String directive, String arguments) throws InterruptedIOException {
            if (directive.equals("newmtl")) {
                relevantSyntax = true;
                if (arguments.isEmpty()) malformed("MTL material declaration requires a name");
                return;
            }
            if (!isTextureDirective(directive)) return;
            relevantSyntax = true;
            String filename = textureFilename(arguments);
            if (filename != null) addReference(AssetReference.Kind.TEXTURE, filename);
        }

        private String textureFilename(String arguments) {
            if (arguments.isEmpty()) {
                unresolved(AssetReference.Kind.TEXTURE, "", "MTL texture directive requires a filename");
                return null;
            }
            int index = 0;
            while (index < arguments.length() && arguments.charAt(index) == '-') {
                int optionEnd = nextWhitespace(arguments, index);
                String option = arguments.substring(index, optionEnd);
                index = skipWhitespace(arguments, optionEnd);
                OptionArity arity = optionArity(option);
                if (arity == null) {
                    unresolved(AssetReference.Kind.TEXTURE, arguments,
                            "Unsupported or ambiguous MTL texture option: " + option);
                    return null;
                }
                int consumed = consumeOption(arguments, index, arity);
                if (consumed < 0) {
                    unresolved(AssetReference.Kind.TEXTURE, arguments,
                            "MTL texture option has an invalid or incomplete argument list: " + option);
                    return null;
                }
                index = skipWhitespace(arguments, consumed);
            }
            String filename = arguments.substring(index).trim();
            if (filename.isEmpty()) {
                unresolved(AssetReference.Kind.TEXTURE, arguments, "MTL texture directive requires a filename");
                return null;
            }
            if (filename.startsWith("-")) {
                unresolved(AssetReference.Kind.TEXTURE, arguments, "MTL texture directive has ambiguous option syntax");
                return null;
            }
            return filename;
        }

        private int consumeOption(String text, int index, OptionArity arity) {
            int values = 0;
            int cursor = index;
            while (values < arity.maximum()) {
                int end = nextWhitespace(text, cursor);
                if (end == cursor) break;
                String value = text.substring(cursor, end);
                if (!arity.accepts(value)) break;
                values++;
                cursor = skipWhitespace(text, end);
            }
            return values < arity.minimum() ? -1 : cursor;
        }

        private void addReference(AssetReference.Kind kind, String value) {
            if (references.size() >= limits.maxReferencesPerFile()) {
                if (!referenceLimit) limitations.add(new Limitation("REFERENCE_LIMIT",
                        "Reference inspection limit reached; additional references were not recorded"));
                referenceLimit = true;
                return;
            }
            references.add(new ExtractedReference(kind, value, false));
        }

        private void unresolved(AssetReference.Kind kind, String value, String message) {
            syntaxLimitation = true;
            limitations.add(new Limitation("REFERENCE_SYNTAX_LIMITATION", message));
            if (references.size() < limits.maxReferencesPerFile()) {
                references.add(new ExtractedReference(kind, value, true));
            } else if (!referenceLimit) {
                limitations.add(new Limitation("REFERENCE_LIMIT",
                        "Reference inspection limit reached; additional references were not recorded"));
                referenceLimit = true;
            }
        }

        private void malformed(String message) {
            if (!malformed) {
                malformed = true;
                malformedMessage = message;
            }
        }

        private static boolean isTextureDirective(String directive) {
            return directive.equals("map_Kd") || directive.equals("map_Ks") || directive.equals("map_Bump")
                    || directive.equals("bump") || directive.equals("norm") || directive.equals("map_d");
        }

        private static OptionArity optionArity(String option) {
            return switch (option) {
                case "-blendu", "-blendv", "-cc", "-clamp" -> OptionArity.ON_OFF;
                case "-mm" -> OptionArity.TWO_NUMBERS;
                case "-o", "-s", "-t" -> OptionArity.ONE_TO_THREE_NUMBERS;
                case "-texres" -> OptionArity.INTEGER;
                case "-bm", "-boost" -> OptionArity.NUMBER;
                case "-imfchan" -> OptionArity.CHANNEL;
                case "-type" -> OptionArity.REFLECTION_TYPE;
                case "-halo" -> OptionArity.NONE;
                default -> null;
            };
        }

        private static String[] tokens(String arguments) {
            return arguments.isEmpty() ? new String[0] : arguments.split("\\s+");
        }

        private static boolean allFinite(String[] values) {
            for (String value : values) if (!finite(value)) return false;
            return true;
        }

        private static boolean allFaceVertices(String[] values) {
            for (String value : values) if (!faceVertex(value)) return false;
            return true;
        }

        private static boolean faceVertex(String value) {
            String[] fields = value.split("/", -1);
            if (fields.length < 1 || fields.length > 3 || !nonZeroInteger(fields[0])) return false;
            if (fields.length == 1) return true;
            if (fields.length == 2) return nonZeroInteger(fields[1]);
            return (!fields[1].isEmpty() && nonZeroInteger(fields[1]) || fields[1].isEmpty())
                    && nonZeroInteger(fields[2]);
        }

        private static boolean nonZeroInteger(String value) {
            if (value.isEmpty()) return false;
            int offset = value.charAt(0) == '-' || value.charAt(0) == '+' ? 1 : 0;
            if (offset == value.length()) return false;
            for (int i = offset; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
            try { return Long.parseLong(value) != 0; }
            catch (NumberFormatException ignored) { return false; }
        }

        private static boolean finite(String value) {
            try { return Double.isFinite(Double.parseDouble(value)); }
            catch (NumberFormatException ignored) { return false; }
        }

        private static int nextWhitespace(String text, int index) {
            int cursor = index;
            while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) cursor++;
            return cursor;
        }

        private static int skipWhitespace(String text, int index) {
            int cursor = index;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
            return cursor;
        }

        private static void checkInterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Asset scan interrupted");
        }

        private enum OptionArity {
            NONE(0, 0) { boolean accepts(String value) { return false; } },
            ON_OFF(1, 1) { boolean accepts(String value) { return value.equals("on") || value.equals("off"); } },
            TWO_NUMBERS(2, 2) { boolean accepts(String value) { return finite(value); } },
            ONE_TO_THREE_NUMBERS(1, 3) { boolean accepts(String value) { return finite(value); } },
            INTEGER(1, 1) { boolean accepts(String value) { return value.matches("[0-9]+"); } },
            NUMBER(1, 1) { boolean accepts(String value) { return finite(value); } },
            CHANNEL(1, 1) { boolean accepts(String value) { return value.matches("[rgbmlz]"); } },
            REFLECTION_TYPE(1, 1) {
                boolean accepts(String value) {
                    return value.equals("sphere") || value.matches("cube_(top|bottom|front|back|left|right)");
                }
            };

            private final int minimum;
            private final int maximum;
            OptionArity(int minimum, int maximum) { this.minimum = minimum; this.maximum = maximum; }
            int minimum() { return minimum; }
            int maximum() { return maximum; }
            abstract boolean accepts(String value);
        }
    }
}
