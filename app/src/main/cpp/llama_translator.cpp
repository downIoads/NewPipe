// JNI wrapper around llama.cpp for the NewPipe translation feature.
// One model is loaded at a time (singleton pattern on the Java side).

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstring>
#include <cerrno>
#include <cstdio>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

#include "llama.h"

#define LOG_TAG "NewPipeTranslator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    struct State {
        llama_model   *model = nullptr;
        llama_context *ctx   = nullptr;
        const llama_vocab *vocab = nullptr;
        std::mutex mu;
        std::atomic<bool> cancel{false};
    };

    State g_state;

    std::string jstr(JNIEnv *env, jstring s) {
        if (s == nullptr) return {};
        const char *c = env->GetStringUTFChars(s, nullptr);
        std::string out = c ? c : "";
        if (c) env->ReleaseStringUTFChars(s, c);
        return out;
    }
}

static void llama_log_to_android(ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: prio = ANDROID_LOG_INFO; break;
    }
    __android_log_print(prio, "llama.cpp", "%s", text);
}

extern "C" JNIEXPORT void JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeBackendInit(JNIEnv *, jclass) {
    static std::once_flag once;
    std::call_once(once, []() {
        llama_log_set(llama_log_to_android, nullptr);
        llama_backend_init();
        LOGI("llama backend initialized");
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeLoad(
        JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_state.mu);

    if (g_state.ctx) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    if (g_state.model) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    g_state.vocab = nullptr;

    const std::string path = jstr(env, jpath);
    LOGI("loading model from %s", path.c_str());

    // --- diagnostics: prove the file is readable / mmappable from native ---
    {
        struct stat st{};
        if (stat(path.c_str(), &st) == 0) {
            LOGI("stat ok: size=%lld mode=0%o nlink=%d",
                 (long long) st.st_size, st.st_mode, (int) st.st_nlink);
        } else {
            LOGE("stat(%s) failed: errno=%d (%s)",
                 path.c_str(), errno, strerror(errno));
        }

        FILE *f = fopen(path.c_str(), "rb");
        if (f) {
            unsigned char hdr[8] = {0};
            const size_t n = fread(hdr, 1, sizeof(hdr), f);
            LOGI("fopen ok, read %zu header bytes: %02x %02x %02x %02x %02x %02x %02x %02x",
                 n, hdr[0], hdr[1], hdr[2], hdr[3],
                 hdr[4], hdr[5], hdr[6], hdr[7]);
            fclose(f);
        } else {
            LOGE("fopen(%s) failed: errno=%d (%s)",
                 path.c_str(), errno, strerror(errno));
        }

        // Try mmap directly so we know whether SELinux blocks it.
        const int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            LOGE("open(%s) failed: errno=%d (%s)",
                 path.c_str(), errno, strerror(errno));
        } else {
            void *p = mmap(nullptr, 4096, PROT_READ, MAP_SHARED, fd, 0);
            if (p == MAP_FAILED) {
                LOGE("mmap probe failed: errno=%d (%s) -- this usually means "
                     "SELinux blocked mmap on the underlying file",
                     errno, strerror(errno));
            } else {
                LOGI("mmap probe ok, first 4 bytes: %02x %02x %02x %02x",
                     ((unsigned char *) p)[0], ((unsigned char *) p)[1],
                     ((unsigned char *) p)[2], ((unsigned char *) p)[3]);
                munmap(p, 4096);
            }
            close(fd);
        }
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only on phones for now
    mparams.use_mmap = true;

    LOGI("calling llama_model_load_from_file (use_mmap=%d, n_gpu_layers=%d)",
         (int) mparams.use_mmap, mparams.n_gpu_layers);

    g_state.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_state.model) {
        LOGE("llama_model_load_from_file returned null");
        return JNI_FALSE;
    }
    g_state.vocab = llama_model_get_vocab(g_state.model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = nCtx > 0 ? (uint32_t) nCtx : 2048;
    cparams.n_batch   = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) {
        LOGE("failed to create llama context");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }

    LOGI("model loaded, n_ctx=%d threads=%d", cparams.n_ctx, cparams.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeRelease(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state.mu);
    if (g_state.ctx)   { llama_free(g_state.ctx); g_state.ctx = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    g_state.vocab = nullptr;
    LOGI("model released");
}

extern "C" JNIEXPORT void JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeCancel(JNIEnv *, jclass) {
    g_state.cancel.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state.mu);
    return (g_state.model && g_state.ctx) ? JNI_TRUE : JNI_FALSE;
}

static int64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_schabi_newpipe_translation_LlamaTranslator_nativeTranslate(
        JNIEnv *env, jclass,
        jstring jtext, jstring jtarget, jint maxTokens) {

    const int64_t t_start = now_ms();
    LOGI("nativeTranslate: entering");

    std::lock_guard<std::mutex> lock(g_state.mu);
    if (!g_state.ctx || !g_state.model) {
        LOGE("translate called without loaded model");
        return nullptr;
    }
    g_state.cancel.store(false);

    const std::string text   = jstr(env, jtext);
    const std::string target = jstr(env, jtarget);
    LOGI("nativeTranslate: text_len=%zu target='%s'", text.size(), target.c_str());

    const std::string system_msg =
            "You are a translation engine. Output ONLY the translated text. "
            "Do not add any preamble, explanations, notes, or labels such "
            "as \"Here's the translation:\" or \"Translation:\". Do not "
            "wrap the output in quotes. Preserve original formatting, "
            "timestamps, emojis, and punctuation.";

    const std::string user_msg =
            "Translate the following text to " + target + ":\n\n" + text;

    std::vector<llama_chat_message> msgs = {
            {"system", system_msg.c_str()},
            {"user",   user_msg.c_str()},
    };

    const char *tmpl = llama_model_chat_template(g_state.model, /*name*/nullptr);
    LOGI("nativeTranslate: chat template ptr=%p (%s)",
         (const void *) tmpl, tmpl ? "non-null" : "NULL");

    std::vector<char> fmt(system_msg.size() + user_msg.size() + 2048);
    int written = llama_chat_apply_template(
            tmpl, msgs.data(), msgs.size(), /*add_ass*/true,
            fmt.data(), (int32_t) fmt.size());
    LOGI("nativeTranslate: apply_template returned %d (buf=%zu)", written, fmt.size());

    // Some chat templates (Gemma among them, depending on llama.cpp build)
    // reject a separate "system" role. Fall back to merging the system
    // instruction into the user message so the prompt still gets through.
    std::string merged_user;
    if (written < 0) {
        LOGE("apply_template failed with system role; retrying merged into user");
        merged_user = system_msg + "\n\n" + user_msg;
        msgs = { {"user", merged_user.c_str()} };
        fmt.assign(merged_user.size() + 2048, 0);
        written = llama_chat_apply_template(
                tmpl, msgs.data(), msgs.size(), true,
                fmt.data(), (int32_t) fmt.size());
        LOGI("nativeTranslate: apply_template (merged) returned %d", written);
        if (written < 0) {
            LOGE("chat template formatting failed even after merge");
            return nullptr;
        }
    }

    if ((size_t) written > fmt.size()) {
        fmt.resize(written);
        written = llama_chat_apply_template(
                tmpl, msgs.data(), msgs.size(), true,
                fmt.data(), (int32_t) fmt.size());
        LOGI("nativeTranslate: apply_template second pass returned %d", written);
        if (written < 0) return nullptr;
    }
    const std::string prompt(fmt.data(), (size_t) written);
    LOGI("nativeTranslate: prompt length=%zu (first 200 chars): %.200s",
         prompt.size(), prompt.c_str());

    const int est = -llama_tokenize(
            g_state.vocab, prompt.c_str(), (int32_t) prompt.size(),
            nullptr, 0, /*add_special*/true, /*parse_special*/true);
    LOGI("nativeTranslate: estimated tokens=%d", est);
    std::vector<llama_token> tokens(est);
    int n_prompt = llama_tokenize(
            g_state.vocab, prompt.c_str(), (int32_t) prompt.size(),
            tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_prompt < 0) {
        LOGE("tokenize failed (n_prompt=%d)", n_prompt);
        return nullptr;
    }
    tokens.resize(n_prompt);
    LOGI("nativeTranslate: tokenized to %d tokens", n_prompt);

    llama_memory_clear(llama_get_memory(g_state.ctx), true);

    const int n_batch = (int) llama_n_batch(g_state.ctx);
    LOGI("nativeTranslate: decoding prompt in batches of %d", n_batch);
    const int64_t t_prompt_start = now_ms();
    int n_past = 0;
    for (int i = 0; i < (int) tokens.size(); i += n_batch) {
        const int chunk = std::min(n_batch, (int) tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        const int rc = llama_decode(g_state.ctx, batch);
        if (rc != 0) {
            LOGE("decode failed at prompt batch i=%d chunk=%d rc=%d", i, chunk, rc);
            return nullptr;
        }
        n_past += chunk;
        LOGI("nativeTranslate: prompt decode progress %d/%d", n_past, n_prompt);
    }
    const int64_t t_prompt_done = now_ms();
    LOGI("nativeTranslate: prompt decode took %lld ms",
         (long long) (t_prompt_done - t_prompt_start));

    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    out.reserve(text.size() * 2);

    const int max_new = maxTokens > 0 ? maxTokens : 512;
    LOGI("nativeTranslate: starting generation, max_new=%d", max_new);
    int64_t t_last_log = now_ms();
    for (int gen = 0; gen < max_new; ++gen) {
        if (g_state.cancel.load()) {
            LOGI("nativeTranslate: cancelled at gen=%d", gen);
            break;
        }
        const llama_token id = llama_sampler_sample(smpl, g_state.ctx, -1);
        if (llama_vocab_is_eog(g_state.vocab, id)) {
            LOGI("nativeTranslate: EOG hit at gen=%d, total_out=%zu chars",
                 gen, out.size());
            break;
        }

        char piece[256];
        const int np = llama_token_to_piece(
                g_state.vocab, id, piece, sizeof(piece), 0, /*special*/false);
        if (np < 0) {
            LOGE("token_to_piece failed at gen=%d (np=%d)", gen, np);
            break;
        }
        out.append(piece, (size_t) np);

        if (gen < 3 || gen % 10 == 0) {
            const int64_t now = now_ms();
            const double tps = (gen + 1) * 1000.0 / (now - t_prompt_done + 1);
            LOGI("nativeTranslate: gen=%d total_chars=%zu tok/s=%.2f piece='%.*s'",
                 gen, out.size(), tps, np, piece);
            t_last_log = now;
        }

        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        const int drc = llama_decode(g_state.ctx, batch);
        if (drc != 0) {
            LOGE("decode failed during generation at gen=%d rc=%d", gen, drc);
            break;
        }
        n_past += 1;
    }

    llama_sampler_free(smpl);

    size_t a = out.find_first_not_of(" \t\r\n");
    size_t b = out.find_last_not_of(" \t\r\n");
    std::string trimmed = (a == std::string::npos) ? "" : out.substr(a, b - a + 1);
    LOGI("nativeTranslate: done in %lld ms, output '%.200s'",
         (long long) (now_ms() - t_start), trimmed.c_str());
    return env->NewStringUTF(trimmed.c_str());
}
