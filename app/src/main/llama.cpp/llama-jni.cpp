#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "LlamaCommon.h"
#include "LlamaConfig.h"
#include "HardwareUtils.h"
#include "LlamaPrompt.h"
#include "LlamaSession.h"

#include "llama.h"

static std::unique_ptr<LlamaContext> g_llama_context;

extern "C" {

JNIEXPORT void JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_initRuntime(
        JNIEnv * env,
        jobject /* thiz */,
        jstring nativeLibDir
) {
    const char * native_lib_dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    const std::string native_lib_dir_str = native_lib_dir != nullptr ? native_lib_dir : "";
    if (native_lib_dir != nullptr) {
        env->ReleaseStringUTFChars(nativeLibDir, native_lib_dir);
    }

    if (g_runtime_initialized) {
        LOGI("Runtime ya inicializado. Backends registrados: %s", getRegisteredBackends().c_str());
        return;
    }

    setAndroidLogCallback();
    LOGI("Cargando backends dinámicos desde: %s", native_lib_dir_str.c_str());
    const size_t registered_before = ggml_backend_reg_count();
    ggml_backend_load_all_from_path(native_lib_dir_str.c_str());
    if (ggml_backend_reg_count() == registered_before) {
        LOGW("No se detectaron backends desde nativeLibraryDir; intentando fallback por soname");
        loadBestCpuBackendBySonameFallback();
    }
    llama_backend_init();

    g_runtime_initialized = true;
    LOGI("Runtime inicializado. Backends registrados: %s", getRegisteredBackends().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_initModel(
        JNIEnv * env,
        jobject /* thiz */,
        jstring modelPath,
        jint contextLength
) {
    if (!g_runtime_initialized) {
        LOGE("initModel llamado antes de initRuntime");
        return JNI_FALSE;
    }

    try {
        const char * path = env->GetStringUTFChars(modelPath, nullptr);
        const std::string model_path = path != nullptr ? path : "";
        if (path != nullptr) {
            env->ReleaseStringUTFChars(modelPath, path);
        }

        if (g_llama_context) {
            LOGW("Limpiando contexto previo antes de cargar un nuevo modelo");
            g_llama_context.reset();
        }

        const ThreadConfig thread_config = detectThreadConfig();
        LOGI("========================================");
        LOGI("Inicializando modelo desde: %s", model_path.c_str());
        LOGI("CPUs online: %d | decode=%d | prefill=%d",
             thread_config.online_cpu_count,
             thread_config.decode_threads,
             thread_config.prefill_threads);
        if (!thread_config.observed_max_freqs_khz.empty()) {
            LOGI("Frecuencias max observadas (kHz): %s | pico=%ld",
                 joinLongs(thread_config.observed_max_freqs_khz).c_str(),
                 thread_config.highest_max_freq_khz);
            if (!thread_config.frequency_groups_summary.empty()) {
                LOGI("Clusters CPU detectados: %s", thread_config.frequency_groups_summary.c_str());
                LOGI("Piso decode=%ld kHz | mayor salto entre clusters=%d%% | prime fusionado=%s",
                     thread_config.decode_floor_freq_khz,
                     thread_config.largest_cluster_drop_pct,
                     thread_config.merged_prime_cluster ? "SI" : "NO");
            }
        } else {
            LOGI("Topologia por frecuencia no disponible; usando fallback homogeneo");
        }
        LOGI("========================================");

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;

        llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (model == nullptr) {
            LOGE("No se pudo cargar el modelo GGUF");
            return JNI_FALSE;
        }

        const int requested_ctx = contextLength > 0
                                  ? static_cast<int>(contextLength)
                                  : Config::DEFAULT_CONTEXT_TOKENS;
        const int model_ctx_train = llama_model_n_ctx_train(model);
        const int max_supported_ctx = model_ctx_train > 0
                                      ? model_ctx_train
                                      : Config::MAX_CONTEXT_TOKENS;
        const int clamped_ctx = std::max(
                Config::MIN_CONTEXT_TOKENS,
                std::min(requested_ctx, std::min(Config::MAX_CONTEXT_TOKENS, max_supported_ctx))
        );

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(clamped_ctx);
        context_params.n_batch = static_cast<uint32_t>(Config::N_BATCH);
        context_params.n_ubatch = static_cast<uint32_t>(Config::N_UBATCH);
        context_params.n_threads = thread_config.decode_threads;
        context_params.n_threads_batch = thread_config.prefill_threads;
        context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

        LOGI("Parámetros de contexto:");
        LOGI("  n_ctx=%u n_batch=%u n_ubatch=%u",
             context_params.n_ctx,
             context_params.n_batch,
             context_params.n_ubatch);
        LOGI("  n_threads=%d n_threads_batch=%d flash_attn=%s",
             context_params.n_threads,
             context_params.n_threads_batch,
             llama_flash_attn_type_name(context_params.flash_attn_type));

        llama_context * ctx = llama_init_from_model(model, context_params);
        if (ctx == nullptr) {
            LOGE("No se pudo crear el contexto llama");
            llama_model_free(model);
            return JNI_FALSE;
        }

        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        if (sampler == nullptr) {
            LOGE("No se pudo crear el sampler");
            llama_free(ctx);
            llama_model_free(model);
            return JNI_FALSE;
        }

        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f)
        );
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.15f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        g_llama_context = std::make_unique<LlamaContext>();
        g_llama_context->model = model;
        g_llama_context->ctx = ctx;
        g_llama_context->sampler = sampler;
        g_llama_context->thread_config = thread_config;
        g_llama_context->conversation_manager =
                std::make_unique<ConversationManager>(static_cast<int>(llama_n_ctx(ctx)));

        if (!prepareSessionState(*g_llama_context)) {
            LOGE("No se pudo preparar la sesion persistente del modelo");
            g_llama_context.reset();
            return JNI_FALSE;
        }

        LOGI("System info: %s", llama_print_system_info());
        LOGI("Backends registrados: %s", getRegisteredBackends().c_str());
        LOGI("✓ Modelo inicializado correctamente con sesión persistente");
        return JNI_TRUE;
    } catch (const std::exception & e) {
        LOGE("Excepción en initModel: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_generateText(
        JNIEnv * env,
        jobject /* thiz */,
        jstring prompt,
        jint maxTokens
) {
    if (!g_llama_context || !g_llama_context->isValid()) {
        return env->NewStringUTF("Error: Modelo no inicializado. Reinicia la aplicacion.");
    }

    try {
        const char * prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
        const std::string raw_prompt = prompt_cstr != nullptr ? prompt_cstr : "";
        if (prompt_cstr != nullptr) {
            env->ReleaseStringUTFChars(prompt, prompt_cstr);
        }

        auto & llama_context = *g_llama_context;
        auto * ctx = llama_context.ctx;
        auto * sampler = llama_context.sampler;
        const llama_vocab * vocab = llama_model_get_vocab(llama_context.model);

        const int n_ctx = static_cast<int>(llama_n_ctx(ctx));
        const int requested_max_tokens = std::max(1, static_cast<int>(maxTokens));
        const int max_user_prompt_tokens = llama_context.conversation_manager->maxUserPromptTokens(
                requested_max_tokens,
                llama_context.empty_user_turn_tokens.size()
        );

        if (max_user_prompt_tokens < 1) {
            LOGE("No hay espacio suficiente para un nuevo turno de usuario");
            return env->NewStringUTF("Error: El contexto disponible es insuficiente para generar.");
        }

        auto fragment = createSafePromptFragment(raw_prompt, vocab, max_user_prompt_tokens);
        std::vector<llama_token> user_turn_tokens;
        if (!tokenizeText(
                vocab,
                buildUserTurnText(fragment.text),
                &user_turn_tokens,
                false,
                true
        )) {
            LOGE("No se pudo tokenizar el turno del usuario");
            return env->NewStringUTF("Error: No se pudo procesar el texto de entrada.");
        }

        const TurnPlan turn_plan = llama_context.conversation_manager->planForTurn(
                user_turn_tokens,
                requested_max_tokens
        );

        if (!turn_plan.can_fit || turn_plan.available_generation_tokens < Config::MIN_GENERATION_TOKENS) {
            LOGE("El turno no cabe en el contexto actual incluso tras truncar historial");
            return env->NewStringUTF(
                    "Error: El mensaje es demasiado largo para este contexto. Reduce su tamaño o reinicia la conversación."
            );
        }

        const int adjusted_max_tokens =
                std::min(requested_max_tokens, turn_plan.available_generation_tokens);

        LOGI("========================================");
        LOGI("Iniciando generación para prompt de %zu caracteres", raw_prompt.size());
        LOGI("Turno tokenizado: %zu | Historial activo: %zu | Generacion max: %d",
             user_turn_tokens.size(),
             llama_context.conversation_manager->getActiveTokenCount(),
             adjusted_max_tokens);
        LOGI("========================================");

        int64_t prefill_us = 0;
        int prompt_tokens_processed = 0;

        if (turn_plan.rebuild_required) {
            if (!replayConversationState(
                    llama_context,
                    turn_plan.next_history_tokens,
                    turn_plan.history_truncated,
                    &prefill_us
            )) {
                recoverConversationState(llama_context);
                return env->NewStringUTF("Error: No se pudo reconstruir el contexto del modelo.");
            }
            prompt_tokens_processed = static_cast<int>(turn_plan.replay_tokens.size());
            LOGW("Historial truncado para mantener compatibilidad con el contexto");
        } else {
            if (!decodeTokenSequence(ctx, user_turn_tokens, "user_turn_prefill", &prefill_us)) {
                recoverConversationState(llama_context);
                return env->NewStringUTF("Error: No se pudo procesar el prompt en el modelo.");
            }
            llama_context.conversation_manager->appendToHistory(user_turn_tokens);
            prompt_tokens_processed = static_cast<int>(user_turn_tokens.size());
        }

        if (prefill_us > 0) {
            const double prefill_tps =
                    (static_cast<double>(prompt_tokens_processed) * 1e6) / static_cast<double>(prefill_us);
            LOGI("Prefill: %d tokens en %.2f ms (%.2f tok/s)",
                 prompt_tokens_processed,
                 prefill_us / 1000.0,
                 prefill_tps);
        }

        llama_sampler_reset(sampler);

        std::string generated_text;
        std::vector<llama_token> generated_history_tokens;
        int generated_tokens = 0;
        int current_context_tokens =
                static_cast<int>(llama_context.conversation_manager->getActiveTokenCount());
        std::string stop_reason = "completed";

        const int64_t decode_started_at = llama_time_us();

        for (int i = 0; i < adjusted_max_tokens; ++i) {
            if (current_context_tokens >= n_ctx - Config::SAFETY_MARGIN) {
                stop_reason = "context_limit";
                break;
            }

            const llama_token new_token = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, new_token)) {
                stop_reason = "eog";
                break;
            }

            llama_sampler_accept(sampler, new_token);

            char piece_buffer[256];
            const int piece_len = llama_token_to_piece(
                    vocab,
                    new_token,
                    piece_buffer,
                    static_cast<int32_t>(sizeof(piece_buffer)),
                    0,
                    true
            );

            const std::string token_piece =
                    piece_len > 0 ? std::string(piece_buffer, static_cast<size_t>(piece_len)) : "";

            if (!decodeSingleToken(ctx, new_token, "decode_token")) {
                stop_reason = "decode_error";
                recoverConversationState(llama_context);
                return env->NewStringUTF("Error: Fallo interno durante la generacion.");
            }

            generated_history_tokens.push_back(new_token);
            ++current_context_tokens;

            if (token_piece.find("<|im_end|>") != std::string::npos) {
                stop_reason = "im_end";
                break;
            }

            generated_text += token_piece;
            ++generated_tokens;

            if (generated_tokens % Config::LOG_INTERVAL == 0) {
                LOGD("Decode: %d tokens generados (ctx: %d/%d)",
                     generated_tokens,
                     current_context_tokens,
                     n_ctx);
            }
        }

        if (stop_reason != "im_end") {
            if (!closeAssistantTurn(
                    llama_context,
                    &generated_history_tokens,
                    &current_context_tokens
            )) {
                recoverConversationState(llama_context);
                return env->NewStringUTF("Error: Fallo al cerrar el turno del asistente.");
            }
        }

        llama_context.conversation_manager->appendToHistory(generated_history_tokens);

        const int64_t decode_us = llama_time_us() - decode_started_at;
        if (decode_us > 0 && generated_tokens > 0) {
            const double decode_tps =
                    (static_cast<double>(generated_tokens) * 1e6) / static_cast<double>(decode_us);
            LOGI("Decode: %d tokens en %.2f ms (%.2f tok/s), parada=%s",
                 generated_tokens,
                 decode_us / 1000.0,
                 decode_tps,
                 stop_reason.c_str());
        } else {
            LOGI("Decode: 0 tokens generados, parada=%s", stop_reason.c_str());
        }

        if (generated_text.empty()) {
            LOGW("El modelo no generó contenido visible");
            return env->NewStringUTF(
                    "El modelo no pudo generar una respuesta. Intenta reformular tu mensaje."
            );
        }

        if (fragment.is_truncated) {
            generated_text =
                    "\n\n[Nota: el mensaje fue truncado por limite de contexto]\n\n" + generated_text;
        }

        return env->NewStringUTF(generated_text.c_str());
    } catch (const std::exception & e) {
        LOGE("Excepción en generateText: %s", e.what());
        if (g_llama_context && g_llama_context->isValid()) {
            recoverConversationState(*g_llama_context);
        }
        return env->NewStringUTF("Error crítico durante la generación. Intenta nuevamente.");
    }
}

JNIEXPORT void JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_releaseModel(
        JNIEnv * /* env */,
        jobject /* thiz */
) {
    try {
        LOGI("Liberando recursos del modelo...");
        if (g_llama_context) {
            g_llama_context.reset();
        }

        if (g_runtime_initialized) {
            llama_backend_free();
            g_runtime_initialized = false;
        }

        LOGI("Recursos del modelo y runtime liberados");
    } catch (const std::exception & e) {
        LOGE("Excepción en releaseModel: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_clearConversation(
        JNIEnv * /* env */,
        jobject /* thiz */
) {
    try {
        if (!g_llama_context || !g_llama_context->isValid()) {
            LOGW("clearConversation llamado sin modelo activo");
            return;
        }

        if (resetConversationState(*g_llama_context)) {
            LOGI("Sesión de conversación reiniciada");
        } else {
            LOGE("No se pudo reiniciar la conversación nativa");
        }
    } catch (const std::exception & e) {
        LOGE("Excepción en clearConversation: %s", e.what());
    }
}

JNIEXPORT jstring JNICALL
Java_com_kraata_harmony_viewmodels_LlamaBridge_getContextInfo(
        JNIEnv * env,
        jobject /* thiz */
) {
    try {
        if (!g_llama_context || !g_llama_context->isValid()) {
            return env->NewStringUTF("Modelo no inicializado");
        }

        const auto & llama_context = *g_llama_context;
        const int n_ctx = static_cast<int>(llama_n_ctx(llama_context.ctx));
        const int n_batch = static_cast<int>(llama_n_batch(llama_context.ctx));
        const size_t active_tokens = llama_context.conversation_manager->getActiveTokenCount();

        char info_buffer[768];
        snprintf(
                info_buffer,
                sizeof(info_buffer),
                "Contexto:          %d tokens\n"
                "Batch prefill:     %d tokens\n"
                "Hilos decode:      %d\n"
                "Hilos prefill:     %d\n"
                "Flash Attn:        %s\n"
                "System tokens:     %zu\n"
                "History tokens:    %zu\n"
                "Active tokens:     %zu\n"
                "Estado:            %s\n"
                "Disponible:        ~%d tokens\n"
                "Backends:          %s",
                n_ctx,
                n_batch,
                llama_context.thread_config.decode_threads,
                llama_context.thread_config.prefill_threads,
                llama_flash_attn_type_name(LLAMA_FLASH_ATTN_TYPE_ENABLED),
                llama_context.conversation_manager->getSystemSize(),
                llama_context.conversation_manager->getHistorySize(),
                active_tokens,
                llama_context.conversation_manager->wasTruncated() ? "Truncado" : "Completo",
                std::max(0, n_ctx - static_cast<int>(active_tokens) - Config::SAFETY_MARGIN),
                getRegisteredBackends().c_str()
        );

        return env->NewStringUTF(info_buffer);
    } catch (const std::exception & e) {
        LOGE("Excepción en getContextInfo: %s", e.what());
        return env->NewStringUTF("Error al obtener informacion");
    }
}

} // extern "C"
