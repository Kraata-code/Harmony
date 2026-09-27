-keep interface com.google.audio.ambientmusic.NnfpRecognizerCallback {
    void onMusicScoreComputed(float);
}

-keep class * implements com.google.audio.ambientmusic.NnfpRecognizerCallback {
    void onMusicScoreComputed(float);
}

-keep class com.google.audio.ambientmusic.NnfpV3Recognizer {
    *;
}
