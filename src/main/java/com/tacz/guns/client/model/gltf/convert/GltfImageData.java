package com.tacz.guns.client.model.gltf.convert;

public final class GltfImageData {
    private final String name;
    private final String uri;
    private final String mimeType;
    private final byte[] encodedBytes;

    public GltfImageData(String name, String uri, String mimeType, byte[] encodedBytes) {
        this.name = name == null ? "" : name;
        this.uri = uri == null ? "" : uri;
        this.mimeType = mimeType == null ? "" : mimeType;
        if (encodedBytes == null || encodedBytes.length == 0) {
            throw new IllegalArgumentException("encodedBytes must not be empty");
        }
        this.encodedBytes = encodedBytes.clone();
    }

    public String name() {
        return name;
    }

    public String uri() {
        return uri;
    }

    public String mimeType() {
        return mimeType;
    }

    public byte[] encodedBytes() {
        return encodedBytes.clone();
    }
}
