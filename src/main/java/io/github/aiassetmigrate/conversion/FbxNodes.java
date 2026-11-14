package io.github.aiassetmigrate.conversion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded scan of an FBX file's node tree for the texture file names it records. Assimp reports some of
 * these as an empty path (it reads only one of the several places an FBX can store a file name), so the
 * names are read from the file itself: the {@code RelativeFilename}, {@code FileName} and {@code Filename}
 * children of {@code Texture} and {@code Video} nodes. A {@code Video} with non-empty {@code Content} is an
 * embedded image, not a file reference. Binary FBX (6.x and 7.x, 32- and 64-bit headers) is parsed
 * structurally; ASCII FBX by its property lines. Nothing is decompressed or interpreted beyond names.
 */
final class FbxNodes {
    private FbxNodes() { }

    private static final byte[] MAGIC = "Kaydara FBX Binary  \0".getBytes(StandardCharsets.ISO_8859_1);
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 2_000_000;

    /** One texture or video record: its relative and recorded file names, and whether its image is inside the file. */
    record TextureFile(String relative, String recorded, boolean embedded) {
        /** The name to resolve: the relative one when present, otherwise the recorded (often absolute) one. */
        String path() { return relative.isEmpty() ? recorded : relative; }
    }

    static boolean binary(byte[] data) {
        if (data.length < MAGIC.length + 6) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    static List<TextureFile> textureFiles(byte[] data) throws IOException {
        return binary(data) ? new Binary(data).scan() : ascii(new String(data, StandardCharsets.UTF_8));
    }

    private static final class Node {
        final String name;
        final List<String> strings = new ArrayList<>();
        long rawBytes;
        final List<Node> children = new ArrayList<>();
        Node(String name) { this.name = name; }

        String child(String... names) {
            for (String wanted : names) {
                for (Node child : children) {
                    if (child.name.equals(wanted) && !child.strings.isEmpty() && !child.strings.getFirst().isEmpty()) {
                        return child.strings.getFirst();
                    }
                }
            }
            return "";
        }
    }

    private static final class Binary {
        final ByteBuffer data;
        final boolean wide;
        int nodes;

        Binary(byte[] bytes) {
            data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            wide = data.getInt(MAGIC.length + 2) >= 7500;
        }

        List<TextureFile> scan() throws IOException {
            Node root = new Node("");
            int position = MAGIC.length + 6;
            while (position < data.limit()) {
                int end = node(position, root, 0);
                if (end < 0) break; // null record: end of the top-level list
                position = end;
            }
            List<TextureFile> files = new ArrayList<>();
            collect(root.children, files);
            return files;
        }

        /** Parses one node at {@code at} and adds it to {@code parent}; returns where it ends, or -1 for a null record. */
        int node(int at, Node parent, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("FBX node tree is nested too deeply");
            if (++nodes > MAX_NODES) throw new IOException("FBX node tree is too large to scan");
            int header = wide ? 25 : 13;
            require(at, header);
            long end = wide ? data.getLong(at) : Integer.toUnsignedLong(data.getInt(at));
            long count = wide ? data.getLong(at + 8) : Integer.toUnsignedLong(data.getInt(at + 4));
            long length = wide ? data.getLong(at + 16) : Integer.toUnsignedLong(data.getInt(at + 8));
            int nameLength = Byte.toUnsignedInt(data.get(at + header - 1));
            if (end == 0) return -1;
            if (end > data.limit() || end <= at) throw new IOException("FBX node runs past the end of the file");
            require(at + header, nameLength);
            byte[] name = new byte[nameLength];
            data.get(at + header, name);
            Node node = new Node(new String(name, StandardCharsets.ISO_8859_1));
            int properties = at + header + nameLength;
            if (length > end - properties) throw new IOException("FBX properties run past their node");
            int position = properties;
            for (long i = 0; i < count; i++) position = property(position, (int) (properties + length), node);
            position = (int) (properties + length);
            while (position < end) {
                int next = node(position, node, depth + 1);
                if (next < 0) break; // null record: end of this node's children
                position = next;
            }
            parent.children.add(node);
            return (int) end;
        }

        int property(int at, int limit, Node node) throws IOException {
            if (at >= limit) throw new IOException("FBX property list is shorter than declared");
            char type = (char) data.get(at);
            int position = at + 1;
            int size = switch (type) {
                case 'C' -> 1;
                case 'Y' -> 2;
                case 'I', 'F' -> 4;
                case 'D', 'L' -> 8;
                case 'f', 'd', 'l', 'i', 'b' -> {
                    require(position, 12);
                    yield 12 + checked(Integer.toUnsignedLong(data.getInt(position + 8)));
                }
                case 'S', 'R' -> {
                    require(position, 4);
                    int length = checked(Integer.toUnsignedLong(data.getInt(position)));
                    require(position + 4, length);
                    if (type == 'S') {
                        byte[] text = new byte[length];
                        data.get(position + 4, text);
                        node.strings.add(new String(text, StandardCharsets.UTF_8));
                    } else {
                        node.rawBytes += length;
                    }
                    yield 4 + length;
                }
                default -> throw new IOException("Unknown FBX property type '" + type + "'");
            };
            if (size > limit - position) throw new IOException("FBX property runs past its node");
            return position + size;
        }

        private int checked(long length) throws IOException {
            if (length > data.limit()) throw new IOException("FBX property is larger than the file");
            return (int) length;
        }

        private void require(int at, int length) throws IOException {
            if (at < 0 || length < 0 || at > data.limit() - length) throw new IOException("FBX file is truncated");
        }
    }

    private static void collect(List<Node> nodes, List<TextureFile> files) {
        for (Node node : nodes) {
            if (node.name.equals("Texture") || node.name.equals("Video")) {
                boolean embedded = node.name.equals("Video") && node.children.stream()
                        .anyMatch(child -> child.name.equals("Content") && child.rawBytes > 0);
                TextureFile file = new TextureFile(node.child("RelativeFilename"), node.child("FileName", "Filename"), embedded);
                if (!file.path().isEmpty()) files.add(file);
            }
            collect(node.children, files);
        }
    }

    private static final Pattern ASCII_NAME = Pattern.compile("(?m)^\\s*(RelativeFilename|FileName|Filename)\\s*:\\s*\"([^\"]*)\"");

    /** ASCII FBX: relative names when the file records any, otherwise the recorded names. */
    private static List<TextureFile> ascii(String text) {
        Set<String> relative = new LinkedHashSet<>(), recorded = new LinkedHashSet<>();
        Matcher match = ASCII_NAME.matcher(text);
        while (match.find()) {
            if (match.group(2).isEmpty()) continue;
            (match.group(1).equals("RelativeFilename") ? relative : recorded).add(match.group(2));
        }
        List<TextureFile> files = new ArrayList<>();
        for (String name : relative.isEmpty() ? recorded : relative) {
            files.add(relative.isEmpty() ? new TextureFile("", name, false) : new TextureFile(name, "", false));
        }
        return files;
    }
}
