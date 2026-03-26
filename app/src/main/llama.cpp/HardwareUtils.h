#pragma once

#include <string>
#include <vector>

struct ThreadConfig {
    int online_cpu_count = 1;
    int decode_threads = 1;
    int prefill_threads = 1;
    long highest_max_freq_khz = 0;
    std::vector<long> observed_max_freqs_khz;
    std::string frequency_groups_summary;
    long decode_floor_freq_khz = 0;
    int largest_cluster_drop_pct = 0;
    bool merged_prime_cluster = false;
};

// Función principal para detectar configuración de hilos en Android
ThreadConfig detectThreadConfig();