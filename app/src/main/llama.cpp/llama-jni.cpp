#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ============================================================================
// CONFIGURACIÓN Y CONSTANTES
// ============================================================================

namespace Config {
    constexpr int SAFETY_MARGIN = 128;           // Margen de seguridad en tokens
    constexpr int MIN_GENERATION_TOKENS = 50;   // Mínimo tokens para generar
    constexpr int LOG_INTERVAL = 10;            // Intervalo de logging
    constexpr float TRUNCATE_THRESHOLD = 0.7f;  // Umbral para truncamiento inteligente
    constexpr int DEFAULT_CONTEXT_TOKENS = 4096;
    constexpr int MIN_CONTEXT_TOKENS = 1024;
    constexpr int MAX_CONTEXT_TOKENS = 8192;
    constexpr int DEFAULT_BATCH_TOKENS = 512;

}

// ============================================================================
// GESTIÓN DE CONVERSACIÓN CON VENTANA DESLIZANTE
// ============================================================================

/**
 * Administra el historial de conversación con estrategia de ventana deslizante.
 * Previene desbordamiento de contexto manteniendo solo los mensajes más recientes.
 */
class ConversationManager {
private:
    std::vector<llama_token> conversation_history;
    int max_context_tokens;
    int reserved_for_generation;
    bool history_truncated;

public:
    explicit ConversationManager(int ctx_size, int reserve_tokens = 256)
            : max_context_tokens(ctx_size - Config::SAFETY_MARGIN),
              reserved_for_generation(reserve_tokens),
              history_truncated(false) {
        LOGI("ConversationManager creado: ctx=%d, reserva=%d",
             max_context_tokens, reserved_for_generation);
    }

    /**
     * Prepara el contexto combinando historial y nuevo prompt.
     * Aplica truncamiento inteligente si es necesario.
     */
    std::vector<llama_token> prepareContext(
            const std::vector<llama_token> &new_prompt_tokens
    ) {
        std::vector<llama_token> result;

        int available = max_context_tokens - reserved_for_generation;
        int total_needed = conversation_history.size() + new_prompt_tokens.size();

        LOGD("Preparando contexto: historial=%zu, nuevo=%zu, disponible=%d",
             conversation_history.size(), new_prompt_tokens.size(), available);

        if (total_needed <= available) {
            // Todo cabe, agregar normalmente
            result = conversation_history;
            result.insert(result.end(),
                          new_prompt_tokens.begin(),
                          new_prompt_tokens.end());
            LOGD("Contexto completo: %zu tokens", result.size());
        } else {
            // Aplicar ventana deslizante
            int tokens_to_keep = available - new_prompt_tokens.size();

            if (tokens_to_keep > 0 && conversation_history.size() > tokens_to_keep) {
                // Mantener solo los tokens más recientes del historial
                result.assign(
                        conversation_history.end() - tokens_to_keep,
                        conversation_history.end()
                );
                history_truncated = true;
                LOGW("Historial truncado: %zu -> %d tokens",
                     conversation_history.size(), tokens_to_keep);
            }

            // Agregar nuevo prompt
            result.insert(result.end(),
                          new_prompt_tokens.begin(),
                          new_prompt_tokens.end());

            LOGI("Contexto con ventana deslizante: %zu tokens totales", result.size());
        }

        return result;
    }

    void addToHistory(const std::vector<llama_token> &tokens) {
        conversation_history.insert(
                conversation_history.end(),
                tokens.begin(),
                tokens.end()
        );
        LOGD("Historial actualizado: %zu tokens totales", conversation_history.size());
    }

    void clear() {
        conversation_history.clear();
        history_truncated = false;
        LOGI("Historial de conversación limpiado");
    }

    bool wasTruncated() const { return history_truncated; }

    size_t getHistorySize() const { return conversation_history.size(); }
};

// ============================================================================
// FRAGMENTACIÓN INTELIGENTE DE PROMPTS
// ============================================================================

/**
 * Utilidad para manejar prompts que exceden el límite de contexto.
 * Implementa truncamiento inteligente en puntos semánticamente apropiados.
 */
class PromptFragmenter {
public:
    struct Fragment {
        std::string text;
        int estimated_tokens;
        bool is_truncated;

        Fragment() : estimated_tokens(0), is_truncated(false) {}
    };

    /**
     * Crea un fragmento seguro del prompt que cabe en el contexto disponible.
     */
    static Fragment createSafeFragment(
            const std::string &prompt,
            const llama_vocab *vocab,
            int max_tokens
    ) {
        Fragment fragment;

        // Estimación rápida (aproximadamente 4 caracteres por token en promedio)
        int estimated_tokens = prompt.length() / 4;

        if (estimated_tokens <= max_tokens) {
            fragment.text = prompt;
            fragment.estimated_tokens = estimated_tokens;
            fragment.is_truncated = false;
            LOGD("Prompt cabe completo: ~%d tokens estimados", estimated_tokens);
            return fragment;
        }

        // Necesitamos truncar
        LOGW("Prompt demasiado largo: ~%d tokens, máximo: %d",
             estimated_tokens, max_tokens);

        int target_chars = max_tokens * 4;
        std::string truncated = prompt.substr(0, std::min(target_chars, (int) prompt.length()));

        // Buscar punto de corte inteligente
        size_t cut_point = findIntelligentCutPoint(truncated, target_chars);
        truncated = truncated.substr(0, cut_point);

        fragment.text = truncated + "\n\n[...contenido truncado por límite de contexto...]";
        fragment.is_truncated = true;

        // Verificar tokenización real
        std::vector<llama_token> tokens(max_tokens + 100);
        int32_t actual_tokens = llama_tokenize(
                vocab,
                fragment.text.c_str(),
                fragment.text.length(),
                tokens.data(),
                tokens.size(),
                true,
                true
        );

        fragment.estimated_tokens = actual_tokens > 0 ? actual_tokens : 0;
        LOGI("Prompt truncado: %d tokens finales", fragment.estimated_tokens);

        return fragment;
    }

private:
    /**
     * Encuentra un punto de corte semánticamente apropiado.
     * Prioriza: punto final > salto de línea > espacio
     */
    static size_t findIntelligentCutPoint(const std::string &text, int target_pos) {
        size_t min_acceptable = target_pos * Config::TRUNCATE_THRESHOLD;

        // Buscar el último punto, signo de exclamación o interrogación
        size_t last_sentence = text.find_last_of(".!?", target_pos);
        if (last_sentence != std::string::npos && last_sentence > min_acceptable) {
            return last_sentence + 1;
        }

        // Buscar el último salto de línea doble (párrafo)
        size_t last_paragraph = text.rfind("\n\n", target_pos);
        if (last_paragraph != std::string::npos && last_paragraph > min_acceptable) {
            return last_paragraph + 2;
        }

        // Buscar el último salto de línea
        size_t last_line = text.find_last_of("\n", target_pos);
        if (last_line != std::string::npos && last_line > min_acceptable) {
            return last_line + 1;
        }

        // Buscar el último espacio
        size_t last_space = text.find_last_of(" ", target_pos);
        if (last_space != std::string::npos && last_space > min_acceptable) {
            return last_space;
        }

        // Si todo falla, cortar en el target
        return target_pos;
    }
};

// ============================================================================
// CONTEXTO LLAMA CON RAII
// ============================================================================

/**
 * Encapsula el contexto de Llama con gestión automática de recursos (RAII).
 */
struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::unique_ptr<ConversationManager> conversation_manager;

    ~LlamaContext() {
        cleanup();
    }

    void cleanup() {
        if (sampler) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_free_model(model);
            model = nullptr;
        }
        conversation_manager.reset();
    }

    bool isValid() const {
        return model != nullptr && ctx != nullptr && sampler != nullptr;
    }
};

static std::unique_ptr<LlamaContext> g_llama_context;

// ============================================================================
// FUNCIONES JNI
// ============================================================================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_initModel(
        JNIEnv *env,
        jobject thiz,
        jstring modelPath,
        jint contextLength
) {
    try {
        const char *path = env->GetStringUTFChars(modelPath, nullptr);
        LOGI("========================================");
        LOGI("Inicializando modelo Qwen2 desde: %s", path);
        LOGI("========================================");

        // Limpiar contexto previo si existe
        if (g_llama_context) {
            LOGW("Limpiando contexto previo...");
            g_llama_context.reset();
        }

        llama_backend_init();

        // Parámetros del modelo optimizados
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;  // CPU only para compatibilidad

        auto model = llama_load_model_from_file(path, mparams);
        env->ReleaseStringUTFChars(modelPath, path);

        if (!model) {
            LOGE("❌ Error: No se pudo cargar el modelo");
            llama_backend_free();
            return JNI_FALSE;
        }

        // Parámetros del contexto optimizados para móvil
        const int requested_ctx = contextLength > 0
                                  ? static_cast<int>(contextLength)
                                  : Config::DEFAULT_CONTEXT_TOKENS;
        const int model_ctx_train = llama_model_n_ctx_train(model);
        const int max_supported_ctx =
                model_ctx_train > 0 ? model_ctx_train : Config::MAX_CONTEXT_TOKENS;
        const int clamped_ctx = std::max(
                Config::MIN_CONTEXT_TOKENS,
                std::min(requested_ctx, std::min(Config::MAX_CONTEXT_TOKENS, max_supported_ctx))
        );

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = clamped_ctx;
        // n_batch grande = prefill del prompt más rápido (procesa más tokens a la vez)
        // n_ubatch menor = decode más eficiente en memoria (1 token por vez en generación)
        cparams.n_batch = std::min(cparams.n_ctx,
                                   static_cast<uint32_t>(Config::DEFAULT_BATCH_TOKENS));
        cparams.n_ubatch = 128; // decode eficiente: menos memoria pressure por iteración
        cparams.n_threads = 4;          // Threads para inferencia
        cparams.n_threads_batch = 4;    // Threads para batch

        LOGI("Contexto solicitado=%d, contexto aplicado=%u, n_ctx_train=%d, n_batch=%u",
             requested_ctx, cparams.n_ctx, model_ctx_train, cparams.n_batch);

        auto ctx = llama_new_context_with_model(model, cparams);
        if (!ctx) {
            LOGE("❌ Error: No se pudo crear el contexto");
            llama_free_model(model);
            llama_backend_free();
            return JNI_FALSE;
        }

        LOGI("✓ Contexto creado: %d tokens disponibles", llama_n_ctx(ctx));

        // Crear sampler chain optimizado para Qwen2
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        auto sampler = llama_sampler_chain_init(sparams);

        if (!sampler) {
            LOGE("❌ Error: No se pudo crear el sampler");
            llama_free(ctx);
            llama_free_model(model);
            llama_backend_free();
            return JNI_FALSE;
        }

        // Configurar parámetros de sampling optimizados
        llama_sampler_chain_add(sampler,
                                llama_sampler_init_penalties(
                                        64,      // penalty_last_n
                                        1.05f,   // penalty_repeat
                                        0.0f,    // penalty_freq
                                        0.0f     // penalty_present
                                )
        );

        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.15f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // Inicializar contexto global
        g_llama_context = std::make_unique<LlamaContext>();
        g_llama_context->model = model;
        g_llama_context->ctx = ctx;
        g_llama_context->sampler = sampler;

        // Crear gestor de conversación
        g_llama_context->conversation_manager =
                std::make_unique<ConversationManager>(llama_n_ctx(ctx), 256);

        LOGI("========================================");
        LOGI("✓ Modelo Qwen2 inicializado correctamente");
        LOGI("========================================");
        return JNI_TRUE;

    } catch (const std::exception &e) {
        LOGE("❌ Excepción en initModel: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_generateText(
        JNIEnv *env,
        jobject thiz,
        jstring prompt,
        jint maxTokens
) {
    if (!g_llama_context || !g_llama_context->isValid()) {
        LOGE("❌ Error: Modelo no inicializado");
        return env->NewStringUTF(
                "Error: Modelo no inicializado. Por favor, reinicia la aplicación.");
    }

    try {
        const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
        std::string prompt_str(prompt_cstr);
        env->ReleaseStringUTFChars(prompt, prompt_cstr);

        LOGI("========================================");
        LOGI("Iniciando generación");
        LOGI("Prompt: %zu caracteres", prompt_str.length());
        LOGI("Max tokens solicitados: %d", maxTokens);
        LOGI("========================================");

        auto ctx = g_llama_context->ctx;
        auto model = g_llama_context->model;
        auto sampler = g_llama_context->sampler;
        const llama_vocab *vocab = llama_model_get_vocab(model);

        const int n_ctx = llama_n_ctx(ctx);
        const int n_batch = llama_n_batch(ctx);
        const int requested_max_tokens = std::max(1, static_cast<int>(maxTokens));

        if (n_batch <= 0) {
            LOGE("❌ n_batch inválido: %d", n_batch);
            return env->NewStringUTF("Error: Configuración inválida del modelo.");
        }

        // Reiniciar memoria KV para cada inferencia completa.
        // llama_memory_clear(llama_get_memory(ctx), false);

        // ====================================================================
        // FASE 1: TOKENIZACIÓN Y VALIDACIÓN CON FRAGMENTACIÓN
        // ====================================================================

        // Calcular espacio máximo disponible para el prompt
        int max_prompt_space = n_ctx - requested_max_tokens - Config::SAFETY_MARGIN;
        if (max_prompt_space < Config::MIN_GENERATION_TOKENS) {
            LOGE("❌ Espacio insuficiente para prompt: max_prompt_space=%d", max_prompt_space);
            return env->NewStringUTF(
                    "Error: La configuración de generación excede el contexto disponible."
            );
        }

        // Crear fragmento seguro si es necesario
        auto fragment = PromptFragmenter::createSafeFragment(
                prompt_str, vocab, max_prompt_space
        );

        std::string final_prompt = fragment.text;

        // Tokenizar el prompt (posiblemente fragmentado)
        std::vector<llama_token> prompt_tokens(n_ctx);
        int32_t n_prompt_tokens = llama_tokenize(
                vocab,
                final_prompt.c_str(),
                final_prompt.length(),
                prompt_tokens.data(),
                prompt_tokens.size(),
                true,    // add_bos - Qwen2 requiere BOS
                true     // special - parsear tokens especiales ChatML
        );

        if (n_prompt_tokens <= 0) {
            LOGE("❌ Error: No se pudo tokenizar el prompt");
            return env->NewStringUTF("Error: No se pudo procesar el texto de entrada.");
        }

        prompt_tokens.resize(n_prompt_tokens);
        LOGI("✓ Tokens del prompt: %d", n_prompt_tokens);

        // ====================================================================
        // FASE 2: VALIDACIÓN DE ESPACIO DISPONIBLE
        // ====================================================================

        const int available_tokens = n_ctx - n_prompt_tokens - Config::SAFETY_MARGIN;

        if (available_tokens < Config::MIN_GENERATION_TOKENS) {
            LOGE("❌ Espacio insuficiente: disponible=%d, mínimo=%d",
                 available_tokens, Config::MIN_GENERATION_TOKENS);
            return env->NewStringUTF(
                    "Error: El mensaje es demasiado largo para este modelo. "
                    "Por favor, reduce el tamaño del mensaje o inicia una nueva conversación."
            );
        }

        // Ajustar maxTokens dinámicamente
        const int adjusted_max_tokens = std::min(requested_max_tokens, available_tokens);

        LOGI("📊 Estadísticas de contexto:");
        LOGI("  - Contexto total: %d tokens", n_ctx);
        LOGI("  - Prompt: %d tokens", n_prompt_tokens);
        LOGI("  - Disponible para generación: %d tokens", available_tokens);
        LOGI("  - Generación ajustada: %d tokens", adjusted_max_tokens);
        LOGI("  - Margen de seguridad: %d tokens", Config::SAFETY_MARGIN);

        // ====================================================================
        // FASE 3: DECODIFICACIÓN DEL PROMPT
        // ====================================================================

        int prompt_offset = 0;
        while (prompt_offset < n_prompt_tokens) {
            const int chunk_size = std::min(n_batch, n_prompt_tokens - prompt_offset);
            llama_batch batch = llama_batch_get_one(prompt_tokens.data() + prompt_offset,
                                                    chunk_size);

            const int decode_status = llama_decode(ctx, batch);
            if (decode_status != 0) {
                LOGE("❌ Error en llama_decode del prompt (status=%d, chunk=%d, offset=%d)",
                     decode_status, chunk_size, prompt_offset);
                return env->NewStringUTF("Error: No se pudo procesar el prompt en el modelo.");
            }

            prompt_offset += chunk_size;
        }

        LOGI("✓ Prompt decodificado exitosamente");

        // ====================================================================
        // FASE 4: GENERACIÓN CON MONITOREO EN TIEMPO REAL
        // ====================================================================

        llama_sampler_reset(sampler);

        std::string generated_text;
        int n_generated = 0;
        int n_current_context = n_prompt_tokens;
        bool early_stop = false;
        std::string stop_reason = "completed";

        LOGI("🔄 Iniciando bucle de generación...");

        for (int i = 0; i < adjusted_max_tokens; i++) {
            // Monitoreo de seguridad del contexto
            if (n_current_context >= n_ctx - Config::SAFETY_MARGIN) {
                LOGW("⚠️ Acercándose al límite de contexto: %d/%d",
                     n_current_context, n_ctx);
                stop_reason = "context_limit";
                early_stop = true;
                break;
            }

            // Sample del siguiente token
            llama_token new_token = llama_sampler_sample(sampler, ctx, -1);

            // Verificar token EOG (End of Generation)
            if (llama_vocab_is_eog(vocab, new_token)) {
                LOGI("✓ Token EOG encontrado en iteración %d", i);
                stop_reason = "eog";
                break;
            }

            // Aceptar el token
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

                // Verificar token de fin ChatML
                if (token_str.find("<|im_end|>") != std::string::npos) {
                    LOGI("✓ Token <|im_end|> encontrado en iteración %d", i);
                    stop_reason = "im_end";
                    break;
                }

                generated_text += token_str;
                n_generated++;
                n_current_context++;

                // Logging periódico
                if (n_generated % Config::LOG_INTERVAL == 0) {
                    LOGD("📝 Progreso: %d tokens generados (contexto: %d/%d)",
                         n_generated, n_current_context, n_ctx);
                }
            }

            // Preparar batch con el nuevo token
            llama_batch batch = llama_batch_get_one(&new_token, 1);

            // Decodificar el nuevo token
            if (llama_decode(ctx, batch) != 0) {
                LOGE("❌ Error en llama_decode durante generación en token %d", i);
                stop_reason = "decode_error";
                early_stop = true;
                break;
            }
        }

        // ====================================================================
        // FASE 5: FINALIZACIÓN Y REPORTE
        // ====================================================================

        LOGI("========================================");
        LOGI("✓ Generación completada");
        LOGI("  - Tokens generados: %d", n_generated);
        LOGI("  - Razón de parada: %s", stop_reason.c_str());
        LOGI("  - Caracteres generados: %zu", generated_text.length());
        LOGI("  - Contexto final: %d/%d tokens", n_current_context, n_ctx);
        LOGI("========================================");

        if (generated_text.empty()) {
            LOGW("⚠️ El modelo no generó respuesta");
            return env->NewStringUTF(
                    "El modelo no pudo generar una respuesta. "
                    "Intenta reformular tu mensaje."
            );
        }

        // Agregar advertencia si hubo truncamiento
        if (fragment.is_truncated) {
            std::string warning = "\n\n[Nota: El mensaje fue truncado debido al límite de contexto]";
            generated_text = warning + generated_text;
        }

        return env->NewStringUTF(generated_text.c_str());

    } catch (const std::exception &e) {
        LOGE("❌ Excepción en generateText: %s", e.what());
        return env->NewStringUTF(
                "Error crítico durante la generación. Por favor, intenta nuevamente.");
    }
}

JNIEXPORT void JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_releaseModel(
        JNIEnv *env,
        jobject thiz
) {
    try {
        LOGI("========================================");
        LOGI("Liberando recursos del modelo...");

        if (g_llama_context) {
            g_llama_context.reset();
            llama_backend_free();
            LOGI("✓ Recursos liberados correctamente");
        } else {
            LOGW("⚠️ No había modelo cargado");
        }

        LOGI("========================================");
    } catch (const std::exception &e) {
        LOGE("❌ Excepción en releaseModel: %s", e.what());
    }
}

/**
 * Limpia el historial de conversación.
 * Útil para iniciar una nueva conversación sin reiniciar el modelo.
 */
JNIEXPORT void JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_clearConversation(
        JNIEnv *env,
        jobject thiz
) {
    try {
        if (g_llama_context && g_llama_context->conversation_manager) {
            g_llama_context->conversation_manager->clear();
            LOGI("✓ Historial de conversación limpiado");
        }
    } catch (const std::exception &e) {
        LOGE("❌ Excepción en clearConversation: %s", e.what());
    }
}

/**
 * Obtiene información sobre el estado actual del contexto.
 * Útil para debugging y monitoreo.
 */
JNIEXPORT jstring JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_getContextInfo(
        JNIEnv *env,
        jobject thiz
) {
    try {
        if (!g_llama_context || !g_llama_context->isValid()) {
            return env->NewStringUTF("Modelo no inicializado");
        }

        auto ctx = g_llama_context->ctx;
        auto conv_manager = g_llama_context->conversation_manager.get();

        int n_ctx = llama_n_ctx(ctx);
        size_t history_size = conv_manager ? conv_manager->getHistorySize() : 0;
        bool truncated = conv_manager ? conv_manager->wasTruncated() : false;

        char info_buffer[512];
        snprintf(info_buffer, sizeof(info_buffer),
                 "Contexto: %d tokens\n"
                 "Historial: %zu tokens\n"
                 "Estado: %s\n"
                 "Disponible: ~%d tokens",
                 n_ctx,
                 history_size,
                 truncated ? "Truncado" : "Completo",
                 n_ctx - (int) history_size - Config::SAFETY_MARGIN
        );

        return env->NewStringUTF(info_buffer);

    } catch (const std::exception &e) {
        LOGE("❌ Excepción en getContextInfo: %s", e.what());
        return env->NewStringUTF("Error al obtener información");
    }
}

} // extern "C"
