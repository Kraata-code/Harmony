#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/**
 * Context holder con patrón RAII prueb
 */
struct LlamaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;

    ~LlamaContext() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_free_model(model);
    }
};

static std::unique_ptr<LlamaContext> g_llama_context;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dd3boh_outertune_viewmodels_LlamaBridge_initModel(
        JNIEnv* env,
        jobject thiz,
        jstring modelPath
) {
    try {
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        LOGI("Inicializando modelo desde: %s", path);

        llama_backend_init();

        // Parámetros del modelo
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;

        auto model = llama_load_model_from_file(path, mparams);
        env->ReleaseStringUTFChars(modelPath, path);

        if (!model) {
            LOGE("Error: No se pudo cargar el modelo");
            llama_backend_free();
            return JNI_FALSE;
        }

        // Parámetros del contexto
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 2048;
        cparams.n_batch = 512;
        cparams.n_threads = 4;
        cparams.n_threads_batch = 4;

        auto ctx = llama_new_context_with_model(model, cparams);
        if (!ctx) {
            LOGE("Error: No se pudo crear el contexto");
            llama_free_model(model);
            llama_backend_free();
            return JNI_FALSE;
        }

        // Crear sampler chain optimizado para Qwen2
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        auto sampler = llama_sampler_chain_init(sparams);

        if (!sampler) {
            LOGE("Error: No se pudo crear el sampler");
            llama_free(ctx);
            llama_free_model(model);
            llama_backend_free();
            return JNI_FALSE;
        }

        // Parámetros de sampling optimizados
        llama_sampler_chain_add(sampler,
                                llama_sampler_init_penalties(
                                        64,      // penalty_last_n
                                        1.05f,    // penalty_repeat
                                        0.0f,    // penalty_freq
                                        0.0f     // penalty_present
                                )
        );

        llama_sampler_chain_add(sampler,
                                llama_sampler_init_top_k(40)
        );

        llama_sampler_chain_add(sampler,
                                llama_sampler_init_top_p(0.9f, 1)
        );

        llama_sampler_chain_add(sampler,
                                llama_sampler_init_min_p(0.15f, 1)
        );

        llama_sampler_chain_add(sampler,
                                llama_sampler_init_temp(0.3f)
        );

        llama_sampler_chain_add(sampler,
                                llama_sampler_init_dist(LLAMA_DEFAULT_SEED)
        );

        g_llama_context = std::make_unique<LlamaContext>();
        g_llama_context->model = model;
        g_llama_context->ctx = ctx;
        g_llama_context->sampler = sampler;

        LOGI("Modelo Qwen2 inicializado correctamente");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("Excepción en initModel: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_dd3boh_outertune_viewmodels_LlamaBridge_generateText(
        JNIEnv* env,
        jobject thiz,
        jstring prompt,
        jint maxTokens
) {
    if (!g_llama_context || !g_llama_context->ctx) {
        LOGE("Error: Modelo no inicializado");
        return env->NewStringUTF("Error: Modelo no inicializado");
    }

    try {
        const char* prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
        std::string prompt_str(prompt_cstr);
        env->ReleaseStringUTFChars(prompt, prompt_cstr);

        LOGI("Generando respuesta para prompt de %zu caracteres", prompt_str.length());

        auto ctx = g_llama_context->ctx;
        auto model = g_llama_context->model;
        auto sampler = g_llama_context->sampler;
        const llama_vocab* vocab = llama_model_get_vocab(model);

        // Tokenizar prompt
        int n_ctx = llama_n_ctx(ctx);
        std::vector<llama_token> tokens(n_ctx);

        // add_bos=true para Qwen2 (agrega BOS automáticamente)
        // special=true para procesar <|im_start|> y <|im_end|>
        int32_t n_prompt_tokens = llama_tokenize(
                vocab,
                prompt_str.c_str(),
                prompt_str.length(),
                tokens.data(),
                tokens.size(),
                true,    // add_bos - Qwen2 requiere BOS
                true     // special - parsear tokens especiales ChatML
        );

        if (n_prompt_tokens <= 0) {
            LOGE("Error: No se pudo tokenizar el prompt");
            return env->NewStringUTF("Error al procesar el prompt");
        }

        if (n_prompt_tokens >= n_ctx - maxTokens) {
            LOGE("Error: Prompt demasiado largo (%d tokens)", n_prompt_tokens);
            return env->NewStringUTF("Error: El prompt es demasiado largo");
        }

        tokens.resize(n_prompt_tokens);
        LOGI("Tokens del prompt: %d", n_prompt_tokens);

        // Crear batch para el prompt
        llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt_tokens);

        // Decodificar el prompt
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Error en llama_decode del prompt");
            return env->NewStringUTF("Error al procesar el prompt");
        }

        LOGI("Prompt procesado correctamente, iniciando generación...");

        // Reset del sampler
        llama_sampler_reset(sampler);

        std::string generated_text;
        const int max_tokens_limit = std::min((int)maxTokens, 256);
        int n_generated = 0;

        // Bucle de generación
        for (int i = 0; i < max_tokens_limit; i++) {
            // Sample el siguiente token
            llama_token new_token = llama_sampler_sample(sampler, ctx, -1);

            // Verificar si es un token de fin (EOG)
            if (llama_vocab_is_eog(vocab, new_token)) {
                LOGI("Token EOG encontrado en iteración %d", i);
                break;
            }

            // Aceptar el token en el sampler
            llama_sampler_accept(sampler, new_token);

            // Decodificar token a texto
            char buffer[256];
            int piece_len = llama_token_to_piece(
                    vocab,
                    new_token,
                    buffer,
                    sizeof(buffer),
                    0,
                    true  // special=true para manejar <|im_end|>
            );

            if (piece_len > 0) {
                std::string token_str(buffer, piece_len);

                // Verificar si es el token de fin ChatML
                if (token_str.find("<|im_end|>") != std::string::npos) {
                    LOGI("Encontrado <|im_end|> en iteración %d", i);
                    break;
                }

                generated_text += token_str;
                n_generated++;

                if (n_generated % 10 == 0) {
                    LOGD("Generados %d tokens...", n_generated);
                }
            }

            // Preparar batch con el nuevo token
            batch = llama_batch_get_one(&new_token, 1);

            // Decodificar el nuevo token
            if (llama_decode(ctx, batch) != 0) {
                LOGE("Error en llama_decode durante generación");
                break;
            }
        }

        LOGI("Generación completada: %d tokens generados", n_generated);

        if (generated_text.empty()) {
            return env->NewStringUTF("El modelo no generó ninguna respuesta.");
        }

        return env->NewStringUTF(generated_text.c_str());

    } catch (const std::exception& e) {
        LOGE("Excepción en generateText: %s", e.what());
        return env->NewStringUTF("Error durante generación");
    }
}

JNIEXPORT void JNICALL
Java_com_dd3boh_outertune_viewmodels_LlamaBridge_releaseModel(
        JNIEnv* env,
        jobject thiz
) {
    try {
        if (g_llama_context) {
            g_llama_context.reset();
            llama_backend_free();
            LOGI("Modelo liberado correctamente");
        }
    } catch (const std::exception& e) {
        LOGE("Excepción en releaseModel: %s", e.what());
    }
}

} // extern "C"
