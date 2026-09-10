package com.tacz.guns.client.model.gltf.quality;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Best-effort, bounded disk cache. Only its own hashed entries and temporary files are evicted. */
final class TextureVariantCache {
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private static final int MAGIC = 0x54565831;
    private static final int HEADER_BYTES = 48;
    private static final long MAX_ENTRY_BYTES = HEADER_BYTES + (long) TextureVariantPolicy.MAX_ENCODED_BYTES;
    private static final Object IO_LOCK = new Object();
    private final Path directory;
    private final long maxBytes;

    TextureVariantCache(Path directory) {
        this(directory, MAX_BYTES);
    }

    TextureVariantCache(Path directory, long maxBytes) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("invalid texture cache capacity");
        this.maxBytes = maxBytes;
    }

    static String key(byte[] source, TextureImageFilter.Spec spec) {
        return key(contentHash(source), spec);
    }

    static String contentHash(byte[] source) {
        return HexFormat.of().formatHex(sha256().digest(source));
    }

    static String key(String sourceHash, TextureImageFilter.Spec spec) {
        MessageDigest digest = sha256();
        digest.update(TextureImageFilter.VERSION.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(Ktx2ImageDecoder.VERSION.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(HexFormat.of().parseHex(sourceHash));
        digest.update((byte) 0);
        String parameters = spec.role() + ":" + spec.maxSize() + ":" + spec.alphaMode()
                + ":" + Float.toHexString(spec.alphaCutoff()) + ":" + Float.toHexString(spec.alphaFactor())
                + ":" + spec.sampler().magFilter() + ":" + spec.sampler().minFilter()
                + ":" + spec.sampler().wrapS() + ":" + spec.sampler().wrapT();
        digest.update(parameters.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    byte[] read(String key, int width, int height) {
        synchronized (IO_LOCK) {
            Path file = path(key);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            try {
                long fileSize = Files.size(file);
                if (fileSize < HEADER_BYTES || fileSize > MAX_ENTRY_BYTES || fileSize > maxBytes) {
                    throw new IOException("invalid texture cache size");
                }
                byte[] encoded;
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if (input.readInt() != MAGIC || input.readInt() != width || input.readInt() != height) {
                        throw new IOException("texture cache metadata mismatch");
                    }
                    int length = input.readInt();
                    if (length <= 0 || length != fileSize - HEADER_BYTES) throw new IOException("invalid cached image length");
                    byte[] hash = input.readNBytes(32);
                    encoded = input.readNBytes(length);
                    if (encoded.length != length || !Arrays.equals(hash, sha256().digest(encoded))) {
                        throw new IOException("texture cache checksum mismatch");
                    }
                }
                TextureVariantPolicy.validateDerivedPng(encoded);
                TextureImageFilter.Dimensions dimensions = TextureImageFilter.inspect(encoded);
                if (dimensions.width() != width || dimensions.height() != height) {
                    throw new IOException("cached PNG dimensions mismatch");
                }
                try { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); }
                catch (IOException ignored) { /* A read-only cache entry is still usable. */ }
                return encoded;
            } catch (IOException | IllegalArgumentException failure) {
                try { Files.deleteIfExists(file); }
                catch (IOException ignored) { /* Recompute even if a corrupt entry cannot be removed. */ }
                return null;
            }
        }
    }

    boolean write(String key, TextureImageFilter.Result image) {
        TextureVariantPolicy.validateDerivedPng(image.encoded());
        synchronized (IO_LOCK) {
            long entryBytes = HEADER_BYTES + (long) image.encoded().length;
            if (entryBytes > maxBytes || entryBytes > MAX_ENTRY_BYTES) return false;
            Path target = path(key);
            Path temporary = null;
            try {
                Files.createDirectories(directory);
                if (read(key, image.width(), image.height()) != null) return true;
                prune(entryBytes);
                temporary = Files.createTempFile(directory, "texture-", ".tmp");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    output.writeInt(MAGIC);
                    output.writeInt(image.width());
                    output.writeInt(image.height());
                    output.writeInt(image.encoded().length);
                    output.write(sha256().digest(image.encoded()));
                    output.write(image.encoded());
                }
                // If this filesystem cannot atomically publish an entry, keep using the in-memory
                // derivative rather than expose a partially written cache file to another load.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
                return true;
            } catch (IOException failure) {
                return false;
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException ignored) { /* Owned leftovers are included in the next bounded prune. */ }
                }
            }
        }
    }

    private void prune(long incomingBytes) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long totalBytes = 0;
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.tv|texture-[0-9]+\\.tmp")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (entries.size() >= MAX_FILES * 2) throw new IOException("unexpectedly large texture cache directory");
                long size = Files.size(path);
                totalBytes = Math.addExact(totalBytes, size);
                entries.add(new Entry(path, size, Files.getLastModifiedTime(path).toMillis()));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::modified).thenComparing(entry -> entry.path().toString()));
        int count = entries.size();
        for (Entry entry : entries) {
            if (totalBytes <= maxBytes - incomingBytes && count < MAX_FILES) break;
            Files.deleteIfExists(entry.path());
            totalBytes -= entry.bytes();
            count--;
        }
    }

    private Path path(String key) {
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid texture cache key");
        return directory.resolve(key + ".tv");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError("SHA-256 is required by Java", exception); }
    }

    private record Entry(Path path, long bytes, long modified) { }
}
