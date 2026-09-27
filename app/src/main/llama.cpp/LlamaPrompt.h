#pragma once

#include <string>
#include <vector>

#include "llama.h"

struct PromptFragment {
    std::string text;
    int estimated_tokens = 0;
    bool is_truncated = false;
};

std::string buildSystemTurnText(const std::string & system_prompt);
std::string buildUserTurnText(const std::string & user_prompt);

bool tokenizeText(
        const llama_vocab * vocab,
        const std::string & text,
        std::vector<llama_token> * out_tokens,
        bool add_bos,
        bool special
);

PromptFragment createSafePromptFragment(
        const std::string & prompt,
        const llama_vocab * vocab,
        int max_tokens
);
