#include "LlamaPrompt.h"

#include <algorithm>

#include "LlamaCommon.h"
#include "LlamaConfig.h"

namespace {

size_t findIntelligentCutPoint(const std::string & text, int target_pos) {
    const size_t min_acceptable =
            static_cast<size_t>(target_pos * Config::TRUNCATE_THRESHOLD);

    const size_t last_sentence = text.find_last_of(".!?", static_cast<size_t>(target_pos));
    if (last_sentence != std::string::npos && last_sentence > min_acceptable) {
        return last_sentence + 1;
    }

    const size_t last_paragraph = text.rfind("\n\n", static_cast<size_t>(target_pos));
    if (last_paragraph != std::string::npos && last_paragraph > min_acceptable) {
        return last_paragraph + 2;
    }

    const size_t last_line = text.find_last_of('\n', static_cast<size_t>(target_pos));
    if (last_line != std::string::npos && last_line > min_acceptable) {
        return last_line + 1;
    }

    const size_t last_space = text.find_last_of(' ', static_cast<size_t>(target_pos));
    if (last_space != std::string::npos && last_space > min_acceptable) {
        return last_space;
    }

    return static_cast<size_t>(target_pos);
}

} // namespace

std::string buildSystemTurnText(const std::string & system_prompt) {
    return std::string(Config::kSystemTurnPrefix) +
           system_prompt +
           Config::kTurnClose;
}

std::string buildUserTurnText(const std::string & user_prompt) {
    return std::string(Config::kUserTurnPrefix) +
           user_prompt +
           Config::kTurnClose +
           Config::kAssistantTurnPrefix;
}

bool tokenizeText(
        const llama_vocab * vocab,
        const std::string & text,
        std::vector<llama_token> * out_tokens,
        bool add_bos,
        bool special
) {
    const int32_t required = -llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            nullptr,
            0,
            add_bos,
            special
    );

    if (required <= 0) {
        return false;
    }

    out_tokens->assign(static_cast<size_t>(required), 0);
    const int32_t written = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            out_tokens->data(),
            required,
            add_bos,
            special
    );

    if (written < 0) {
        return false;
    }

    out_tokens->resize(static_cast<size_t>(written));
    return true;
}

PromptFragment createSafePromptFragment(
        const std::string & prompt,
        const llama_vocab * vocab,
        int max_tokens
) {
    PromptFragment fragment;
    const int estimated_tokens = static_cast<int>(prompt.length()) / 4;

    if (estimated_tokens <= max_tokens) {
        fragment.text = prompt;
        fragment.estimated_tokens = estimated_tokens;
        return fragment;
    }

    LOGW("Prompt demasiado largo: ~%d tokens, maximo bruto: %d", estimated_tokens, max_tokens);

    const int target_chars = max_tokens * 4;
    std::string truncated = prompt.substr(
            0,
            static_cast<size_t>(std::min(target_chars, static_cast<int>(prompt.length())))
    );

    const size_t cut_point = findIntelligentCutPoint(truncated, target_chars);
    truncated = truncated.substr(0, cut_point);

    fragment.text = truncated + "\n\n[contenido truncado por limite de contexto]";
    fragment.is_truncated = true;

    std::vector<llama_token> tokens;
    if (tokenizeText(vocab, fragment.text, &tokens, false, false)) {
        fragment.estimated_tokens = static_cast<int>(tokens.size());
    }

    return fragment;
}
