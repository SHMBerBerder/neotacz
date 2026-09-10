package com.tacz.guns.client.model.gltf.quality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded container validation before libktx can allocate from untrusted offsets or lengths. */
record Ktx2ImageHeader(int width, int height, int model, int primaries, int transfer,
                      Channels channels, List<Level> levels, long rgbaBytes, long inflatedBytes) {
    static final long MAX_RGBA_BYTES = 384L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_METADATA_ENTRIES = 1024;
    private static final byte[] MAGIC = {(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, 13, 10, 26, 10};

    enum Channels { RGB, RGBA, RED, RG }
    record Level(long offset, long length, long uncompressedLength) { }

    Ktx2ImageHeader { levels = List.copyOf(levels); }

    static boolean matches(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length) return false;
        for (int index = 0; index < MAGIC.length; index++) if (bytes[index] != MAGIC[index]) return false;
        return true;
    }

    static Ktx2ImageHeader read(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        TextureVariantPolicy.validateEncodedLength(encoded.length);
        require(matches(encoded) && encoded.length >= 104, "missing KTX2 header/level index");
        ByteBuffer data = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        require(u32(data, 12) == 0 && u32(data, 16) == 1, "only Basis Universal KTX2 is supported");
        int width = dimension(u32(data, 20)), height = dimension(u32(data, 24));
        require(u32(data, 28) == 0 && u32(data, 32) == 0 && u32(data, 36) == 1,
                "only non-array, non-cubemap 2D KTX2 textures are supported");
        long count = u32(data, 40);
        require(count >= 1 && count <= 32 - Integer.numberOfLeadingZeros(Math.max(width, height)),
                "invalid KTX2 mip count");
        int mipCount = (int) count;
        long scheme = u32(data, 44);
        require(scheme <= 2, "unsupported KTX2 supercompression scheme");
        long indexEnd = 80L + 24L * mipCount;
        long dfdOffset = u32(data, 48), dfdLength = u32(data, 52);
        long kvdOffset = u32(data, 56), kvdLength = u32(data, 60);
        long sgdOffset = u64(data, 64), sgdLength = u64(data, 72);
        require(dfdOffset == indexEnd && dfdLength >= 44 && dfdLength <= 65536,
                "invalid KTX2 DFD size or position");
        range(dfdOffset, dfdLength, indexEnd, encoded.length);
        require(dfdLength + kvdLength <= MAX_METADATA_BYTES, "KTX2 metadata exceeds the local byte budget");
        Dfd dfd = dfd(data, (int) dfdOffset, (int) dfdLength);
        require(dfd.model == 163 ? scheme == 1 : scheme == 0 || scheme == 2,
                "Basis format and supercompression scheme disagree");
        long metadataEnd = dfdOffset + dfdLength;
        if (kvdLength == 0) {
            require(kvdOffset == 0, "empty KVD must have zero offset");
        } else {
            require(kvdOffset == align(metadataEnd, 4), "invalid KVD position");
            range(kvdOffset, kvdLength, metadataEnd, encoded.length);
            metadata(data, (int) kvdOffset, (int) kvdLength);
            metadataEnd = kvdOffset + kvdLength;
        }
        if (scheme == 1) {
            require(sgdOffset == align(metadataEnd, 8) && sgdLength >= 20L + 20L * mipCount,
                    "missing BasisLZ global data");
            range(sgdOffset, sgdLength, metadataEnd, encoded.length);
            metadataEnd = sgdOffset + sgdLength;
        } else {
            require(sgdOffset == 0 && sgdLength == 0, "unexpected supercompression global data");
        }
        List<Level> levels = new ArrayList<>(mipCount);
        long rgbaBytes = 0, inflatedBytes = 0;
        for (int index = 0; index < mipCount; index++) {
            int cursor = 80 + index * 24;
            long offset = u64(data, cursor), length = u64(data, cursor + 8), uncompressed = u64(data, cursor + 16);
            range(offset, length, metadataEnd, encoded.length);
            long w = Math.max(1, width >> index), h = Math.max(1, height >> index);
            rgbaBytes = Math.addExact(rgbaBytes, Math.multiplyExact(4, Math.multiplyExact(w, h)));
            if (scheme == 1) {
                require(uncompressed == 0, "BasisLZ uncompressedByteLength must be zero");
            } else {
                long blocks = Math.multiplyExact(16, Math.multiplyExact((w + 3) / 4, (h + 3) / 4));
                require(uncompressed == blocks && (scheme != 0 || length == blocks),
                        "UASTC level expansion does not match its dimensions");
                inflatedBytes = Math.addExact(inflatedBytes, blocks);
            }
            levels.add(new Level(offset, length, uncompressed));
        }
        require(rgbaBytes <= MAX_RGBA_BYTES, "KTX2 RGBA mip chain exceeds the CPU output budget");
        long alignment = scheme == 0 ? 16 : 1;
        long expectedOffset = align(metadataEnd, alignment);
        for (int index = mipCount - 1; index >= 0; index--) {
            Level level = levels.get(index);
            require(level.offset == expectedOffset, "KTX2 mip payloads overlap or have invalid layout");
            expectedOffset = level.offset + level.length;
            if (index > 0) expectedOffset = align(expectedOffset, alignment);
        }
        require(expectedOffset == encoded.length, "KTX2 payload size does not match the file");
        if (scheme == 1) {
            basisGlobal(data, (int) sgdOffset, (int) sgdLength, levels, dfd.channels);
            inflatedBytes = levels.getFirst().offset + levels.getFirst().length - levels.getLast().offset;
        }
        return new Ktx2ImageHeader(width, height, dfd.model, dfd.primaries, dfd.transfer,
                dfd.channels, levels, rgbaBytes, inflatedBytes);
    }

    void validateUsage(TextureImageFilter.Role role) {
        boolean color = role == TextureImageFilter.Role.BASE_COLOR || role == TextureImageFilter.Role.EMISSIVE;
        require(color ? primaries == 1 && transfer == 2 : primaries == 0 && transfer == 1,
                "KTX2 DFD color space does not match the material texture role");
        require(role == TextureImageFilter.Role.OCCLUSION || channels == Channels.RGB || channels == Channels.RGBA,
                "KTX2 material texture role requires RGB channels");
    }

    private static Dfd dfd(ByteBuffer data, int start, int length) {
        require(u32(data, start) == length, "DFD total size mismatch");
        int base = start + 4, end = start + length;
        require(u32(data, base) == 0 && u16(data, base + 4) == 2, "unsupported basic DFD descriptor");
        int blockSize = u16(data, base + 6), model = u8(data, base + 8);
        require(blockSize >= 40 && blockSize <= end - base && (blockSize - 24) % 16 == 0,
                "invalid DFD sample layout");
        int samples = (blockSize - 24) / 16;
        require(model == 163 ? samples == 1 || samples == 2 : model == 166 && samples == 1,
                "only ETC1S and UASTC LDR descriptors are supported");
        require(u8(data, base + 11) == 0, "premultiplied or unknown KTX2 alpha flags are unsupported");
        require(u32(data, base + 12) == 0x0303, "Basis texel blocks must be 4x4");
        for (int plane = 0; plane < 8; plane++) {
            int bytes = u8(data, base + 16 + plane);
            int expected = model == 163 ? (plane < samples ? 8 : 0) : (plane == 0 ? 16 : 0);
            require(bytes == expected || bytes == 0, "invalid Basis DFD plane size");
        }
        for (int cursor = base + blockSize; cursor < end;) {
            require(end - cursor >= 8, "truncated extra DFD descriptor");
            int extra = u16(data, cursor + 6);
            require(extra >= 8 && extra % 4 == 0 && extra <= end - cursor, "invalid extra DFD size");
            cursor += extra;
        }
        int first = sample(data, base + 24, model, 0), second = samples == 2 ? sample(data, base + 40, model, 1) : -1;
        Channels channels;
        if (model == 163) {
            if (first == 0 && second == -1) channels = Channels.RGB;
            else if (first == 0 && second == 15) channels = Channels.RGBA;
            else if (first == 3 && second == -1) channels = Channels.RED;
            else if (first == 3 && second == 4) channels = Channels.RG;
            else throw invalid("invalid ETC1S DFD channels");
        } else {
            channels = switch (first) {
                case 0 -> Channels.RGB;
                case 3 -> Channels.RGBA;
                case 4 -> Channels.RED;
                case 6 -> Channels.RG;
                default -> throw invalid("invalid UASTC DFD channels");
            };
        }
        int primaries = u8(data, base + 9), transfer = u8(data, base + 10);
        require((primaries == 1 && transfer == 2) || (primaries == 0 && transfer == 1),
                "KTX2 material DFD must describe sRGB color or linear non-color data");
        require(transfer != 2 || channels == Channels.RGB || channels == Channels.RGBA,
                "red and red-green Basis textures must be linear");
        return new Dfd(model, primaries, transfer, channels);
    }

    private static int sample(ByteBuffer data, int cursor, int model, int index) {
        require(u16(data, cursor) == (model == 163 ? index * 64 : 0)
                        && u8(data, cursor + 2) == (model == 163 ? 63 : 127)
                        && u32(data, cursor + 8) == 0
                        && u32(data, cursor + 12) == 0xFFFFFFFFL,
                "invalid Basis DFD sample bounds");
        int channel = u8(data, cursor + 3);
        require((channel & 0xE0) == 0, "unsupported DFD sample qualifiers");
        require((channel & 0x10) == 0 || model == 163 && (channel & 15) == 15,
                "only a separate alpha sample may override the transfer function");
        return channel & 15;
    }

    private static void metadata(ByteBuffer data, int start, int length) {
        int cursor = start, end = start + length, count = 0;
        var keys = new HashSet<String>();
        while (cursor < end) {
            require(end - cursor >= 4 && ++count <= MAX_METADATA_ENTRIES, "invalid or excessive KVD entries");
            long size = u32(data, cursor);
            cursor += 4;
            require(size >= 2 && size <= end - cursor, "KVD entry length exceeds its section");
            int entryEnd = cursor + (int) size, keyEnd = cursor;
            while (keyEnd < entryEnd && data.get(keyEnd) != 0) keyEnd++;
            require(keyEnd > cursor && keyEnd < entryEnd, "KVD key is empty or unterminated");
            String key;
            try {
                key = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(data.slice(cursor, keyEnd - cursor)).toString();
            } catch (CharacterCodingException failure) {
                throw invalid("KVD key is not UTF-8");
            }
            require(keys.add(key), "duplicate KVD key");
            require(!key.equals("KTXanimData"), "animated KTX2 images are unsupported");
            if (key.equals("KTXorientation")) value(data, keyEnd + 1, entryEnd, "rd");
            if (key.equals("KTXswizzle")) value(data, keyEnd + 1, entryEnd, "rgba");
            int padded = Math.toIntExact(align(entryEnd, 4));
            require(padded <= end, "KVD padding exceeds its section");
            for (int index = entryEnd; index < padded; index++) require(data.get(index) == 0, "nonzero KVD padding");
            cursor = padded;
        }
    }

    private static void value(ByteBuffer data, int start, int end, String expected) {
        require(end - start == expected.length() + 1 && data.get(end - 1) == 0,
                "unsupported KTX2 orientation or swizzle");
        for (int index = 0; index < expected.length(); index++) {
            require(data.get(start + index) == expected.charAt(index), "unsupported KTX2 orientation or swizzle");
        }
    }

    private static void basisGlobal(ByteBuffer data, int start, int length, List<Level> levels, Channels channels) {
        long endpoints = u32(data, start + 4), selectors = u32(data, start + 8), tables = u32(data, start + 12);
        require(u16(data, start) > 0 && u16(data, start + 2) > 0 && endpoints > 0 && selectors > 0 && tables > 0,
                "missing BasisLZ codebooks");
        require(u32(data, start + 16) == 0 && 20L + 20L * levels.size() + endpoints + selectors + tables == length,
                "BasisLZ global data sizes do not match");
        boolean twoSlices = channels == Channels.RGBA || channels == Channels.RG;
        for (int index = 0; index < levels.size(); index++) {
            int cursor = start + 20 + 20 * index;
            require(u32(data, cursor) == 0, "BasisLZ animation/P-frame flags are unsupported");
            slice(u32(data, cursor + 4), u32(data, cursor + 8), levels.get(index).length);
            long alphaOffset = u32(data, cursor + 12), alphaLength = u32(data, cursor + 16);
            if (twoSlices) slice(alphaOffset, alphaLength, levels.get(index).length);
            else require(alphaOffset == 0 && alphaLength == 0, "unexpected BasisLZ second slice");
        }
    }

    private static void slice(long offset, long length, long limit) {
        require(length > 0 && offset <= limit && length <= limit - offset, "BasisLZ slice exceeds its mip level");
    }

    private static int dimension(long value) {
        require(value >= 4 && value <= MAX_DIMENSION && value % 4 == 0, "unsupported KTX2 dimensions");
        return (int) value;
    }

    private static void range(long offset, long length, long minimum, long limit) {
        require(offset >= minimum && offset <= limit && length > 0 && length <= limit - offset,
                "KTX2 section exceeds its encoded buffer");
    }

    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) / alignment * alignment; }
    private static long u32(ByteBuffer data, int offset) { return Integer.toUnsignedLong(data.getInt(offset)); }
    private static int u16(ByteBuffer data, int offset) { return Short.toUnsignedInt(data.getShort(offset)); }
    private static int u8(ByteBuffer data, int offset) { return Byte.toUnsignedInt(data.get(offset)); }
    private static long u64(ByteBuffer data, int offset) {
        long value = data.getLong(offset);
        require(value >= 0, "KTX2 unsigned size is out of range");
        return value;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw invalid(reason); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private record Dfd(int model, int primaries, int transfer, Channels channels) { }
}
