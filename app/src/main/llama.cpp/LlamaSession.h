#pragma once

#include <memory>
#include <vector>

#include "HardwareUtils.h"
#include "llama.h"

struct TurnPlan {
    bool can_fit = false;
    bool rebuild_required = false;
    bool history_truncated = false;
    int available_generation_tokens = 0;
    std::vector<llama_token> replay_tokens;
    std::vector<llama_token> next_history_tokens;
};

class ConversationManager {
public:
    explicit ConversationManager(int context_capacity_tokens);

    void setSystemTokens(std::vector<llama_token> tokens);
    void clearHistory();
    void replaceHistory(std::vector<llama_token> tokens, bool truncated);
    void appendToHistory(const std::vector<llama_token> & tokens);

    size_t getHistorySize() const;
    size_t getSystemSize() const;
    size_t getActiveTokenCount() const;
    bool wasTruncated() const;

    int maxUserPromptTokens(int reserve_generation_tokens, size_t turn_wrapper_tokens) const;
    TurnPlan planForTurn(
            const std::vector<llama_token> & turn_tokens,
            int reserve_generation_tokens
    ) const;

    const std::vector<llama_token> & systemTokens() const;

private:
    int context_capacity_tokens_ = 0;
    std::vector<llama_token> system_tokens_;
    std::vector<llama_token> history_tokens_;
    bool history_truncated_ = false;
};

struct LlamaContext {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    std::unique_ptr<ConversationManager> conversation_manager;
    ThreadConfig thread_config;
    std::vector<llama_token> empty_user_turn_tokens;
    std::vector<llama_token> assistant_close_tokens;

    ~LlamaContext();

    void cleanup();
    bool isValid() const;
};

bool decodeTokenSequence(
        llama_context * ctx,
        const std::vector<llama_token> & tokens,
        const char * stage,
        int64_t * elapsed_us = nullptr
);

bool decodeSingleToken(llama_context * ctx, llama_token token, const char * stage);

bool replayConversationState(
        LlamaContext & llama_context,
        const std::vector<llama_token> & history_tokens,
        bool history_truncated,
        int64_t * elapsed_us = nullptr
);

bool resetConversationState(LlamaContext & llama_context);
bool prepareSessionState(LlamaContext & llama_context);
bool recoverConversationState(LlamaContext & llama_context);

bool closeAssistantTurn(
        LlamaContext & llama_context,
        std::vector<llama_token> * stored_tokens,
        int * context_token_count
);
