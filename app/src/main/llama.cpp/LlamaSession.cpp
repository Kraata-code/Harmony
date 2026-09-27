#include "LlamaSession.h"

#include <algorithm>
#include <utility>

#include "LlamaCommon.h"
#include "LlamaConfig.h"
#include "LlamaPrompt.h"

ConversationManager::ConversationManager(int context_capacity_tokens)
        : context_capacity_tokens_(context_capacity_tokens) {}

void ConversationManager::setSystemTokens(std::vector<llama_token> tokens) {
    system_tokens_ = std::move(tokens);
    clearHistory();
}

void ConversationManager::clearHistory() {
    history_tokens_.clear();
    history_truncated_ = false;
}

void ConversationManager::replaceHistory(std::vector<llama_token> tokens, bool truncated) {
    history_tokens_ = std::move(tokens);
    history_truncated_ = history_truncated_ || truncated;
}

void ConversationManager::appendToHistory(const std::vector<llama_token> & tokens) {
    history_tokens_.insert(history_tokens_.end(), tokens.begin(), tokens.end());
}

size_t ConversationManager::getHistorySize() const {
    return history_tokens_.size();
}

size_t ConversationManager::getSystemSize() const {
    return system_tokens_.size();
}

size_t ConversationManager::getActiveTokenCount() const {
    return system_tokens_.size() + history_tokens_.size();
}

bool ConversationManager::wasTruncated() const {
    return history_truncated_;
}

int ConversationManager::maxUserPromptTokens(
        int reserve_generation_tokens,
        size_t turn_wrapper_tokens
) const {
    return context_capacity_tokens_
           - Config::SAFETY_MARGIN
           - reserve_generation_tokens
           - static_cast<int>(system_tokens_.size() + turn_wrapper_tokens)
           - Config::TRUNCATION_NOTE_HEADROOM;
}

TurnPlan ConversationManager::planForTurn(
        const std::vector<llama_token> & turn_tokens,
        int reserve_generation_tokens
) const {
    TurnPlan plan;

    const int prompt_budget = context_capacity_tokens_ - Config::SAFETY_MARGIN - reserve_generation_tokens;
    const int available_history_tokens = prompt_budget
                                         - static_cast<int>(system_tokens_.size())
                                         - static_cast<int>(turn_tokens.size());

    if (available_history_tokens < 0) {
        return plan;
    }

    plan.can_fit = true;

    std::vector<llama_token> kept_history;
    if (static_cast<int>(history_tokens_.size()) > available_history_tokens) {
        if (available_history_tokens > 0) {
            kept_history.assign(
                    history_tokens_.end() - available_history_tokens,
                    history_tokens_.end()
            );
        }
        plan.rebuild_required = true;
        plan.history_truncated = true;
    } else {
        kept_history = history_tokens_;
    }

    plan.next_history_tokens = kept_history;
    plan.next_history_tokens.insert(
            plan.next_history_tokens.end(),
            turn_tokens.begin(),
            turn_tokens.end()
    );

    plan.available_generation_tokens = context_capacity_tokens_
                                       - Config::SAFETY_MARGIN
                                       - static_cast<int>(system_tokens_.size())
                                       - static_cast<int>(plan.next_history_tokens.size());

    if (plan.rebuild_required) {
        plan.replay_tokens = system_tokens_;
        plan.replay_tokens.insert(
                plan.replay_tokens.end(),
                plan.next_history_tokens.begin(),
                plan.next_history_tokens.end()
        );
    }

    return plan;
}

const std::vector<llama_token> & ConversationManager::systemTokens() const {
    return system_tokens_;
}

LlamaContext::~LlamaContext() {
    cleanup();
}

void LlamaContext::cleanup() {
    if (sampler != nullptr) {
        llama_sampler_free(sampler);
        sampler = nullptr;
    }
    if (ctx != nullptr) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    conversation_manager.reset();
    empty_user_turn_tokens.clear();
    assistant_close_tokens.clear();
    thread_config = ThreadConfig{};
}

bool LlamaContext::isValid() const {
    return model != nullptr && ctx != nullptr && sampler != nullptr && conversation_manager != nullptr;
}

bool decodeTokenSequence(
        llama_context * ctx,
        const std::vector<llama_token> & tokens,
        const char * stage,
        int64_t * elapsed_us
) {
    if (tokens.empty()) {
        if (elapsed_us != nullptr) {
            *elapsed_us = 0;
        }
        return true;
    }

    const int n_batch = std::max(1, static_cast<int>(llama_n_batch(ctx)));
    const int64_t started_at = llama_time_us();

    int offset = 0;
    while (offset < static_cast<int>(tokens.size())) {
        const int chunk_size = std::min(n_batch, static_cast<int>(tokens.size()) - offset);
        llama_batch batch = llama_batch_get_one(
                const_cast<llama_token *>(tokens.data()) + offset,
                chunk_size
        );

        const int status = llama_decode(ctx, batch);
        if (status != 0) {
            LOGE("llama_decode fallo en %s (status=%d, chunk=%d, offset=%d)",
                 stage, status, chunk_size, offset);
            return false;
        }

        offset += chunk_size;
    }

    if (elapsed_us != nullptr) {
        *elapsed_us = llama_time_us() - started_at;
    }
    return true;
}

bool decodeSingleToken(llama_context * ctx, llama_token token, const char * stage) {
    llama_batch batch = llama_batch_get_one(&token, 1);
    const int status = llama_decode(ctx, batch);
    if (status != 0) {
        LOGE("llama_decode fallo en %s para token unico (status=%d)", stage, status);
        return false;
    }
    return true;
}

bool replayConversationState(
        LlamaContext & llama_context,
        const std::vector<llama_token> & history_tokens,
        bool history_truncated,
        int64_t * elapsed_us
) {
    llama_memory_clear(llama_get_memory(llama_context.ctx), false);
    llama_context.conversation_manager->clearHistory();

    const int64_t started_at = llama_time_us();

    if (!decodeTokenSequence(
            llama_context.ctx,
            llama_context.conversation_manager->systemTokens(),
            "system_prompt"
    )) {
        return false;
    }

    if (!history_tokens.empty() &&
        !decodeTokenSequence(llama_context.ctx, history_tokens, "history_replay")) {
        return false;
    }

    llama_context.conversation_manager->replaceHistory(history_tokens, history_truncated);

    if (elapsed_us != nullptr) {
        *elapsed_us = llama_time_us() - started_at;
    }

    return true;
}

bool resetConversationState(LlamaContext & llama_context) {
    int64_t system_prefill_us = 0;
    if (!replayConversationState(
            llama_context,
            {},
            false,
            &system_prefill_us
    )) {
        return false;
    }

    if (system_prefill_us > 0) {
        const double system_tps =
                (static_cast<double>(llama_context.conversation_manager->systemTokens().size()) * 1e6)
                / static_cast<double>(system_prefill_us);
        LOGI("System prompt prefill: %zu tokens en %.2f ms (%.2f tok/s)",
             llama_context.conversation_manager->systemTokens().size(),
             system_prefill_us / 1000.0,
             system_tps);
    }

    return true;
}

bool prepareSessionState(LlamaContext & llama_context) {
    const llama_vocab * vocab = llama_model_get_vocab(llama_context.model);

    std::vector<llama_token> system_tokens;
    std::vector<llama_token> empty_user_turn_tokens;
    std::vector<llama_token> assistant_close_tokens;

    if (!tokenizeText(vocab, buildSystemTurnText(Config::kSystemPrompt), &system_tokens, true, true) ||
        !tokenizeText(vocab, buildUserTurnText(""), &empty_user_turn_tokens, false, true) ||
        !tokenizeText(vocab, Config::kTurnClose, &assistant_close_tokens, false, true)) {
        LOGE("No se pudieron tokenizar los prompts estaticos de la sesion");
        return false;
    }

    llama_context.empty_user_turn_tokens = std::move(empty_user_turn_tokens);
    llama_context.assistant_close_tokens = std::move(assistant_close_tokens);
    llama_context.conversation_manager->setSystemTokens(std::move(system_tokens));

    return resetConversationState(llama_context);
}

bool recoverConversationState(LlamaContext & llama_context) {
    LOGW("Reiniciando sesion nativa tras un fallo fatal");
    return resetConversationState(llama_context);
}

bool closeAssistantTurn(
        LlamaContext & llama_context,
        std::vector<llama_token> * stored_tokens,
        int * context_token_count
) {
    if (llama_context.assistant_close_tokens.empty()) {
        return true;
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(llama_context.ctx));
    if (*context_token_count + static_cast<int>(llama_context.assistant_close_tokens.size())
        >= n_ctx - Config::SAFETY_MARGIN) {
        LOGW("No hay espacio para cerrar el turno del asistente");
        return true;
    }

    if (!decodeTokenSequence(llama_context.ctx, llama_context.assistant_close_tokens, "assistant_close")) {
        return false;
    }

    stored_tokens->insert(
            stored_tokens->end(),
            llama_context.assistant_close_tokens.begin(),
            llama_context.assistant_close_tokens.end()
    );
    *context_token_count += static_cast<int>(llama_context.assistant_close_tokens.size());
    return true;
}
