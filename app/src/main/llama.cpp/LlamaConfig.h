#pragma once
#include <string_view>

namespace Config {
constexpr int SAFETY_MARGIN = 128;
constexpr int MIN_GENERATION_TOKENS = 50;
constexpr int LOG_INTERVAL = 10;
constexpr float TRUNCATE_THRESHOLD = 0.7f;
constexpr int DEFAULT_CONTEXT_TOKENS = 4096;
constexpr int MIN_CONTEXT_TOKENS = 1024;
constexpr int MAX_CONTEXT_TOKENS = 8192;
constexpr int N_BATCH = 512;
constexpr int N_UBATCH = 512;
constexpr int MAX_CPU_SCAN = 64;
constexpr int TRUNCATION_NOTE_HEADROOM = 32;

constexpr const char * kSystemTurnPrefix = "<|im_start|>system\n";
constexpr const char * kUserTurnPrefix = "<|im_start|>user\n";
constexpr const char * kTurnClose = "<|im_end|>\n";
constexpr const char * kAssistantTurnPrefix = "<|im_start|>assistant\n";

// constexpr const char * kSystemPrompt = R"(You are a helpful assistant with access to these tools:

// 1. get_date_info() - Use ONLY for current date/time queries

// 2. get_internet_info(query: string)
//    - MANDATORY for:
//      * ANY question about real people, bands, artists, companies, places
//      * ANY question with 'search', 'look up', 'find', 'who is', 'what is'
//      * Current events, presidents, officials, celebrities
//      * Music: songs, albums, discographies, band members
//      * Definitions, facts, statistics, historical data
//    - Use short keyword queries (e.g., 'Los Tigres del Norte band', 'current Mexico president')
//    - NO URLs in queries
//    - When user says 'search internet' or 'look up', ALWAYS use this tool

// 3. create_playlist(name: string) - Use when user requests playlist creation

// 4. insert_artist_playlist(artist: String, playlistName: String)
//    - Use when adding artist songs to a playlist
//    - Extract BOTH parameters from user request:
//      * artist: Exact artist name
//      * playlistName: Exact playlist name as user states it
//    - Example: 'add The Doors to playlist Doors for ever' ->
//      {"tool": "insert_artist_playlist", "arguments": {"artist": "The Doors", "playlistName": "Doors for ever"}}

// 5. find_local_songs(query: string, limit: string?)
//    - Use when user asks to find songs in their local library
//    - Query should be plain text, no URLs
//    - limit is optional and should be a number as string
//    - Example:
//      {"tool": "find_local_songs", "arguments": {"query": "soda", "limit": "5"}}

// For tool calls, respond ONLY with JSON (no extra text):
// {
//   "tool": "<tool_name>",
//   "arguments": {"<param>": "<value>"}
// }

// For normal responses, answer naturally in Spanish unless the user clearly asks for another language.)";
constexpr const char * kSystemPrompt = R"(You are a helpful assistant. Use tools by responding ONLY with JSON:
{"tool": "<name>", "arguments": {"<param>": "<value>"}}

Tools:
1. get_date_info() → current date/time
2. get_internet_info(query) → people, places, events, music, facts. Use short keywords
3. create_playlist(name) → create a playlist
4. insert_artist_playlist(artist, playlistName) → add artist songs to playlist
5. find_local_songs(query, limit?) → search local library

Always use get_internet_info for: real people, bands, companies, current events, definitions.
Reply in Spanish unless asked otherwise.)";
} // namespace Config