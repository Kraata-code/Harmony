package com.google.audio.ambientmusic;

public final class NnfpV3Recognizer {
    static {
        System.loadLibrary("modeleditor-jni");
        System.loadLibrary("sense_nnfp_v3");
    }

    private NnfpV3Recognizer() {
    }

    public static native long init(String[] shardNames, String[] shardPaths, byte[] config);

    public static native byte[] recognize(
            long pointer,
            short[] audio,
            byte[] recognitionParams,
            NnfpRecognizerCallback callback,
            boolean runOnSmallCores);

    public static native void close(long pointer);
}
