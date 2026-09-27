package com.harmony.music.identifier

internal object Chromaprint {
    init {
        System.loadLibrary("chromaprint-jni")
    }

    external fun fingerprint(pcm: ByteArray, sampleRate: Int, channelCount: Int): String
}
