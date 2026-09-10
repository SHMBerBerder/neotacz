package com.tacz.guns.client.model.gltf.convert;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

final class GltfTestAssets {
    private static final String ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    private GltfTestAssets() {
    }

    static Asset staticPbr() {
        ByteBuffer data = ByteBuffer.allocate(172).order(ByteOrder.LITTLE_ENDIAN);
        data.putLong(0, 0xCEFAEDFEEFBEADDEL);
        putVertex(data, 8, new float[]{1, 2, 3}, new float[]{0, 1, 0}, new float[]{0.25f, 0.75f},
                new int[]{255, 128, 0, 64}, new float[]{1, 0, 0, -1});
        putVertex(data, 60, new float[]{-1, 0, 2}, new float[]{0, 0, 1}, new float[]{1, 0},
                new int[]{0, 255, 64, 255}, new float[]{0, 1, 0, 1});
        putVertex(data, 112, new float[]{0, 4, -2}, new float[]{1, 0, 0}, new float[]{0.5f, 0.25f},
                new int[]{32, 64, 128, 255}, new float[]{0, 0, 1, -1});
        data.put(164, (byte) 0xAA);
        data.put(165, (byte) 0xBB);
        data.putShort(166, (short) 2);
        data.putShort(168, (short) 0);
        data.putShort(170, (short) 1);
        byte[] bytes = data.array();
        String json = """
                {
                  "asset":{"version":"2.0"},
                  "buffers":[{"uri":"%s","byteLength":172}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":8,"byteLength":156,"byteStride":52,"target":34962},
                    {"buffer":0,"byteOffset":164,"byteLength":8,"target":34963}
                  ],
                  "accessors":[
                    {"bufferView":0,"byteOffset":0,"componentType":5126,"count":3,"type":"VEC3","min":[-1,0,-2],"max":[1,4,3]},
                    {"bufferView":0,"byteOffset":12,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":0,"byteOffset":24,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":0,"byteOffset":32,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"},
                    {"bufferView":0,"byteOffset":36,"componentType":5126,"count":3,"type":"VEC4"},
                    {"bufferView":1,"byteOffset":2,"componentType":5123,"count":3,"type":"SCALAR"}
                  ],
                  "images":[{"uri":"data:image/png;base64,%s"}],
                  "textures":[{"source":0}],
                  "materials":[{
                    "pbrMetallicRoughness":{
                      "baseColorFactor":[0.8,0.6,0.4,0.5],
                      "baseColorTexture":{"index":0},
                      "metallicFactor":0.25,
                      "roughnessFactor":0.75,
                      "metallicRoughnessTexture":{"index":0}
                    },
                    "normalTexture":{"index":0,"scale":0.8},
                    "occlusionTexture":{"index":0,"strength":0.6},
                    "emissiveTexture":{"index":0},
                    "emissiveFactor":[0.1,0.2,0.3],
                    "alphaMode":"MASK","alphaCutoff":0.4,"doubleSided":true
                  }],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"COLOR_0":3},"indices":5,"material":0,"mode":4}]}],
                  "nodes":[{"mesh":0}],
                  "scenes":[{"nodes":[0]}],"scene":0
                }
                """.formatted(dataUri(bytes), ONE_PIXEL_PNG);
        return new Asset(json, bytes);
    }

    static Asset skinMorphAnimation() {
        ByteBuffer data = ByteBuffer.allocate(336).order(ByteOrder.LITTLE_ENDIAN);
        data.putLong(0, 0x0102030405060708L);
        putSkinnedVertex(data, 8, new float[]{1, 0, 0}, new float[]{0, 1, 0},
                new int[]{0, 1, 0, 0}, new int[]{255, 0, 0, 0});
        putSkinnedVertex(data, 40, new float[]{0, 1, 0}, new float[]{1, 0, 0},
                new int[]{0, 1, 0, 0}, new int[]{128, 127, 0, 0});
        putMorph(data, 72, new float[]{1, 0, 0}, new float[]{0, 0, 1});
        putMorph(data, 96, new float[]{1, 0, 0}, new float[]{0, 1, 0});
        data.putInt(120, 0x55555555);
        putMatrix(data, 124, new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        });
        putMatrix(data, 188, new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, -1, 0, 1
        });
        data.putInt(252, 0x55555555);
        data.putFloat(256, 0);
        data.putFloat(260, 2);
        data.putInt(264, 0x55555555);
        putFloats(data, 268, 0, 1, 0, 0, 3, 0);
        data.putInt(292, 0x55555555);
        putFloats(data, 296, 0, 1);
        data.putInt(304, 0x55555555);
        putFloats(data, 308, 1, 1, 1, 3, 1, 1);
        data.put(332, (byte) 0);
        data.put(333, (byte) 1);
        data.put(334, (byte) 1);
        byte[] bytes = data.array();
        String json = """
                {
                  "asset":{"version":"2.0"},
                  "buffers":[{"uri":"%s","byteLength":336}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":8,"byteLength":64,"byteStride":32,"target":34962},
                    {"buffer":0,"byteOffset":72,"byteLength":48,"byteStride":24},
                    {"buffer":0,"byteOffset":120,"byteLength":196},
                    {"buffer":0,"byteOffset":252,"byteLength":12},
                    {"buffer":0,"byteOffset":264,"byteLength":28},
                    {"buffer":0,"byteOffset":292,"byteLength":12},
                    {"buffer":0,"byteOffset":304,"byteLength":28},
                    {"buffer":0,"byteOffset":332,"byteLength":3,"target":34963}
                  ],
                  "accessors":[
                    {"bufferView":0,"byteOffset":0,"componentType":5126,"count":2,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                    {"bufferView":0,"byteOffset":12,"componentType":5126,"count":2,"type":"VEC3"},
                    {"bufferView":0,"byteOffset":24,"componentType":5121,"count":2,"type":"VEC4"},
                    {"bufferView":0,"byteOffset":28,"componentType":5121,"normalized":true,"count":2,"type":"VEC4"},
                    {"bufferView":1,"byteOffset":0,"componentType":5126,"count":2,"type":"VEC3"},
                    {"bufferView":1,"byteOffset":12,"componentType":5126,"count":2,"type":"VEC3"},
                    {"bufferView":2,"byteOffset":4,"componentType":5126,"count":3,"type":"MAT4"},
                    {"bufferView":3,"byteOffset":4,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[2]},
                    {"bufferView":4,"byteOffset":4,"componentType":5126,"count":2,"type":"VEC3"},
                    {"bufferView":5,"byteOffset":4,"componentType":5126,"count":2,"type":"SCALAR"},
                    {"bufferView":6,"byteOffset":4,"componentType":5126,"count":2,"type":"VEC3"},
                    {"bufferView":7,"byteOffset":0,"componentType":5121,"count":3,"type":"SCALAR"}
                  ],
                  "meshes":[{"weights":[0.2],"primitives":[{
                    "attributes":{"POSITION":0,"NORMAL":1,"JOINTS_0":2,"WEIGHTS_0":3},
                    "indices":11,"mode":4,"targets":[{"POSITION":4,"NORMAL":5}]
                  }]}],
                  "nodes":[
                    {"mesh":0,"skin":0,"weights":[0.25]},
                    {"name":"jointRoot","children":[2]},
                    {"name":"jointTip","translation":[0,1,0]}
                  ],
                  "skins":[{"skeleton":1,"joints":[1,2],"inverseBindMatrices":6}],
                  "animations":[{"name":"cycle","samplers":[
                    {"input":7,"output":8,"interpolation":"LINEAR"},
                    {"input":7,"output":9,"interpolation":"LINEAR"},
                    {"input":7,"output":10,"interpolation":"LINEAR"}
                  ],"channels":[
                    {"sampler":0,"target":{"node":2,"path":"translation"}},
                    {"sampler":1,"target":{"node":0,"path":"weights"}},
                    {"sampler":2,"target":{"node":2,"path":"scale"}}
                  ]}],
                  "scenes":[{"nodes":[0,1]}],"scene":0
                }
                """.formatted(dataUri(bytes));
        return new Asset(json, bytes);
    }

    static String dataUri(byte[] bytes) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static void putVertex(
            ByteBuffer buffer, int offset, float[] position, float[] normal, float[] uv, int[] color, float[] tangent
    ) {
        putFloats(buffer, offset, position);
        putFloats(buffer, offset + 12, normal);
        putFloats(buffer, offset + 24, uv);
        for (int i = 0; i < color.length; i++) {
            buffer.put(offset + 32 + i, (byte) color[i]);
        }
        putFloats(buffer, offset + 36, tangent);
    }

    private static void putSkinnedVertex(
            ByteBuffer buffer, int offset, float[] position, float[] normal, int[] joints, int[] weights
    ) {
        putFloats(buffer, offset, position);
        putFloats(buffer, offset + 12, normal);
        for (int i = 0; i < 4; i++) {
            buffer.put(offset + 24 + i, (byte) joints[i]);
            buffer.put(offset + 28 + i, (byte) weights[i]);
        }
    }

    private static void putMorph(ByteBuffer buffer, int offset, float[] position, float[] normal) {
        putFloats(buffer, offset, position);
        putFloats(buffer, offset + 12, normal);
    }

    private static void putMatrix(ByteBuffer buffer, int offset, float[] matrix) {
        putFloats(buffer, offset, matrix);
    }

    private static void putFloats(ByteBuffer buffer, int offset, float... values) {
        for (int i = 0; i < values.length; i++) {
            buffer.putFloat(offset + i * Float.BYTES, values[i]);
        }
    }

    record Asset(String json, byte[] bytes) {
        Asset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
