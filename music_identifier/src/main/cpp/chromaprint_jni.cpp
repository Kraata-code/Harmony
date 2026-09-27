#include <jni.h>

#include <climits>
#include <cstdint>
#include <cstring>
#include <vector>

#include <chromaprint.h>

namespace {

void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_harmony_music_identifier_Chromaprint_fingerprint(
        JNIEnv* env,
        jobject,
        jbyteArray pcm,
        jint sampleRate,
        jint channelCount) {
    if (pcm == nullptr) {
        throwException(env, "java/lang/IllegalArgumentException", "PCM data cannot be null");
        return nullptr;
    }
    if (sampleRate <= 1000 || channelCount <= 0) {
        throwException(env, "java/lang/IllegalArgumentException", "Invalid audio format");
        return nullptr;
    }

    const jsize byteCount = env->GetArrayLength(pcm);
    if (byteCount == 0 || byteCount % static_cast<jsize>(sizeof(int16_t)) != 0) {
        throwException(env, "java/lang/IllegalArgumentException", "PCM data must contain 16-bit samples");
        return nullptr;
    }

    const jsize sampleCount = byteCount / static_cast<jsize>(sizeof(int16_t));
    if (sampleCount % channelCount != 0 || sampleCount > INT_MAX) {
        throwException(env, "java/lang/IllegalArgumentException", "PCM data is not aligned to its channels");
        return nullptr;
    }

    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "Could not access PCM data");
        return nullptr;
    }
    std::vector<int16_t> samples(static_cast<size_t>(sampleCount));
    std::memcpy(samples.data(), bytes, static_cast<size_t>(byteCount));
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);

    ChromaprintContext* context = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (context == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "Could not create Chromaprint context");
        return nullptr;
    }

    if (!chromaprint_start(context, sampleRate, channelCount) ||
        !chromaprint_feed(context, samples.data(), static_cast<int>(sampleCount)) ||
        !chromaprint_finish(context)) {
        chromaprint_free(context);
        throwException(env, "java/lang/IllegalArgumentException", "Could not fingerprint PCM data");
        return nullptr;
    }

    char* encodedFingerprint = nullptr;
    if (!chromaprint_get_fingerprint(context, &encodedFingerprint) || encodedFingerprint == nullptr) {
        chromaprint_free(context);
        throwException(env, "java/lang/IllegalStateException", "Could not encode Chromaprint fingerprint");
        return nullptr;
    }

    jstring result = env->NewStringUTF(encodedFingerprint);
    chromaprint_dealloc(encodedFingerprint);
    chromaprint_free(context);
    return result;
}
