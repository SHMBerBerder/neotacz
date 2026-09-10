package com.tacz.guns.tools.draco;

import com.openize.drako.DataType;
import com.openize.drako.Draco;
import com.openize.drako.DracoMesh;
import com.openize.drako.PointAttribute;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;

/** Standalone worker: no Minecraft, NeoForge, or application classpath is loaded. */
public final class DracoWorker {
    private static final int INPUT_MAGIC = 0x44524931;
    private static final int OUTPUT_MAGIC = 0x44524f31;
    // This wire boundary deliberately repeats the client's limits: neither side trusts the other.
    private static final long SINGLE_BYTES = 64L * 1024 * 1024;
    private static final long TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int MAX_PRIMITIVES = 8192;
    private static final int MAX_ATTRIBUTES = 8192;
    private static final long MAX_VERTICES = 250000;
    private static final long MAX_INDICES = 300000;
    private static final long MAX_COMPONENTS = 16000000;

    private DracoWorker() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected input and output file paths");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(Path.of(arguments[0]))));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                     Files.newOutputStream(Path.of(arguments[1]))))) {
            require(input.readInt() == INPUT_MAGIC, "input protocol");
            int count = input.readInt();
            require(count > 0 && count <= MAX_PRIMITIVES, "primitive count");
            output.writeInt(OUTPUT_MAGIC);
            output.writeInt(count);
            Budget budget = new Budget();
            for (int i = 0; i < count; i++) {
                decodeOne(input, output, budget);
            }
            require(input.read() == -1, "trailing input");
        }
    }

    private static void decodeOne(DataInputStream input, DataOutputStream output, Budget budget) throws Exception {
        int vertices = input.readInt();
        int indices = input.readInt();
        int indexType = input.readInt();
        int attributeCount = input.readInt();
        require(vertices > 0 && indices > 0 && indices % 3 == 0, "TRIANGLES counts");
        require(indexType == 5121 || indexType == 5123 || indexType == 5125, "index type");
        budget.vertices += vertices;
        budget.indices += indices;
        budget.attributes += attributeCount;
        require(budget.vertices <= MAX_VERTICES && budget.indices <= MAX_INDICES, "mesh count budget");
        require(attributeCount > 0 && budget.attributes <= MAX_ATTRIBUTES, "attribute count budget");
        int[][] attributes = new int[attributeCount][4];
        HashSet<Integer> uniqueIds = new HashSet<>();
        budget.decoded += (long) indices * width(indexType);
        for (int[] attribute : attributes) {
            attribute[0] = input.readInt();
            attribute[1] = input.readInt();
            attribute[2] = input.readInt();
            attribute[3] = input.readBoolean() ? 1 : 0;
            require(attribute[0] >= 0 && attribute[0] <= 65535 && uniqueIds.add(attribute[0]), "attribute ID");
            require(attribute[2] >= 1 && attribute[2] <= 4, "attribute components");
            require(attribute[3] == 0 || (attribute[1] != 5125 && attribute[1] != 5126), "normalized type");
            long components = (long) vertices * attribute[2];
            long bytes = components * width(attribute[1]);
            require(bytes <= SINGLE_BYTES, "single output budget");
            budget.decoded += bytes;
            budget.components += components;
        }
        require(budget.decoded <= TOTAL_BYTES && budget.components <= MAX_COMPONENTS, "output budget");
        int encodedLength = input.readInt();
        budget.encoded += encodedLength;
        require(encodedLength > 0 && encodedLength <= SINGLE_BYTES && budget.encoded <= TOTAL_BYTES, "input budget");
        byte[] encoded = new byte[encodedLength];
        input.readFully(encoded);
        if (!(Draco.decode(encoded) instanceof DracoMesh mesh)) {
            throw new IOException("Draco did not decode a triangle mesh");
        }
        require(mesh.getNumPoints() == vertices && (long) mesh.getNumFaces() * 3 == indices,
                "decoded mesh shape differs from glTF accessors");
        require(mesh.getNumAttributes() > 0 && mesh.getNumAttributes() <= MAX_ATTRIBUTES, "decoded attribute count");
        HashMap<Integer, PointAttribute> decodedAttributes = new HashMap<>();
        for (int i = 0; i < mesh.getNumAttributes(); i++) {
            PointAttribute attribute = mesh.attribute(i);
            require(decodedAttributes.put(Short.toUnsignedInt(attribute.getUniqueId()), attribute) == null,
                    "duplicate decoded attribute ID");
        }
        output.writeInt(vertices);
        output.writeInt(indices);
        output.writeInt(indexType);
        output.writeInt(attributeCount);
        output.writeInt(indices * width(indexType));
        for (int i = 0; i < indices; i++) {
            int index = mesh.readCorner(i);
            require(index >= 0 && index < vertices, "decoded index bounds");
            require(indexType != 5121 || index <= 255, "index exceeds UBYTE");
            require(indexType != 5123 || index <= 65535, "index exceeds USHORT");
            for (int byteIndex = 0; byteIndex < width(indexType); byteIndex++) {
                output.writeByte(index >>> (byteIndex * 8));
            }
        }
        for (int[] spec : attributes) {
            PointAttribute attribute = decodedAttributes.get(spec[0]);
            require(attribute != null, "missing attribute ID");
            require(attribute.getDataType() == dracoType(spec[1]) && attribute.getComponentsCount() == spec[2]
                    && attribute.getNormalized() == (spec[3] != 0), "decoded attribute shape differs from glTF accessor");
            int stride = width(spec[1]) * spec[2];
            require(attribute.getByteStride() == stride && attribute.getByteOffset() >= 0,
                    "unsupported decoded attribute layout");
            output.writeInt(spec[0]);
            output.writeInt(spec[1]);
            output.writeInt(spec[2]);
            output.writeBoolean(spec[3] != 0);
            output.writeInt(vertices * stride);
            byte[] value = new byte[stride];
            ByteBuffer numeric = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            for (int point = 0; point < vertices; point++) {
                int entry = attribute.mappedIndex(point);
                require(entry >= 0 && entry < attribute.getNumUniqueEntries(), "decoded attribute mapping");
                long end = (long) attribute.getByteOffset() + (long) (entry + 1) * stride;
                require(end <= attribute.getBuffer().getBuffer().length, "decoded attribute storage");
                attribute.getValue(entry, value);
                if (spec[1] == 5126) {
                    for (int component = 0; component < spec[2]; component++) {
                        require(Float.isFinite(numeric.getFloat(component * 4)), "non-finite attribute");
                    }
                }
                output.write(value);
            }
        }
    }

    private static int width(int type) throws IOException {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IOException("Unsupported accessor component type");
        };
    }

    private static int dracoType(int type) throws IOException {
        return switch (type) {
            case 5120 -> DataType.INT8;
            case 5121 -> DataType.UINT8;
            case 5122 -> DataType.INT16;
            case 5123 -> DataType.UINT16;
            case 5125 -> DataType.UINT32;
            case 5126 -> DataType.FLOAT32;
            default -> throw new IOException("Unsupported accessor component type");
        };
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException("Draco " + message);
        }
    }

    private static final class Budget {
        long vertices, indices, attributes, encoded, decoded, components;
    }
}
