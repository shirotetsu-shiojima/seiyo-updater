package jp.seiyo.updater;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

final class PackedArchive {
    static final String EXTENSION = ".syp";
    private static final int MAGIC = 0x53595031;
    private static final int MAX_SIZE = 128 * 1024 * 1024;
    private static final int MAX_HEADER = 1024;

    record Header(String filename, String version, String hash, int size) {}

    // Public, reversible format transform. This does not use a key.
    static void mask(byte[] bytes, int length, int offset) {
        for (int i = 0, position = offset; i < length; i++, position++) {
            bytes[i] ^= (byte) (0xA7 ^ (position * 73) ^ (position >>> 8));
        }
    }

    static boolean isPacked(Path file) {
        return file.getFileName().toString().endsWith(EXTENSION);
    }

    static void write(Path target, String name, String version, String hash, byte[] archive) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var fields = new DataOutputStream(buffer)) {
            fields.writeUTF(name);
            fields.writeUTF(version);
            fields.write(HexFormat.of().parseHex(hash));
        }
        byte[] header = buffer.toByteArray();
        mask(header, header.length, 0);
        try (var out = new DataOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
            out.writeInt(MAGIC);
            out.writeShort(header.length);
            out.writeInt(archive.length);
            out.write(header);
            mask(archive, archive.length, header.length);
            out.write(archive);
        }
    }

    static Header header(Path file) throws IOException {
        try (var input = open(file)) {
            return readHeader(file, input);
        }
    }

    static void unpack(Path file, Path target) throws Exception {
        boolean created = false;
        boolean complete = false;
        try (var input = open(file)) {
            Header header = readHeader(file, input);
            int offset = Math.toIntExact(Files.size(file) - 10 - header.size);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                created = true;
                byte[] data = new byte[64 * 1024];
                int remaining = header.size;
                while (remaining > 0) {
                    int length = input.readNBytes(data, 0, Math.min(remaining, data.length));
                    if (length == 0) throw new IOException("Incomplete SYP archive: " + file);
                    mask(data, length, offset);
                    digest.update(data, 0, length);
                    output.write(data, 0, length);
                    offset += length;
                    remaining -= length;
                }
                if (input.read() != -1 || !HexFormat.of().formatHex(digest.digest()).equals(header.hash)) {
                    throw new IOException("SYP archive checksum mismatch: " + file);
                }
            }
            complete = true;
        } finally {
            if (created && !complete) Files.deleteIfExists(target);
        }
    }

    private static DataInputStream open(Path file) throws IOException {
        return new DataInputStream(Files.newInputStream(file, UpdateWorker.NO_LINKS));
    }

    private static Header readHeader(Path file, DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid SYP signature: " + file);
        int length = input.readUnsignedShort();
        int size = input.readInt();
        if (length < 36 || length > MAX_HEADER || size < 1 || size > MAX_SIZE
                || Files.size(file) != 10L + length + size) throw new IOException("Invalid SYP lengths: " + file);
        byte[] bytes = input.readNBytes(length);
        mask(bytes, bytes.length, 0);
        try (var fields = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String name = fields.readUTF();
            String version = fields.readUTF();
            byte[] hash = fields.readNBytes(32);
            if (!UpdateWorker.validFilename(name) || version.isBlank() || version.length() > 100
                    || hash.length != 32 || fields.read() != -1) throw new IOException("Invalid SYP header: " + file);
            return new Header(name, version, HexFormat.of().formatHex(hash), size);
        }
    }

    private PackedArchive() {}
}
