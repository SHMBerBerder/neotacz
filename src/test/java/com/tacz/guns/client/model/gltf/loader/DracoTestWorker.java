package com.tacz.guns.client.model.gltf.loader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** A real subprocess fixture; it does not load Minecraft or any production decoder. */
public final class DracoTestWorker {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]).resolveSibling("started.pid"), Long.toString(ProcessHandle.current().pid()));
        try (DataInputStream input = new DataInputStream(Files.newInputStream(Path.of(args[0])))) {
            if (input.readInt() != 0x44524931 || input.readInt() != 1) {
                throw new IllegalArgumentException("fixture expects one DRI1 request");
            }
            int vertices = input.readInt();
            int indices = input.readInt();
            int indexType = input.readInt();
            int attributes = input.readInt();
            if (attributes != 1) {
                throw new IllegalArgumentException("fixture expects one attribute");
            }
            int id = input.readInt();
            int type = input.readInt();
            int components = input.readInt();
            boolean normalized = input.readBoolean();
            input.readInt();
            int mode = input.readUnsignedByte();
            if (mode == 1) {
                Thread.sleep(30_000);
            }
            if (mode == 3) {
                System.exit(7);
            }
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
                output.writeInt(0x44524f31);
                if (mode == 4) {
                    return;
                }
                output.writeInt(1);
                output.writeInt(vertices);
                output.writeInt(indices);
                output.writeInt(indexType);
                output.writeInt(attributes);
                output.writeInt(mode == 2 ? Integer.MAX_VALUE : indices * 2);
                byte[] indexData = new byte[indices * 2];
                if (mode == 7) {
                    indexData[0] = (byte) vertices;
                }
                output.write(indexData);
                output.writeInt(id);
                output.writeInt(mode == 8 ? 5123 : type);
                output.writeInt(components);
                output.writeBoolean(normalized);
                int width = type == 5121 ? 1 : 4;
                output.writeInt(vertices * components * width);
                byte[] values = new byte[vertices * components * width];
                if (type == 5121) {
                    Arrays.fill(values, (byte) 128);
                }
                if (mode == 6) {
                    values[2] = (byte) 192;
                    values[3] = 127;
                }
                output.write(values);
            }
        }
    }
}
