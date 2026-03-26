#pragma once

#include <jni.h>
#include <string>
#include <vector>

// Macros de log
#include <android/log.h>
#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Variable global para estado del runtime
extern bool g_runtime_initialized;

// Funciones de utilidad y gestión de backends
void setAndroidLogCallback();
std::string getRegisteredBackends();
bool loadBestCpuBackendBySonameFallback();
std::string joinLongs(const std::vector<long> & values);
