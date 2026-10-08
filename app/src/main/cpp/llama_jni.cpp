// JNI bridge between the Kotlin API (dev.ffmpegkit.llama.LlamaJNI) and llama.cpp.
// Uses the core llama.h API only (no examples "common" lib). Produces
// libllama_jni.so; Kotlin loads ggml, llama, then llama_jni.
//
// Native entry points (must match LlamaJNI.kt):
//   nativeLoadModel(path, nCtx, nThreads, nGpuLayers)                 -> jlong handle
//   nativeComplete(handle, prompt, system, maxTokens, temp, topP, topK, seed) -> jstring (JSON)
//   nativeRender(handle, roles[], contents[], prefill)               -> jstring (提示词)  [鸿胪寺扩展]
//   nativeGenerate(handle, prompt, stops[], bans[],
//                  maxTokens, temp, topP, topK, seed,
//                  repeatPenalty, penaltyLastN, presencePenalty, frequencyPenalty,
//                  sink)                                             -> jstring (JSON)   [鸿胪寺扩展]
//   nativeEmbed(handle, text)                                         -> jfloatArray
//   nativeReleaseModel(handle)                                        -> void
//   nativeModelMeta(handle, key)                                      -> jstring (元数据)   [鸿胪寺扩展]
//   nativeSetEnv(key, value)                                          -> void (进程环境变量) [鸿胪寺扩展]
//   nativeGetSystemInfo()                                             -> jstring

#include <jni.h>
#include <android/log.h>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaCtx {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_threads = 4;
};

bool g_backend_ready = false;

// jstring → 真正的 UTF-8（补齐代理对）。
// ⚠ 不能用 GetStringUTFChars：它给的是 **Modified UTF-8**（emoji 这类补充平面字符
//   会被拆成两个 3 字节代理对），当普通 UTF-8 用就是坏的。
std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const jsize n = env->GetStringLength(s);
    const jchar *u = env->GetStringChars(s, nullptr);
    if (!u) return {};
    std::string 出;
    出.reserve((size_t) n * 3);
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000u + ((cp - 0xD800u) << 10) + (u[i + 1] - 0xDC00u);
            i++;
        }
        if (cp < 0x80) {
            出 += (char) cp;
        } else if (cp < 0x800) {
            出 += (char) (0xC0u | (cp >> 6));
            出 += (char) (0x80u | (cp & 0x3Fu));
        } else if (cp < 0x10000) {
            出 += (char) (0xE0u | (cp >> 12));
            出 += (char) (0x80u | ((cp >> 6) & 0x3Fu));
            出 += (char) (0x80u | (cp & 0x3Fu));
        } else {
            出 += (char) (0xF0u | (cp >> 18));
            出 += (char) (0x80u | ((cp >> 12) & 0x3Fu));
            出 += (char) (0x80u | ((cp >> 6) & 0x3Fu));
            出 += (char) (0x80u | (cp & 0x3Fu));
        }
    }
    env->ReleaseStringChars(s, u);
    return 出;
}

// 真正的 UTF-8 → jstring。
// ⚠ 别用 NewStringUTF：它要的是 **Modified UTF-8**，喂 4 字节 UTF-8（emoji）会解成乱码。
jstring 到jstring(JNIEnv *env, const std::string &s) {
    std::vector<jchar> 出;
    出.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp; int 长;
        if (c < 0x80) { cp = c; 长 = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1Fu; 长 = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0Fu; 长 = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07u; 长 = 4; }
        else { 出.push_back(0xFFFD); i++; continue; }
        bool 坏 = (i + (size_t) 长 > n);
        if (!坏) for (int k = 1; k < 长; k++) {
            unsigned char d = (unsigned char) s[i + k];
            if ((d & 0xC0) != 0x80) { 坏 = true; break; }
            cp = (cp << 6) | (d & 0x3Fu);
        }
        if (坏) { 出.push_back(0xFFFD); i++; continue; }
        i += 长;
        if (cp <= 0xFFFF) {
            出.push_back((jchar) cp);
        } else {
            cp -= 0x10000u;
            出.push_back((jchar) (0xD800u + (cp >> 10)));
            出.push_back((jchar) (0xDC00u + (cp & 0x3FFu)));
        }
    }
    return env->NewString(出.empty() ? nullptr : 出.data(), (jsize) 出.size());
}

// [0, len) 里最多能"安全"吐多少字节 —— 别把末尾那个多字节字符切一半。
size_t utf8安全长度(const std::string &s, size_t len) {
    if (len > s.size()) len = s.size();
    while (len > 0) {
        size_t 起 = len;
        for (size_t k = 1; k <= 4 && k <= len; k++) {
            unsigned char c = (unsigned char) s[len - k];
            if ((c & 0xC0) != 0x80) { 起 = len - k; break; }
        }
        if (起 == len) return len;                   // 找不到首字节 → 原样
        unsigned char f = (unsigned char) s[起];
        size_t 需要 = (f & 0xF8) == 0xF0 ? 4 : (f & 0xF0) == 0xE0 ? 3 :
                      (f & 0xE0) == 0xC0 ? 2 : 1;
        if (len - 起 >= 需要) return len;            // 末尾字符是完整的
        len = 起;                                    // 不完整 → 退到它前面
    }
    return 0;
}

std::string json_escape(const std::string &in) {
    std::string o;
    o.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n";  break;
            case '\r': o += "\\r";  break;
            case '\t': o += "\\t";  break;
            default:   o += c;      break;
        }
    }
    return o;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                            nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n);
    int check = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                               tokens.data(), (int32_t) tokens.size(), add_special, true);
    if (check < 0) tokens.clear();
    return tokens;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

// Format the (system,user) turn with the model's built-in chat template, falling
// back to a plain concatenation if the model has none.
std::string build_prompt(const llama_model *model, const std::string &system,
                         const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) {
        return system.empty() ? user : (system + "\n\n" + user);
    }
    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});
    std::vector<char> buf(user.size() + system.size() + 2048);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                          buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                      buf.data(), (int32_t) buf.size());
    }
    if (n < 0) return system.empty() ? user : (system + "\n\n" + user);
    return std::string(buf.data(), n);
}

llama_sampler *make_sampler(float temp, float topP, int topK, int seed) {
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(
                seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }
    return smpl;
}

// ═══════════════════════════════════════════════════════════════════
// 文字层「禁吐」采样器 —— 把指定短语从生成里掐掉（对齐 PC 版 酒馆协议.禁吐）
//
// ⚠ 匹配在「文字层」不在「token 层」：BPE 里同一串有多种切法，按标准切法算出的
//   token id 可能压根不会出现。所以每一步把尾巴文字抠出来，若它正好落在某个目标
//   的**真前缀**上，就把候选里所有「能接着往这个目标写」的 token 压掉。
//   强度用 -1e4 而不是 -inf（有些后端对 -inf 的 softmax 会出 nan）。
// ═══════════════════════════════════════════════════════════════════

struct 禁吐上下文 {
    const llama_vocab *vocab = nullptr;
    std::vector<std::string> 目标;
    std::string 尾巴;          // 已生成文本的尾部（只看最后一段）
    int 最长 = 0;              // 目标里最长的字节数
};

// 取 token 的「正文片段」：special=false → 控制符（如 <|im_end|>）渲染成空串。
std::string 片段(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n >= 0) return std::string(buf, n);
    int 要 = -n;                     // 缓冲区不够时，返回的是所需大小的相反数
    if (要 <= 0 || 要 > 8192) return {};
    std::vector<char> 大(要);
    n = llama_token_to_piece(vocab, tok, 大.data(), 要, 0, false);
    return n > 0 ? std::string(大.data(), n) : std::string();
}

const char *禁吐_名(const llama_sampler *) { return "honglusi-ban"; }

void 禁吐_接受(llama_sampler *smpl, llama_token tok) {
    auto *c = (禁吐上下文 *) smpl->ctx;
    c->尾巴 += 片段(c->vocab, tok);
    size_t 限 = (size_t) c->最长 * 2 + 16;
    if (c->尾巴.size() > 限) c->尾巴.erase(0, c->尾巴.size() - 限);
}

void 禁吐_复位(llama_sampler *smpl) {
    auto *c = (禁吐上下文 *) smpl->ctx;
    c->尾巴.clear();
}

void 禁吐_应用(llama_sampler *smpl, llama_token_data_array *cur) {
    auto *c = (禁吐上下文 *) smpl->ctx;
    const std::string &尾 = c->尾巴;
    for (const std::string &T : c->目标) {
        // 尾巴结尾是不是 T 的真前缀（不能是完整的 T）
        int 前 = 0;
        for (int k = 1; k < (int) T.size(); k++) {
            if ((int) 尾.size() >= k && 尾.compare(尾.size() - k, k, T, 0, k) == 0) 前 = k;
        }
        if (前 <= 0) continue;
        std::string 剩 = T.substr(前);
        if (剩.empty()) continue;
        for (size_t i = 0; i < cur->size; i++) {
            std::string s = 片段(c->vocab, cur->data[i].id);
            if (s.empty()) continue;
            // s 是「剩」的开头，或「剩」是 s 的开头 —— 两种都挡
            if (s.compare(0, 剩.size(), 剩) == 0 || 剩.compare(0, s.size(), s) == 0)
                cur->data[i].logit = -1e4f;
        }
    }
}

void 禁吐_释放(llama_sampler *smpl) {
    delete (禁吐上下文 *) smpl->ctx;
    smpl->ctx = nullptr;
}

llama_sampler *禁吐_克隆(const llama_sampler *smpl);   // 前向声明

llama_sampler_i 禁吐接口 = {
    禁吐_名, 禁吐_接受, 禁吐_应用, 禁吐_复位, 禁吐_克隆, 禁吐_释放,
};

llama_sampler *禁吐_克隆(const llama_sampler *smpl) {
    auto *c = (禁吐上下文 *) smpl->ctx;
    return llama_sampler_init(&禁吐接口, new 禁吐上下文(*c));
}

llama_sampler *造禁吐(const llama_vocab *vocab, const std::vector<std::string> &目标) {
    auto *c = new 禁吐上下文();
    c->vocab = vocab;
    c->目标 = 目标;
    for (auto &t : 目标) if ((int) t.size() > c->最长) c->最长 = (int) t.size();
    return llama_sampler_init(&禁吐接口, c);
}

// 把 Java String[] 取成 C++ vector<string>（空的跳过）。
std::vector<std::string> 取串数组(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> 出;
    if (!arr) return 出;
    jsize n = env->GetArrayLength(arr);
    出.reserve(n);
    for (jsize i = 0; i < n; i++) {
        auto *js = (jstring) env->GetObjectArrayElement(arr, i);
        std::string s = jstr(env, js);
        env->DeleteLocalRef(js);
        if (!s.empty()) 出.push_back(s);
    }
    return 出;
}

// 用**指定**模板（可以是内置模板名，如 "chatml"）把 (role, content) 列表渲染成一整串。
// 渲染不了回空串。⚠ 别把 tmpl 传 NULL —— llama.cpp 会静默退成 chatml，不是我们要的。
std::string 套模板用(const char *tmpl,
                     const std::vector<std::pair<std::string, std::string>> &消息) {
    if (!tmpl || 消息.empty()) return {};
    std::vector<llama_chat_message> msgs;
    msgs.reserve(消息.size());
    for (auto &p : 消息) msgs.push_back({p.first.c_str(), p.second.c_str()});
    std::vector<char> buf(4096);
    for (int 轮 = 0; 轮 < 12; 轮++) {
        int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                              buf.data(), (int32_t) buf.size());
        if (n < 0) return {};
        if (n <= (int32_t) buf.size()) return std::string(buf.data(), n);
        buf.resize(n + 64);
    }
    return {};
}

// 读模型的 GGUF 元数据（如 general.architecture）。取不到回空串。
std::string 元数据(const llama_model *model, const char *key) {
    char buf[256] = {0};
    int32_t n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    return n > 0 ? std::string(buf, n) : std::string();
}

// 模型自带模板缺失时，按 `general.architecture` 挑一份 llama.cpp **内置模板**兜底。
// ⚠ 对齐 PC 版 `酒馆协议.族默认模板`：**gemma 故意没有**（它没 system 角色、形状特殊，
//   猜错的比"报出来让用户换个量化"更坏）。认不出也一律不兜底。
const char *族默认模板名(const llama_model *model) {
    std::string arch = 元数据(model, "general.architecture");
    for (char &c : arch) c = (char) tolower((unsigned char) c);
    auto 前 = [&](const char *头) { return arch.rfind(头, 0) == 0; };
    if (前("qwen")) return "chatml";
    if (前("glm") || 前("chatglm")) return "chatglm4";
    if (前("phi")) return "phi3";
    if (前("llama")) return "llama3";
    if (前("mistral")) return "mistral-v1";
    // 后面补的三族（模板名就是 llama.cpp 内置表里的键，见 llama-chat.cpp）。
    // ⚠ 顺序：deepseek3 / deepseek2 必须排在 deepseek 前面，否则被前缀吃掉。
    if (前("deepseek3")) return "deepseek3";
    if (前("deepseek2")) return "deepseek2";
    if (前("deepseek")) return "deepseek";
    if (前("minicpm")) return "minicpm";
    if (前("seed")) return "seed_oss";
    return nullptr;
}

// 从系统提示里挑一段「模板不会改写」的样本，用来验它有没有被渲染进去。
// 拿整段比不行（模板常带 | trim 改首尾空白）；挑第一行 ≥12 字的前 80 字。
std::string 取探针(const std::string &文) {
    size_t 起 = 0;
    while (起 < 文.size()) {
        size_t 尾 = 文.find('\n', 起);
        std::string 行 = 文.substr(起, (尾 == std::string::npos ? 文.size() : 尾) - 起);
        // 去首尾空白
        size_t a = 行.find_first_not_of(" \t\r");
        size_t b = 行.find_last_not_of(" \t\r");
        if (a != std::string::npos) {
            std::string 净 = 行.substr(a, b - a + 1);
            if (净.size() >= 12) return 净.substr(0, 80);
        }
        if (尾 == std::string::npos) break;
        起 = 尾 + 1;
    }
    return 文.size() > 60 ? 文.substr(0, 60) : 文;
}

// 渲染多轮 → 一整串。走模型自带模板；**模板缺失时按族挑内置模板兜底**（同 PC）。
// 模板不吃 system 时**并进第一条 user** 再渲染一次（安装补偿）。
// 回空串 = 既没模板、族也认不出（调用方退成朴素拼接）。
std::string 渲染对话(const llama_model *model,
                     const std::vector<std::pair<std::string, std::string>> &消息) {
    const char *mt = llama_model_chat_template(model, nullptr);
    const char *族 = mt ? nullptr : 族默认模板名(model);
    const char *用 = mt ? mt : 族;
    if (!用) return {};

    std::string 串 = 套模板用(用, 消息);
    if (串.empty()) return {};
    int 系 = -1;
    for (size_t i = 0; i < 消息.size(); i++)
        if (消息[i].first == "system" && !消息[i].second.empty()) { 系 = (int) i; break; }
    if (系 >= 0) {
        std::string 探 = 取探针(消息[系].second);
        if (!探.empty() && 串.find(探) == std::string::npos) {
            auto 补 = 消息;
            补.erase(补.begin() + 系);
            bool 放好 = false;
            for (size_t i = 0; i < 补.size(); i++) {
                if (补[i].first == "user") {
                    补[i].second = 消息[系].second + "\n\n" + 补[i].second;
                    放好 = true;
                    break;
                }
            }
            if (!放好) 补.emplace(补.begin(), "user", 消息[系].second);
            std::string 再 = 套模板用(用, 补);
            if (!再.empty() && 再.find(探) != std::string::npos) 串 = 再;
        }
    }
    return 串;
}

// roles/contents 两个 Java String[] → (role, content) 列表（两个 native 入口共用）。
std::vector<std::pair<std::string, std::string>> 取消息(JNIEnv *env, jobjectArray roles,
                                                         jobjectArray contents) {
    std::vector<std::pair<std::string, std::string>> 消息;
    jsize n = roles ? env->GetArrayLength(roles) : 0;
    消息.reserve(n);
    for (jsize i = 0; i < n; i++) {
        auto *jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto *jc = (jstring) env->GetObjectArrayElement(contents, i);
        消息.emplace_back(jstr(env, jr), jstr(env, jc));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    return 消息;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeLoadModel(
        JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads, jint nGpuLayers) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    auto *h = new LlamaCtx();
    h->n_threads = nThreads;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    h->model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!h->model) {
        LOGE("failed to load model");
        delete h;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx) {
        LOGE("failed to create context");
        llama_model_free(h->model);
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeComplete(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jstring system,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h || !h->ctx) return env->NewStringUTF("{}");

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    const std::string text = build_prompt(h->model, jstr(env, system), jstr(env, prompt));

    // Each complete() is a fresh single turn: reset the KV cache so successive calls
    // on the same model don't bleed context into one another.
    llama_memory_clear(llama_get_memory(h->ctx), true);

    std::vector<llama_token> tokens = tokenize(vocab, text, true);
    const int n_prompt = (int) tokens.size();

    llama_sampler *smpl = make_sampler(temp, topP, topK, seed);

    const int64_t t0 = llama_time_us();
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        llama_sampler_free(smpl);
        return env->NewStringUTF("{\"text\":\"\",\"error\":\"decode failed\"}");
    }
    const int64_t t1 = llama_time_us();

    std::string out;
    int n_gen = 0;
    for (; n_gen < maxTokens; n_gen++) {
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        llama_sampler_accept(smpl, id);
        out += token_to_piece(vocab, id);
        llama_batch nb = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, nb) != 0) break;
    }
    const int64_t t2 = llama_time_us();

    llama_sampler_free(smpl);

    const long prompt_ms = (long) ((t1 - t0) / 1000);
    const long gen_ms = (long) ((t2 - t1) / 1000);
    const float tps = gen_ms > 0 ? (n_gen * 1000.0f / gen_ms) : 0.0f;

    std::string json = "{\"text\":\"" + json_escape(out) +
        "\",\"tokensGenerated\":" + std::to_string(n_gen) +
        ",\"promptTokens\":" + std::to_string(n_prompt) +
        ",\"tokensPerSecond\":" + std::to_string(tps) +
        ",\"promptEvalTimeMs\":" + std::to_string(prompt_ms) +
        ",\"generateTimeMs\":" + std::to_string(gen_ms) + "}";
    return 到jstring(env, json);
}

// ── 鸿胪寺扩展一：渲染（对齐 PC 版 `酒馆协议.渲染`）────────────────────
// roles/contents + 预填 → 模型真正吃掉的那一整串。
//   · 走模型自带 llama_chat_apply_template（多轮）
//   · 模板不吃 system 时**并进第一条 user** 再渲染（安装补偿）
//   · 预填接在渲染结果**末尾**（真正的续写起点）
// 渲染不了退成「role：content」朴素拼接，绝不静默返回空。
JNIEXPORT jstring JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeRender(
        JNIEnv *env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents, jstring prefill) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h || !h->ctx) return env->NewStringUTF("");
    auto 消息 = 取消息(env, roles, contents);
    std::string 串 = 渲染对话(h->model, 消息);
    if (串.empty()) {
        for (auto &p : 消息) {
            if (!串.empty()) 串 += "\n";
            串 += p.first + "：" + p.second;
        }
        串 += "\nassistant：";
    }
    return 到jstring(env, 串 + jstr(env, prefill));
}

// ── 鸿胪寺扩展二：生成（prompt 已是渲染好的整串）──────────────────────
// 保留绑定原有的 nativeComplete 不动，这里只做「加法」：
//   · prompt          已经渲染好的提示词（由 nativeRender 产出）
//   · stops           生成中检测的停止串（保留尾缓冲，防跨 token 漏判）
//   · bans            文字层禁吐（自定义采样器压掉候选）
//   · 惩罚            repeat/presence/frequency（小模型复读的头号解药）
//   · sink            每段增量回调 Kotlin，返回 false 立即打断
JNIEXPORT jstring JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeGenerate(
        JNIEnv *env, jobject, jlong handle,
        jstring prompt,
        jobjectArray stops, jobjectArray bans,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed,
        jfloat repeatPenalty, jint penaltyLastN, jfloat presencePenalty, jfloat frequencyPenalty,
        jobject sink) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h || !h->ctx) return env->NewStringUTF("{\"text\":\"\",\"error\":\"no context\"}");

    const llama_vocab *vocab = llama_model_get_vocab(h->model);

    // 料 = 已经渲染好的整串（含预填）
    const std::string 料 = jstr(env, prompt);

    // ④ 停止串 / 禁吐
    std::vector<std::string> 停们 = 取串数组(env, stops);
    std::vector<std::string> 禁们 = 取串数组(env, bans);
    int 停最长 = 0;
    for (auto &s : 停们) if ((int) s.size() > 停最长) 停最长 = (int) s.size();

    // ⑤ 采样器链：惩罚 → 禁吐 → 采样
    //    惩罚（repeat/presence/frequency）必须排在采样最前面 —— 小模型复读、跑偏
    //    主要靠它压。repeat_penalty 允许用户关（=1.0 视作不启用）。
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    bool 要惩罚 = (repeatPenalty > 0.0f && repeatPenalty != 1.0f)
               || presencePenalty != 0.0f || frequencyPenalty != 0.0f;
    if (要惩罚) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab),
                penaltyLastN > 0 ? penaltyLastN : 64,
                repeatPenalty > 0.0f ? repeatPenalty : 1.0f,
                frequencyPenalty, presencePenalty));
    }
    if (!禁们.empty()) llama_sampler_chain_add(smpl, 造禁吐(vocab, 禁们));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(
                seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }

    // ⑥ 回调准备
    jmethodID 吐法 = nullptr;
    if (sink) {
        jclass 槽类 = env->GetObjectClass(sink);
        吐法 = env->GetMethodID(槽类, "onPiece", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(槽类);
    }
    bool 取消 = false;
    std::string 发出;   // 已经吐给 UI 的正文（也是最终 text）
    auto 送 = [&](const std::string &段) -> bool {
        if (段.empty()) return true;
        发出 += 段;
        if (!sink || !吐法) return true;
        jstring js = 到jstring(env, 段);          // ⚠ 不能用 NewStringUTF（emoji 会坏）
        jboolean 继 = env->CallBooleanMethod(sink, 吐法, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return 继 == JNI_TRUE;
    };

    // ⑦ 喂料
    llama_memory_clear(llama_get_memory(h->ctx), true);
    std::vector<llama_token> tokens = tokenize(vocab, 料, true);
    const int n_prompt = (int) tokens.size();
    if (tokens.empty()) {
        llama_sampler_free(smpl);
        return env->NewStringUTF("{\"text\":\"\",\"error\":\"tokenize failed\"}");
    }
    const int64_t t0 = llama_time_us();
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        llama_sampler_free(smpl);
        return env->NewStringUTF("{\"text\":\"\",\"error\":\"decode failed\"}");
    }
    const int64_t t1 = llama_time_us();

    // ⑧ 生成（带停止串尾缓冲 + 回调）
    std::string 待吐;
    int n_gen = 0;
    for (; n_gen < maxTokens; n_gen++) {
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        llama_sampler_accept(smpl, id);
        待吐 += 片段(vocab, id);

        // 停止串：待吐里最早出现的位置
        size_t 切 = std::string::npos;
        for (auto &s : 停们) {
            size_t i = 待吐.find(s);
            if (i != std::string::npos && (切 == std::string::npos || i < 切)) 切 = i;
        }
        if (切 != std::string::npos) {
            送(待吐.substr(0, 切));
            待吐.clear();
            n_gen++;                 // 这一 token 也算生成过
            break;
        }
        // 两个"别切在中间"的约束：
        //   · 尾部留「停最长-1」字节，防止**停止串**被切成两半；
        //   · 只吐到**完整 UTF-8 字符**的边界 —— 一个汉字/emoji 可能被切成多个 token
        //     （Gemma 这类带字节回退的分词器尤其常见），按 token 边界直接转 jstring 会成乱码。
        const size_t 留 = (size_t) (停最长 > 1 ? 停最长 - 1 : 0);
        size_t 目标 = 待吐.size() > 留 ? 待吐.size() - 留 : 0;
        目标 = utf8安全长度(待吐, 目标);
        if (目标 > 0) {
            std::string 吐这 = 待吐.substr(0, 目标);
            待吐.erase(0, 目标);
            if (!送(吐这)) { 取消 = true; n_gen++; break; }
        }
        llama_batch nb = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, nb) != 0) break;
    }
    // 收尾：把还留着的吐掉；末尾若是个残缺的多字节序列（永远补不齐了）就丢掉，
    // 不要拿它去转字符串（那正是"最后一个字乱码"的来源）。
    if (!取消 && !待吐.empty()) {
        if (utf8安全长度(待吐, 待吐.size()) == 待吐.size()) 送(待吐);
    }
    const int64_t t2 = llama_time_us();

    llama_sampler_free(smpl);

    const long prompt_ms = (long) ((t1 - t0) / 1000);
    const long gen_ms = (long) ((t2 - t1) / 1000);
    const float tps = gen_ms > 0 ? (n_gen * 1000.0f / gen_ms) : 0.0f;

    std::string json = "{\"text\":\"" + json_escape(发出) +
        "\",\"tokensGenerated\":" + std::to_string(n_gen) +
        ",\"promptTokens\":" + std::to_string(n_prompt) +
        ",\"tokensPerSecond\":" + std::to_string(tps) +
        ",\"promptEvalTimeMs\":" + std::to_string(prompt_ms) +
        ",\"generateTimeMs\":" + std::to_string(gen_ms) +
        ",\"cancelled\":" + (取消 ? std::string("true") : std::string("false")) + "}";
    return 到jstring(env, json);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeEmbed(
        JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h || !h->ctx) return env->NewFloatArray(0);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    std::vector<llama_token> tokens = tokenize(vocab, jstr(env, text), true);
    const int n_tok = (int) tokens.size();

    // Embeddings must be explicitly enabled or llama_get_embeddings_* returns null.
    // Toggle it on for this call and restore generation mode afterwards.
    llama_set_embeddings(h->ctx, true);
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) n_tok);
    if (llama_decode(h->ctx, batch) != 0) {
        llama_set_embeddings(h->ctx, false);
        return env->NewFloatArray(0);
    }

    const int n_embd = llama_model_n_embd(h->model);
    // Pooled sequence embedding (embedding models); fall back to the last token's
    // hidden state (plain LLMs with no pooling configured).
    const float *emb = llama_get_embeddings_seq(h->ctx, 0);
    if (!emb) emb = llama_get_embeddings_ith(h->ctx, n_tok - 1);
    if (!emb) emb = llama_get_embeddings(h->ctx);
    llama_set_embeddings(h->ctx, false);
    if (!emb) return env->NewFloatArray(0);

    jfloatArray arr = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(arr, 0, n_embd, emb);
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeReleaseModel(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT jstring JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeGetSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// 设置一个进程环境变量。给 OpenCL 的 ICD loader 指路用（`OPENCL_VENDOR_PATH`）。
// ⚠ 必须在**第一次 OpenCL 调用之前**设（也就是第一次带 GPU 载入模型之前）。
JNIEXPORT void JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeSetEnv(JNIEnv *env, jobject, jstring key, jstring val) {
    const char *k = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char *v = val ? env->GetStringUTFChars(val, nullptr) : nullptr;
    if (k && v) setenv(k, v, 1);
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(val, v);
}

// 读模型的 GGUF 元数据（Gap 1：安卓这边认族要 `general.architecture`，
// 光有文件名是猜的）。取不到回空串。
JNIEXPORT jstring JNICALL
Java_dev_ffmpegkit_llama_LlamaJNI_nativeModelMeta(JNIEnv *env, jobject, jlong handle,
                                                  jstring key) {
    auto *h = reinterpret_cast<LlamaCtx *>(handle);
    if (!h || !h->model || !key) return env->NewStringUTF("");
    const char *k = env->GetStringUTFChars(key, nullptr);
    std::string v = k ? 元数据(h->model, k) : std::string();
    if (k) env->ReleaseStringUTFChars(key, k);
    return env->NewStringUTF(v.c_str());
}

} // extern "C"
