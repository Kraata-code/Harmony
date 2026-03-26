#include "LlamaCommon.h"
#include "llama.h"
#include <dlfcn.h>
#include <sstream>

static void androidLogCallback(enum ggml_log_level level, const char * text, void * /* user_data */) {
    int priority = ANDROID_LOG_DEFAULT;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO; break;
        case GGML_LOG_LEVEL_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    if (text != nullptr) {
        __android_log_write(priority, LOG_TAG, text);
    }
}

void setAndroidLogCallback() {
    llama_log_set(androidLogCallback, nullptr);
}

std::string joinLongs(const std::vector<long> & values) {
    std::ostringstream builder;
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) builder << ",";
        builder << values[i];
    }
    return builder.str();
}

std::string getRegisteredBackends() {
    std::ostringstream builder;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        if (i > 0) builder << ", ";
        builder << ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    return builder.str();
}

// --- Lógica de carga de Backends ---

static std::vector<std::string> getCpuBackendCandidates() {
#if defined(__aarch64__)
    return {
            "libggml-cpu-android_armv9.2_2.so", "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.0_1.so", "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv8.2_2.so", "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.0_1.so", "libggml-cpu.so",
    };
#elif defined(__x86_64__)
    return {
            "libggml-cpu-sapphirerapids.so", "libggml-cpu-alderlake.so",
            "libggml-cpu-icelake.so", "libggml-cpu-skylakex.so",
            "libggml-cpu-haswell.so", "libggml-cpu-sandybridge.so",
            "libggml-cpu-sse42.so", "libggml-cpu-x64.so", "libggml-cpu.so",
    };
#else
    return {"libggml-cpu.so"};
#endif
}

static int probeBackendScore(const std::string & candidate) {
    dlerror();
    void * handle = dlopen(candidate.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return 0;

    dlerror();
    auto score_fn = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
    const char * symbol_error = dlerror();

    int score = 0;
    if (score_fn != nullptr && symbol_error == nullptr) {
        score = score_fn();
    } else if (candidate == "libggml-cpu.so") {
        score = 1;
    }

    dlclose(handle);
    return score;
}

bool loadBestCpuBackendBySonameFallback() {
    const auto candidates = getCpuBackendCandidates();
    int best_score = 0;
    std::string best_candidate;

    for (const auto & candidate : candidates) {
        const int score = probeBackendScore(candidate);
        if (score > best_score) {
            best_score = score;
            best_candidate = candidate;
        }
    }

    if (best_candidate.empty()) {
        LOGE("No se encontró ningún backend CPU cargable por soname");
        return false;
    }

    LOGI("Cargando backend CPU por soname: %s (score=%d)", best_candidate.c_str(), best_score);
    return ggml_backend_load(best_candidate.c_str()) != nullptr;
}

// Definición de la variable global
bool g_runtime_initialized = false;