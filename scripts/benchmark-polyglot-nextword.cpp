#include <llama.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

constexpr int kBeamWidth = 16;
constexpr int kSuccessorsPerBeam = 8;
constexpr int kMaxGeneratedTokens = 8;
constexpr int kContextTokens = 512;
constexpr int kThreads = 4;
constexpr int kFirstBeamSequence = 1;
constexpr int kSecondBeamSequence = kFirstBeamSequence + kBeamWidth;

enum class DecodeMode { Sequential, Batched };

struct Options {
    std::string modelPath;
    std::string inputsPath;
    std::string outputPath;
    std::string sourceSha;
    std::string modelSha;
    std::vector<int> selectedPrefixIndexes;
    DecodeMode decodeMode = DecodeMode::Sequential;
    bool selfTest = false;
};

struct Path {
    int sequenceId = 0;
    std::vector<llama_token> tokenIds;
    std::string decoded;
    double rawScore = 0.0;
    std::vector<float> nextLogits;
    std::string termination;
    std::string rejection;
};

struct Successor {
    llama_token token;
    double logProbability;
};

enum class Utf8State { Valid, Incomplete, Malformed };

std::string jsonEscape(const std::string & value) {
    std::ostringstream out;
    for (const unsigned char ch : value) {
        switch (ch) {
        case '\\': out << "\\\\"; break;
        case '"': out << "\\\""; break;
        case '\b': out << "\\b"; break;
        case '\f': out << "\\f"; break;
        case '\n': out << "\\n"; break;
        case '\r': out << "\\r"; break;
        case '\t': out << "\\t"; break;
        default:
            if (ch < 0x20) {
                out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(ch)
                    << std::dec << std::setfill(' ');
            } else {
                out << static_cast<char>(ch);
            }
        }
    }
    return out.str();
}

void writeTokenIds(std::ostream & out, const std::vector<llama_token> & tokenIds) {
    out << '[';
    for (size_t i = 0; i < tokenIds.size(); ++i) {
        if (i != 0) out << ',';
        out << tokenIds[i];
    }
    out << ']';
}

std::string completedSurface(const Path & path);
Utf8State inspectUtf8(const std::string & value);
std::string rawBytesHex(const std::string & value);
std::optional<std::string> invalidTextReason(const std::string & value);

void writePath(std::ostream & out, const Path & path) {
    const bool hasValidUtf8 = inspectUtf8(path.decoded) == Utf8State::Valid;
    const std::string surface = completedSurface(path);
    const bool hasValidSurface = inspectUtf8(surface) == Utf8State::Valid && !invalidTextReason(surface);
    out << "{\"raw_generated_text\":";
    if (hasValidUtf8) out << '"' << jsonEscape(path.decoded) << '"';
    else out << "null";
    out << ",\"raw_generated_bytes_hex\":\"" << rawBytesHex(path.decoded)
        << "\",\"token_ids\":";
    writeTokenIds(out, path.tokenIds);
    out << ",\"cumulative_logprob\":" << std::setprecision(17) << path.rawScore
        << ",\"decoded_surface\":";
    if (hasValidSurface) out << '"' << jsonEscape(surface) << '"';
    else out << "null";
    out << ",\"termination_reason\":\"" << jsonEscape(path.termination) << '"';
    if (!path.rejection.empty()) {
        out << ",\"rejection_reason\":\"" << jsonEscape(path.rejection) << '"';
    }
    out << '}';
}

std::optional<std::string> jsonStringField(const std::string & line, const std::string & key) {
    const std::string needle = "\"" + key + "\"";
    size_t pos = line.find(needle);
    if (pos == std::string::npos) return std::nullopt;
    pos = line.find(':', pos + needle.size());
    if (pos == std::string::npos) return std::nullopt;
    pos = line.find('"', pos + 1);
    if (pos == std::string::npos) return std::nullopt;
    ++pos;
    std::string value;
    bool escaped = false;
    for (; pos < line.size(); ++pos) {
        const char ch = line[pos];
        if (escaped) {
            switch (ch) {
            case '"': value.push_back('"'); break;
            case '\\': value.push_back('\\'); break;
            case '/': value.push_back('/'); break;
            case 'b': value.push_back('\b'); break;
            case 'f': value.push_back('\f'); break;
            case 'n': value.push_back('\n'); break;
            case 'r': value.push_back('\r'); break;
            case 't': value.push_back('\t'); break;
            default: throw std::runtime_error("unsupported JSON escape in input corpus");
            }
            escaped = false;
            continue;
        }
        if (ch == '\\') {
            escaped = true;
        } else if (ch == '"') {
            return value;
        } else {
            value.push_back(ch);
        }
    }
    throw std::runtime_error("unterminated JSON string in input corpus");
}

std::vector<std::string> loadCorpusPrefixes(const std::string & path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open input corpus: " + path);
    std::vector<std::string> prefixes;
    std::string line;
    while (std::getline(input, line)) {
        const auto prefix = jsonStringField(line, "prefix");
        if (!prefix) throw std::runtime_error("input corpus line lacks prefix");
        prefixes.push_back(*prefix);
    }
    if (prefixes.size() != 12) {
        throw std::runtime_error("expected exactly 12 prefixes in the HF reference corpus");
    }
    return prefixes;
}

Options parseOptions(int argc, char ** argv) {
    Options options;
    for (int i = 1; i < argc; ++i) {
        const std::string argument = argv[i];
        const auto next = [&]() -> std::string {
            if (++i >= argc) throw std::runtime_error("missing value for " + argument);
            return argv[i];
        };
        if (argument == "--model") options.modelPath = next();
        else if (argument == "--inputs") options.inputsPath = next();
        else if (argument == "--output") options.outputPath = next();
        else if (argument == "--source-sha") options.sourceSha = next();
        else if (argument == "--model-sha") options.modelSha = next();
        else if (argument == "--prefix-index") {
            const std::string value = next();
            size_t parsedLength = 0;
            const int index = std::stoi(value, &parsedLength);
            if (parsedLength != value.size() || index < 0) {
                throw std::runtime_error("--prefix-index must be a non-negative integer");
            }
            options.selectedPrefixIndexes.push_back(index);
        }
        else if (argument == "--decode-mode") {
            const std::string value = next();
            if (value == "sequential") options.decodeMode = DecodeMode::Sequential;
            else if (value == "batched") options.decodeMode = DecodeMode::Batched;
            else throw std::runtime_error("--decode-mode must be sequential or batched");
        }
        else if (argument == "--self-test") options.selfTest = true;
        else throw std::runtime_error("unknown argument: " + argument);
    }
    if (!options.selfTest && (options.modelPath.empty() || options.inputsPath.empty() || options.outputPath.empty() ||
        options.sourceSha.empty() || options.modelSha.empty())) {
        throw std::runtime_error("--model, --inputs, --output, --source-sha, and --model-sha are required");
    }
    return options;
}

const char * decodeModeName(DecodeMode mode) {
    return mode == DecodeMode::Sequential ? "sequential" : "batched";
}

std::vector<std::string> splitAdapterDelimiter(const std::string & text) {
    constexpr std::string_view delimiter = "\xE0\xB3\xB1";
    std::vector<std::string> fragments;
    size_t start = 0;
    while (true) {
        const size_t next = text.find(delimiter, start);
        if (next == std::string::npos) {
            fragments.push_back(text.substr(start));
            return fragments;
        }
        fragments.push_back(text.substr(start, next - start));
        start = next + delimiter.size();
    }
}

std::vector<llama_token> tokenizeNoBos(const llama_vocab * vocab, const std::string & fragment) {
    std::vector<llama_token> tokens(std::max<size_t>(8, fragment.size() + 8));
    int32_t result = llama_tokenize(
        vocab,
        fragment.data(),
        static_cast<int32_t>(fragment.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        false,
        false);
    if (result < 0) {
        tokens.resize(static_cast<size_t>(-result));
        result = llama_tokenize(
            vocab,
            fragment.data(),
            static_cast<int32_t>(fragment.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            false,
            false);
    }
    if (result < 0) throw std::runtime_error("llama_tokenize failed");
    tokens.resize(static_cast<size_t>(result));
    return tokens;
}

std::vector<llama_token> tokenizeAdapterInput(const llama_vocab * vocab, const std::string & prefix) {
    std::vector<llama_token> tokens;
    for (const auto & fragment : splitAdapterDelimiter(prefix)) {
        auto fragmentTokens = tokenizeNoBos(vocab, fragment);
        tokens.insert(tokens.end(), fragmentTokens.begin(), fragmentTokens.end());
    }
    if (tokens.empty()) throw std::runtime_error("adapter tokenization produced no prompt tokens");
    return tokens;
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (length < 0) throw std::runtime_error("llama_token_to_piece failed");
    return std::string(buffer.data(), static_cast<size_t>(length));
}

bool isWhitespaceByte(unsigned char byte) {
    return byte == ' ' || byte == '\n' || byte == '\r' || byte == '\t' || byte == '\f' || byte == '\v';
}

Utf8State inspectUtf8(const std::string & value) {
    size_t offset = 0;
    while (offset < value.size()) {
        const auto lead = static_cast<unsigned char>(value[offset++]);
        if (lead < 0x80) continue;
        int continuationCount = 0;
        uint32_t result = 0;
        if ((lead & 0xE0) == 0xC0) { continuationCount = 1; result = lead & 0x1F; }
        else if ((lead & 0xF0) == 0xE0) { continuationCount = 2; result = lead & 0x0F; }
        else if ((lead & 0xF8) == 0xF0) { continuationCount = 3; result = lead & 0x07; }
        else return Utf8State::Malformed;
        if (offset + static_cast<size_t>(continuationCount) > value.size()) return Utf8State::Incomplete;
        for (int i = 0; i < continuationCount; ++i) {
            const auto next = static_cast<unsigned char>(value[offset++]);
            if ((next & 0xC0) != 0x80) return Utf8State::Malformed;
            result = (result << 6) | (next & 0x3F);
        }
        const uint32_t minimum = continuationCount == 1 ? 0x80 : continuationCount == 2 ? 0x800 : 0x10000;
        if (result < minimum || result > 0x10FFFF || (result >= 0xD800 && result <= 0xDFFF)) {
            return Utf8State::Malformed;
        }
    }
    return Utf8State::Valid;
}

std::optional<std::string> invalidTextReason(const std::string & value) {
    size_t offset = 0;
    while (offset < value.size()) {
        const auto lead = static_cast<unsigned char>(value[offset++]);
        uint32_t codePoint = lead;
        if (lead >= 0x80) {
            int continuationCount = 0;
            if ((lead & 0xE0) == 0xC0) { continuationCount = 1; codePoint = lead & 0x1F; }
            else if ((lead & 0xF0) == 0xE0) { continuationCount = 2; codePoint = lead & 0x0F; }
            else { continuationCount = 3; codePoint = lead & 0x07; }
            for (int i = 0; i < continuationCount; ++i) {
                const auto next = static_cast<unsigned char>(value[offset++]);
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
        }
        if (codePoint == 0xFFFD) return "replacement_character";
        if ((codePoint <= 0x1F && !isWhitespaceByte(static_cast<unsigned char>(codePoint))) ||
            (codePoint >= 0x7F && codePoint <= 0x9F)) return "control_character";
        if (codePoint == 0x200B || codePoint == 0xFEFF) return "format_character";
    }
    return std::nullopt;
}

std::string rawBytesHex(const std::string & value) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (const unsigned char byte : value) out << std::setw(2) << static_cast<unsigned int>(byte);
    return out.str();
}

enum class PathState { Active, Completed, Invalid };

PathState classifyPath(const llama_vocab * vocab, Path & path, bool atTokenLimit) {
    if (path.decoded.empty()) return PathState::Active;
    if (!isWhitespaceByte(static_cast<unsigned char>(path.decoded.front()))) {
        const Utf8State utf8State = inspectUtf8(path.decoded);
        path.termination = "invalid";
        path.rejection = utf8State == Utf8State::Malformed ? "invalid_utf8" : "missing_leading_whitespace";
        return PathState::Invalid;
    }
    size_t surfaceStart = 0;
    while (surfaceStart < path.decoded.size() && isWhitespaceByte(static_cast<unsigned char>(path.decoded[surfaceStart]))) ++surfaceStart;
    if (surfaceStart == path.decoded.size()) {
        if (!path.tokenIds.empty() && llama_vocab_is_eog(vocab, path.tokenIds.back())) {
            path.termination = "invalid";
            path.rejection = "empty_eojeol";
            return PathState::Invalid;
        }
        return PathState::Active;
    }
    if (path.decoded[surfaceStart] == '.' || path.decoded[surfaceStart] == '?' || path.decoded[surfaceStart] == '!') {
        path.termination = "invalid";
        path.rejection = "leading_punctuation";
        return PathState::Invalid;
    }
    size_t boundary = path.decoded.size();
    std::string termination;
    for (size_t i = surfaceStart; i < path.decoded.size(); ++i) {
        const unsigned char byte = static_cast<unsigned char>(path.decoded[i]);
        if (isWhitespaceByte(byte)) {
            boundary = i;
            termination = "whitespace";
            break;
        }
        if (byte == '.' || byte == '?' || byte == '!') {
            boundary = i;
            termination = "sentence_punctuation";
            break;
        }
    }
    const bool reachedEog = !path.tokenIds.empty() && llama_vocab_is_eog(vocab, path.tokenIds.back());
    const std::string surface = path.decoded.substr(surfaceStart, boundary - surfaceStart);
    const Utf8State surfaceUtf8 = inspectUtf8(surface);
    if (surfaceUtf8 == Utf8State::Malformed) {
        path.termination = "invalid";
        path.rejection = "invalid_utf8";
        return PathState::Invalid;
    }
    if (surfaceUtf8 == Utf8State::Incomplete) {
        if (termination.empty() && !reachedEog && !atTokenLimit) return PathState::Active;
        path.termination = "invalid";
        path.rejection = reachedEog ? "incomplete_utf8_at_eos" : atTokenLimit ? "incomplete_utf8_at_max_generated_tokens" : "incomplete_utf8";
        return PathState::Invalid;
    }
    if (const auto invalid = invalidTextReason(surface)) {
        path.termination = "invalid";
        path.rejection = *invalid;
        return PathState::Invalid;
    }
    if (!termination.empty()) {
        path.termination = termination;
        return PathState::Completed;
    }
    if (reachedEog) {
        path.termination = "eos";
        return PathState::Completed;
    }
    if (atTokenLimit) return PathState::Active;
    return PathState::Active;
}

void requireSelfTest(bool condition, const std::string & message) {
    if (!condition) throw std::runtime_error("self-test failed: " + message);
}

void runSelfTest() {
    Path incomplete;
    incomplete.decoded = std::string(" \xEA", 2);
    requireSelfTest(classifyPath(nullptr, incomplete, false) == PathState::Active, "incomplete UTF-8 tail must remain active");
    Path incompleteAtLimit;
    incompleteAtLimit.decoded = std::string(" \xEA", 2);
    requireSelfTest(classifyPath(nullptr, incompleteAtLimit, true) == PathState::Invalid, "incomplete UTF-8 tail at the token limit must be invalid");
    Path malformedAfterLeadingWhitespace;
    malformedAfterLeadingWhitespace.decoded = std::string(" \xFF", 2);
    requireSelfTest(classifyPath(nullptr, malformedAfterLeadingWhitespace, false) == PathState::Invalid, "malformed UTF-8 after leading whitespace must be invalid");
    incomplete.decoded.push_back(static_cast<char>(0xB0));
    incomplete.decoded.push_back(static_cast<char>(0x80));
    requireSelfTest(classifyPath(nullptr, incomplete, false) == PathState::Active, "valid no-boundary eojeol must remain active");
    incomplete.decoded.push_back(' ');
    incomplete.decoded.push_back(static_cast<char>(0xEA));
    requireSelfTest(classifyPath(nullptr, incomplete, false) == PathState::Completed, "completed UTF-8 eojeol must complete before a trailing partial token");
    std::ostringstream trailingPartial;
    writePath(trailingPartial, incomplete);
    requireSelfTest(trailingPartial.str().find("\"raw_generated_text\":null") != std::string::npos, "trailing partial UTF-8 must not enter JSON text");
    requireSelfTest(trailingPartial.str().find("\"decoded_surface\":\"\xEA\xB0\x80\"") != std::string::npos, "completed surface must survive a trailing partial token");

    Path newline;
    newline.decoded = " word\n";
    requireSelfTest(classifyPath(nullptr, newline, false) == PathState::Completed, "newline must complete an eojeol");
    Path tab;
    tab.decoded = " word\t";
    requireSelfTest(classifyPath(nullptr, tab, false) == PathState::Completed, "tab must complete an eojeol");

    Path malformed;
    malformed.decoded = std::string("\xFF", 1);
    requireSelfTest(classifyPath(nullptr, malformed, false) == PathState::Invalid, "malformed UTF-8 must be invalid");
    std::ostringstream serialized;
    writePath(serialized, malformed);
    const std::string json = serialized.str();
    requireSelfTest(json.find("\"raw_generated_text\":null") != std::string::npos, "invalid UTF-8 must not enter JSON text");
    requireSelfTest(json.find("\"raw_generated_bytes_hex\":\"ff\"") != std::string::npos, "invalid UTF-8 bytes must be preserved");
    std::cout << "{\"status\":\"self_test_passed\",\"invalid_path\":" << json << "}" << std::endl;
}

std::string completedSurface(const Path & path) {
    size_t start = 0;
    while (start < path.decoded.size() && isWhitespaceByte(static_cast<unsigned char>(path.decoded[start]))) ++start;
    size_t end = start;
    while (end < path.decoded.size()) {
        const unsigned char byte = static_cast<unsigned char>(path.decoded[end]);
        if (isWhitespaceByte(byte) || byte == '.' || byte == '?' || byte == '!') break;
        ++end;
    }
    return path.decoded.substr(start, end - start);
}

std::vector<float> decodeBatch(
    llama_context * context,
    const std::vector<llama_token> & tokens,
    llama_seq_id sequenceId,
    llama_pos startPosition) {
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = startPosition + static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = sequenceId;
        batch.logits[i] = i + 1 == tokens.size();
    }
    const int32_t status = llama_decode(context, batch);
    llama_batch_free(batch);
    if (status != 0) throw std::runtime_error("llama_decode failed with status " + std::to_string(status));
    const llama_vocab * vocab = llama_model_get_vocab(llama_get_model(context));
    const int32_t vocabSize = llama_vocab_n_tokens(vocab);
    const float * logits = llama_get_logits_ith(context, -1);
    if (logits == nullptr) throw std::runtime_error("llama_decode did not expose logits");
    return std::vector<float>(logits, logits + vocabSize);
}

void decodePendingBatch(
    llama_context * context,
    std::vector<Path> & pending,
    size_t promptTokenCount) {
    if (pending.empty()) return;
    llama_batch batch = llama_batch_init(static_cast<int32_t>(pending.size()), 0, 1);
    batch.n_tokens = static_cast<int32_t>(pending.size());
    for (size_t i = 0; i < pending.size(); ++i) {
        batch.token[i] = pending[i].tokenIds.back();
        batch.pos[i] = static_cast<llama_pos>(promptTokenCount + pending[i].tokenIds.size() - 1);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = pending[i].sequenceId;
        batch.logits[i] = true;
    }
    const int32_t status = llama_decode(context, batch);
    if (status != 0) {
        llama_batch_free(batch);
        throw std::runtime_error("batched llama_decode failed with status " + std::to_string(status));
    }
    const llama_vocab * vocab = llama_model_get_vocab(llama_get_model(context));
    const int32_t vocabSize = llama_vocab_n_tokens(vocab);
    for (size_t i = 0; i < pending.size(); ++i) {
        const float * logits = llama_get_logits_ith(context, static_cast<int32_t>(i));
        if (logits == nullptr) {
            llama_batch_free(batch);
            throw std::runtime_error("batched llama_decode did not expose logits");
        }
        pending[i].nextLogits.assign(logits, logits + vocabSize);
    }
    llama_batch_free(batch);
}

std::vector<Successor> topSuccessors(const std::vector<float> & logits) {
    if (logits.empty()) throw std::runtime_error("empty logits");
    const float maximum = *std::max_element(logits.begin(), logits.end());
    double total = 0.0;
    for (const float logit : logits) total += std::exp(static_cast<double>(logit - maximum));
    const double logNormalizer = static_cast<double>(maximum) + std::log(total);
    std::vector<int32_t> indices(logits.size());
    for (size_t i = 0; i < indices.size(); ++i) indices[i] = static_cast<int32_t>(i);
    std::partial_sort(
        indices.begin(),
        indices.begin() + std::min<size_t>(kSuccessorsPerBeam, indices.size()),
        indices.end(),
        [&logits](int32_t left, int32_t right) { return logits[left] > logits[right]; });
    std::vector<Successor> successors;
    for (size_t i = 0; i < std::min<size_t>(kSuccessorsPerBeam, indices.size()); ++i) {
        const int32_t id = indices[i];
        successors.push_back({id, static_cast<double>(logits[id]) - logNormalizer});
    }
    return successors;
}

std::vector<Path> topDistinctCompleted(std::vector<Path> completed) {
    std::sort(completed.begin(), completed.end(), [](const Path & left, const Path & right) {
        return left.rawScore > right.rawScore;
    });
    std::unordered_set<std::string> seen;
    std::vector<Path> selected;
    for (const auto & path : completed) {
        const std::string surface = completedSurface(path);
        if (surface.empty() || !seen.insert(surface).second) continue;
        selected.push_back(path);
        if (selected.size() == 4) break;
    }
    return selected;
}

void writePathArray(std::ostream & out, const std::vector<Path> & paths) {
    out << '[';
    for (size_t i = 0; i < paths.size(); ++i) {
        if (i != 0) out << ',';
        writePath(out, paths[i]);
    }
    out << ']';
}

void runPrefix(
    llama_context * context,
    const llama_vocab * vocab,
    const std::string & prefix,
    DecodeMode decodeMode,
    std::ostream & out) {
    const auto started = std::chrono::steady_clock::now();
    const auto promptTokens = tokenizeAdapterInput(vocab, prefix);
    const size_t peakKvTokens = promptTokens.size() + 2 * kBeamWidth * (promptTokens.size() + kMaxGeneratedTokens) - kBeamWidth;
    if (peakKvTokens > kContextTokens) {
        throw std::runtime_error("prompt exceeds the unified KV context budget for the configured beam banks");
    }
    llama_memory_t memory = llama_get_memory(context);
    llama_memory_clear(memory, true);
    Path root;
    root.sequenceId = 0;
    root.nextLogits = decodeBatch(context, promptTokens, 0, 0);
    std::vector<Path> active{std::move(root)};
    std::vector<Path> completed;
    std::vector<Path> truncated;
    std::vector<Path> invalid;
    std::vector<Path> pruned;

    for (int generation = 0; generation < kMaxGeneratedTokens && !active.empty(); ++generation) {
        std::vector<Path> pending;
        for (const auto & parent : active) {
            for (const auto & successor : topSuccessors(parent.nextLogits)) {
                Path child;
                child.tokenIds = parent.tokenIds;
                child.tokenIds.push_back(successor.token);
                child.decoded = parent.decoded;
                if (!llama_vocab_is_eog(vocab, successor.token)) child.decoded += tokenPiece(vocab, successor.token);
                child.rawScore = parent.rawScore + successor.logProbability;
                const PathState state = classifyPath(vocab, child, generation + 1 == kMaxGeneratedTokens);
                if (state == PathState::Completed) {
                    completed.push_back(std::move(child));
                } else if (state == PathState::Invalid) {
                    invalid.push_back(std::move(child));
                } else if (generation + 1 == kMaxGeneratedTokens) {
                    child.termination = "max_generated_tokens";
                    truncated.push_back(std::move(child));
                } else {
                    pending.push_back(std::move(child));
                }
            }
        }
        std::sort(pending.begin(), pending.end(), [](const Path & left, const Path & right) {
            return left.rawScore > right.rawScore;
        });
        if (pending.size() > kBeamWidth) {
            pruned.insert(pruned.end(), pending.begin() + kBeamWidth, pending.end());
            pending.resize(kBeamWidth);
        }
        const int destinationBase = generation % 2 == 0 ? kFirstBeamSequence : kSecondBeamSequence;
        for (size_t i = 0; i < pending.size(); ++i) {
            const Path & child = pending[i];
            const Path * source = nullptr;
            for (const auto & candidateParent : active) {
                const size_t parentTokenCount = candidateParent.tokenIds.size();
                if (child.tokenIds.size() == parentTokenCount + 1 &&
                    std::equal(candidateParent.tokenIds.begin(), candidateParent.tokenIds.end(), child.tokenIds.begin())) {
                    source = &candidateParent;
                    break;
                }
            }
            if (source == nullptr) throw std::runtime_error("beam parent lookup failed");
            const llama_seq_id destination = destinationBase + static_cast<llama_seq_id>(i);
            llama_memory_seq_rm(memory, destination, 0, -1);
            llama_memory_seq_cp(memory, source->sequenceId, destination, 0, -1);
            pending[i].sequenceId = destination;
            if (decodeMode == DecodeMode::Sequential) {
                pending[i].nextLogits = decodeBatch(
                    context,
                    {pending[i].tokenIds.back()},
                    destination,
                    static_cast<llama_pos>(promptTokens.size() + pending[i].tokenIds.size() - 1));
            }
        }
        if (decodeMode == DecodeMode::Batched) {
            decodePendingBatch(context, pending, promptTokens.size());
        }
        for (const auto & parent : active) {
            if (parent.sequenceId != 0) llama_memory_seq_rm(memory, parent.sequenceId, 0, -1);
        }
        active = std::move(pending);
    }
    const auto finished = std::chrono::steady_clock::now();
    const double elapsedMs = std::chrono::duration<double, std::milli>(finished - started).count();
    const auto top4 = topDistinctCompleted(completed);
    out << "{\"prefix\":\"" << jsonEscape(prefix) << "\",\"input_token_ids\":";
    writeTokenIds(out, promptTokens);
    out << ",\"kv_peak_token_upper_bound\":" << peakKvTokens
        << ",\"latency_ms\":" << std::setprecision(17) << elapsedMs
        << ",\"top4_distinct_completed\":";
    writePathArray(out, top4);
    out << ",\"completed_paths\":";
    writePathArray(out, completed);
    out << ",\"truncated_paths\":";
    writePathArray(out, truncated);
    out << ",\"invalid_paths\":";
    writePathArray(out, invalid);
    out << ",\"pruned_paths\":";
    writePathArray(out, pruned);
    out << '}';
}

} // namespace

int main(int argc, char ** argv) {
    try {
        const Options options = parseOptions(argc, argv);
        if (options.selfTest) {
            runSelfTest();
            return 0;
        }
        const auto corpus = loadCorpusPrefixes(options.inputsPath);
        std::vector<std::string> prefixes;
        if (options.selectedPrefixIndexes.empty()) {
            prefixes = corpus;
        } else {
            prefixes.reserve(options.selectedPrefixIndexes.size());
            for (const int index : options.selectedPrefixIndexes) {
                if (static_cast<size_t>(index) >= corpus.size()) {
                    throw std::runtime_error("--prefix-index is outside the HF reference corpus");
                }
                prefixes.push_back(corpus[static_cast<size_t>(index)]);
            }
        }
        llama_backend_init();
        llama_model_params modelParameters = llama_model_default_params();
        modelParameters.n_gpu_layers = 0;
        llama_model * model = llama_model_load_from_file(options.modelPath.c_str(), modelParameters);
        if (model == nullptr) throw std::runtime_error("could not load GGUF model");
        llama_context_params contextParameters = llama_context_default_params();
        contextParameters.n_ctx = kContextTokens;
        contextParameters.n_batch = kContextTokens;
        contextParameters.n_ubatch = kContextTokens;
        contextParameters.n_seq_max = 33;
        contextParameters.kv_unified = true;
        contextParameters.n_threads = kThreads;
        contextParameters.n_threads_batch = kThreads;
        llama_context * context = llama_init_from_model(model, contextParameters);
        if (context == nullptr) {
            llama_model_free(model);
            throw std::runtime_error("could not initialize llama context");
        }
        const llama_vocab * vocab = llama_model_get_vocab(model);
        std::ostringstream results;
        results << '[';
        for (size_t i = 0; i < prefixes.size(); ++i) {
            if (i != 0) results << ',';
            runPrefix(context, vocab, prefixes[i], options.decodeMode, results);
        }
        results << ']';
        std::ofstream output(options.outputPath);
        if (!output) throw std::runtime_error("cannot open output path");
        output << "{\"status\":\"completed\",\"source_sha\":\"" << jsonEscape(options.sourceSha)
            << "\",\"model_sha\":\"" << jsonEscape(options.modelSha)
            << "\",\"model_path\":\"" << jsonEscape(options.modelPath)
            << "\",\"adapter_preprocessing\":\"split U+0CF1, tokenize each fragment independently, concatenate token IDs, no BOS\""
            << ",\"decoder\":{\"beam_width\":16,\"top_successors\":8,\"max_generated_tokens\":8,\"score\":\"cumulative_logsoftmax_without_length_normalization\",\"kv_reuse\":true,\"kv_unified\":true,\"decode_mode\":\"" << decodeModeName(options.decodeMode) << "\",\"n_ctx\":512,\"n_batch\":512,\"n_threads\":4,\"n_seq_max\":33}"
            << ",\"results\":" << results.str() << '}' << std::endl;
        llama_free(context);
        llama_model_free(model);
        llama_backend_free();
        return 0;
    } catch (const std::exception & error) {
        std::cerr << "benchmark-polyglot-nextword: " << error.what() << std::endl;
        llama_backend_free();
        return 1;
    }
}
