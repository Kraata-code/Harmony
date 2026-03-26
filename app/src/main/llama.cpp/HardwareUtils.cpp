#include "HardwareUtils.h"
#include "LlamaConfig.h" // Para usar Config::MAX_CPU_SCAN

#include <algorithm>
#include <fstream>
#include <sstream>
#include <unistd.h>

// Estructura interna para agrupar frecuencias (no necesita salir al header)
struct FrequencyGroup {
    long freq_khz = 0;
    int count = 0;
};

// --- Funciones Auxiliares Estáticas (Solo visibles en este archivo) ---

static int getAvailableCpuCount() {
    const long count = sysconf(_SC_NPROCESSORS_ONLN);
    return count > 0 ? static_cast<int>(count) : 1;
}

static bool readLongFromFile(const std::string & path, long * value) {
    std::ifstream input(path);
    if (!input.is_open()) return false;
    
    long parsed = 0;
    input >> parsed;
    if (input.fail()) return false;
    
    *value = parsed;
    return true;
}

static bool isCpuOnline(int cpu_index) {
    const std::string path =
            "/sys/devices/system/cpu/cpu" + std::to_string(cpu_index) + "/online";
    long online = 0;
    if (!readLongFromFile(path, &online)) {
        return access(path.c_str(), F_OK) != 0; // cpu0 a veces no tiene el archivo
    }
    return online != 0;
}

static std::vector<FrequencyGroup> buildFrequencyGroups(std::vector<long> freqs_khz) {
    if (freqs_khz.empty()) {
        return {};
    }

    std::sort(freqs_khz.begin(), freqs_khz.end(), std::greater<>());

    std::vector<FrequencyGroup> groups;
    for (const long freq_khz : freqs_khz) {
        if (groups.empty()) {
            groups.push_back({freq_khz, 1});
            continue;
        }

        auto & current_group = groups.back();
        if (freq_khz * 100 >= current_group.freq_khz * 95) {
            current_group.count += 1;
            current_group.freq_khz = std::max(current_group.freq_khz, freq_khz);
        } else {
            groups.push_back({freq_khz, 1});
        }
    }

    return groups;
}

static std::string summarizeFrequencyGroups(const std::vector<FrequencyGroup> & groups) {
    std::ostringstream builder;
    for (size_t i = 0; i < groups.size(); ++i) {
        if (i > 0) {
            builder << " | ";
        }
        builder << groups[i].freq_khz << "x" << groups[i].count;
    }
    return builder.str();
}


// --- Implementación de la Función Pública ---

ThreadConfig detectThreadConfig() {
    ThreadConfig config;
    config.online_cpu_count = std::max(1, getAvailableCpuCount());

#if defined(__aarch64__)
    std::vector<long> online_freqs;
    long highest_freq = 0;

    for (int cpu = 0; cpu < Config::MAX_CPU_SCAN; ++cpu) {
        const std::string cpu_path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu);
        if (access(cpu_path.c_str(), F_OK) != 0 || !isCpuOnline(cpu)) {
            continue;
        }

        long max_freq = 0;
        const std::string freq_path = cpu_path + "/cpufreq/cpuinfo_max_freq";
        if (readLongFromFile(freq_path, &max_freq) && max_freq > 0) {
            online_freqs.push_back(max_freq);
            highest_freq = std::max(highest_freq, max_freq);
        }
    }

    config.observed_max_freqs_khz = online_freqs;
    config.highest_max_freq_khz = highest_freq;

    if (highest_freq > 0 && !online_freqs.empty()) {
        const auto groups = buildFrequencyGroups(online_freqs);
        config.frequency_groups_summary = summarizeFrequencyGroups(groups);

        if (!groups.empty()) {
            long decode_floor_freq_khz = groups.back().freq_khz;
            int largest_drop_pct = 0;
            size_t boundary_idx = groups.size() - 1;

            for (size_t i = 0; i + 1 < groups.size(); ++i) {
                const long upper = groups[i].freq_khz;
                const long lower = groups[i + 1].freq_khz;
                const int drop_pct = upper > 0
                                     ? static_cast<int>(((upper - lower) * 100) / upper)
                                     : 0;

                if (drop_pct > largest_drop_pct) {
                    largest_drop_pct = drop_pct;
                    boundary_idx = i;
                }
            }

            config.largest_cluster_drop_pct = largest_drop_pct;

            if (groups.size() == 1) {
                decode_floor_freq_khz = groups.front().freq_khz;
            } else if (largest_drop_pct >= 8) {
                decode_floor_freq_khz = groups[boundary_idx].freq_khz;
            }

            if (groups.front().count == 1 &&
                groups.size() >= 2 &&
                groups[1].freq_khz * 100 >= groups[0].freq_khz * 80) {
                decode_floor_freq_khz = std::min(decode_floor_freq_khz, groups[1].freq_khz);
                config.merged_prime_cluster = true;
            }

            config.decode_floor_freq_khz = decode_floor_freq_khz;

            int decode_threads = 0;
            for (const auto & group : groups) {
                if (group.freq_khz >= decode_floor_freq_khz) {
                    decode_threads += group.count;
                }
            }

            config.decode_threads = std::clamp(decode_threads, 1, config.online_cpu_count);
            config.prefill_threads = std::clamp(
                    std::max(config.decode_threads, config.online_cpu_count),
                    1,
                    config.online_cpu_count
            );
            return config;
        }
    }
#endif

    // Fallback para x86_64 o si falla la detección en ARM
    const int decode_threads =
            config.online_cpu_count <= 4 ? config.online_cpu_count : config.online_cpu_count / 2;
    config.decode_threads = std::clamp(decode_threads, 1, config.online_cpu_count);
    config.prefill_threads = std::clamp(
            std::max(config.decode_threads, config.online_cpu_count),
            1,
            config.online_cpu_count
    );
    return config;
}
