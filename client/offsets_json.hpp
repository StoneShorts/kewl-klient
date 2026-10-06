// offsets_json.hpp -- load the per-build offset file into the variables offsets.hpp declares.
//
// offsets.hpp carries one value per number and the note that says how it was found. Those values are
// the DEFAULTS, measured on the build named by BUILD_VERSION. At start-up the DLL reads the host
// osclient.exe's version resource and looks for offsets/client-<version>.json next to itself; if the
// file is there, every number in it replaces the default, and the client runs on a build the DLL was
// never compiled for. If it is not there, the DLL asks the project's GitHub repository for it once
// (the derivation pipeline publishes a file per build) and caches it beside the DLL.
//
// The file format is deliberately flat:
//
//   {
//     "build":   "241-3",
//     "sha256":  "<osclient.exe digest the numbers were derived from>",
//     "offsets": { "CLIENT_OBJ_PTR": { "value": 15290984, "status": "derived", "evidence": "..." }, ... }
//   }
//
// Unknown keys are ignored (a newer pipeline may know more than this DLL); missing keys keep their
// compiled default and are reported, because a default from another build is exactly the silent
// wrong-read this whole arrangement exists to prevent. A key whose status is "missing" leaves the
// default too, and is reported as missing rather than applied.
#pragma once
#include <windows.h>
#include <winhttp.h>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "log.hpp"
#include "offsets.hpp"

namespace oxc::offjson {

// ---------------------------------------------------------------------------------------------------
// A JSON reader just big enough for the offsets file: objects, strings, numbers, true/false/null.
// ---------------------------------------------------------------------------------------------------
struct Value {
    enum Kind { Null, Bool, Number, String, Object, Array } kind = Null;
    bool b = false;
    double num = 0;
    std::string str;
    std::map<std::string, Value> obj;
    std::vector<Value> arr;

    const Value* get(const std::string& k) const {
        auto it = obj.find(k);
        return it == obj.end() ? nullptr : &it->second;
    }
};

class Parser {
public:
    explicit Parser(const std::string& s) : s_(s) {}
    bool parse(Value& out, std::string& err) {
        skip();
        if (!value(out, err)) return false;
        skip();
        if (i_ != s_.size()) { err = "trailing characters at " + std::to_string(i_); return false; }
        return true;
    }

private:
    const std::string& s_;
    size_t i_ = 0;

    void skip() { while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\n' || s_[i_] == '\r' || s_[i_] == '\t')) ++i_; }

    bool value(Value& v, std::string& err) {
        if (i_ >= s_.size()) { err = "unexpected end"; return false; }
        char c = s_[i_];
        if (c == '{') return object(v, err);
        if (c == '[') return array(v, err);
        if (c == '"') { v.kind = Value::String; return string(v.str, err); }
        if (c == 't' && s_.compare(i_, 4, "true") == 0) { v.kind = Value::Bool; v.b = true; i_ += 4; return true; }
        if (c == 'f' && s_.compare(i_, 5, "false") == 0) { v.kind = Value::Bool; v.b = false; i_ += 5; return true; }
        if (c == 'n' && s_.compare(i_, 4, "null") == 0) { v.kind = Value::Null; i_ += 4; return true; }
        if (c == '-' || (c >= '0' && c <= '9')) {
            size_t start = i_;
            while (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+' || s_[i_] == '.' || s_[i_] == 'e' || s_[i_] == 'E' || (s_[i_] >= '0' && s_[i_] <= '9'))) ++i_;
            v.kind = Value::Number;
            v.num = std::strtod(s_.c_str() + start, nullptr);
            return true;
        }
        err = "unexpected character '" + std::string(1, c) + "' at " + std::to_string(i_);
        return false;
    }

    bool string(std::string& out, std::string& err) {
        ++i_;  // opening quote
        while (i_ < s_.size()) {
            char c = s_[i_++];
            if (c == '"') return true;
            if (c == '\\') {
                if (i_ >= s_.size()) break;
                char e = s_[i_++];
                switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    if (i_ + 4 > s_.size()) { err = "bad \\u escape"; return false; }
                    unsigned cp = std::strtoul(s_.substr(i_, 4).c_str(), nullptr, 16);
                    i_ += 4;
                    if (cp < 0x80) out += static_cast<char>(cp);
                    else if (cp < 0x800) { out += static_cast<char>(0xC0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
                    else { out += static_cast<char>(0xE0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
                    break;
                }
                default: out += e; break;
                }
            } else {
                out += c;
            }
        }
        err = "unterminated string";
        return false;
    }

    bool object(Value& v, std::string& err) {
        v.kind = Value::Object;
        ++i_;
        skip();
        if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
        while (true) {
            skip();
            if (i_ >= s_.size() || s_[i_] != '"') { err = "expected key at " + std::to_string(i_); return false; }
            std::string key;
            if (!string(key, err)) return false;
            skip();
            if (i_ >= s_.size() || s_[i_] != ':') { err = "expected ':' at " + std::to_string(i_); return false; }
            ++i_;
            skip();
            Value child;
            if (!value(child, err)) return false;
            v.obj[key] = std::move(child);
            skip();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
            err = "expected ',' or '}' at " + std::to_string(i_);
            return false;
        }
    }

    bool array(Value& v, std::string& err) {
        v.kind = Value::Array;
        ++i_;
        skip();
        if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
        while (true) {
            skip();
            Value child;
            if (!value(child, err)) return false;
            v.arr.push_back(std::move(child));
            skip();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
            err = "expected ',' or ']' at " + std::to_string(i_);
            return false;
        }
    }
};

// ---------------------------------------------------------------------------------------------------
// The variable table: every name in offsets.hpp, generated into offsets_table.inc.
// ---------------------------------------------------------------------------------------------------
struct Slot {
    const char* name;
    std::uintptr_t* u;   // one of these two is set, by kind
    int* i;
};

inline const std::vector<Slot>& slots() {
    static const std::vector<Slot> table = {
#define OXC_OFFSET_uintptr(NAME) Slot{#NAME, &oxc::off::NAME, nullptr},
#define OXC_OFFSET_int(NAME)     Slot{#NAME, nullptr, &oxc::off::NAME},
#define OXC_OFFSET(KIND, NAME)   OXC_OFFSET_##KIND(NAME)
#include "offsets_table.inc"
#undef OXC_OFFSET
#undef OXC_OFFSET_int
#undef OXC_OFFSET_uintptr
    };
    return table;
}

struct Report {
    bool loaded = false;         // a file for this build was read and applied
    std::string build;           // the build the file names
    std::string source;          // "file" or "download"
    int applied = 0, missing = 0, unknown = 0;
    std::vector<std::string> missingNames;
    std::string error;           // why loading failed, when it did
};

// Where the per-build files live: offsets/ beside the DLL. `dir` is the DLL's directory.
inline std::wstring offsetsPath(const std::wstring& dir, const std::wstring& version) {
    return dir + L"\\offsets\\client-" + version + L".json";
}

inline bool readFile(const std::wstring& path, std::string& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::ostringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

// One GET over WinHTTP, into `out`. Returns false on any failure with the reason in `err`. Kept small:
// no redirects beyond what WinHTTP follows itself, no auth, no retries -- the caller decides.
inline bool httpGet(const std::wstring& host, const std::wstring& path, std::string& out, std::string& err) {
    HINTERNET s = WinHttpOpen(L"0xClient/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!s) { err = "WinHttpOpen failed"; return false; }
    HINTERNET c = WinHttpConnect(s, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!c) { WinHttpCloseHandle(s); err = "WinHttpConnect failed"; return false; }
    HINTERNET r = WinHttpOpenRequest(c, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    bool ok = false;
    if (r && WinHttpSendRequest(r, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) && WinHttpReceiveResponse(r, nullptr)) {
        DWORD status = 0, len = sizeof status;
        WinHttpQueryHeaders(r, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status, &len, WINHTTP_NO_HEADER_INDEX);
        if (status == 200) {
            DWORD avail = 0;
            while (WinHttpQueryDataAvailable(r, &avail) && avail > 0) {
                std::string chunk(avail, '\0');
                DWORD got = 0;
                if (!WinHttpReadData(r, chunk.data(), avail, &got)) break;
                out.append(chunk.data(), got);
            }
            ok = true;
        } else {
            err = "HTTP " + std::to_string(status);
        }
    } else {
        err = "request failed (" + std::to_string(GetLastError()) + ")";
    }
    if (r) WinHttpCloseHandle(r);
    WinHttpCloseHandle(c);
    WinHttpCloseHandle(s);
    return ok;
}

// Apply a parsed file to the variables. Reports what was set, what the file did not know, and what
// the file knew that this DLL does not.
inline bool apply(const Value& root, Report& rep) {
    const Value* build = root.get("build");
    const Value* offsets = root.get("offsets");
    if (!build || build->kind != Value::String || !offsets || offsets->kind != Value::Object) {
        rep.error = "offsets file is missing \"build\" or \"offsets\"";
        return false;
    }
    rep.build = build->str;
    for (const Slot& s : slots()) {
        const Value* entry = offsets->get(s.name);
        if (!entry) { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        const Value* v = entry->kind == Value::Object ? entry->get("value") : entry;
        const Value* st = entry->kind == Value::Object ? entry->get("status") : nullptr;
        if (st && st->kind == Value::String && st->str == "missing") { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        if (!v || v->kind != Value::Number) { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        if (s.u) *s.u = static_cast<std::uintptr_t>(v->num);
        else if (s.i) *s.i = static_cast<int>(v->num);
        ++rep.applied;
    }
    for (const auto& kv : offsets->obj) {
        bool known = false;
        for (const Slot& s : slots()) if (kv.first == s.name) { known = true; break; }
        if (!known) ++rep.unknown;
    }
    oxc::off::BUILD_VERSION = std::wstring(rep.build.begin(), rep.build.end());
    // The one relationship the code relies on across two names: the NxtString flag byte sits 0x17
    // past the string. A file that breaks it is reporting two different structs, so say so.
    if (oxc::off::IFTYPE_TEXT_FLAG != oxc::off::IFTYPE_TEXT + 0x17)
        oxc::logf("[offsets] WARNING: IFTYPE_TEXT_FLAG (0x%llx) is not IFTYPE_TEXT+0x17 (0x%llx) in this file\n",
                  (unsigned long long)oxc::off::IFTYPE_TEXT_FLAG, (unsigned long long)oxc::off::IFTYPE_TEXT);
    return true;
}

// Load offsets for `version` ("241-3"): the file beside the DLL first, then one download from the
// repository, cached beside the DLL for next time. Never throws; the report says what happened.
inline Report load(const std::wstring& dllDir, const std::wstring& version) {
    Report rep;
    const std::wstring path = offsetsPath(dllDir, version);
    std::string text;
    if (readFile(path, text)) {
        rep.source = "file";
    } else {
        std::string err;
        const std::wstring remote = L"/StoneShorts/0xClient/main/offsets/client-" + version + L".json";
        if (!httpGet(L"raw.githubusercontent.com", remote, text, err)) {
            std::string ver;
            for (wchar_t ch : version) ver += (ch < 128 ? static_cast<char>(ch) : '?');
            rep.error = "no offsets/client-" + ver + ".json beside the DLL, and the download failed: " + err;
            return rep;
        }
        rep.source = "download";
        CreateDirectoryW((dllDir + L"\\offsets").c_str(), nullptr);
        std::ofstream f(path, std::ios::binary);
        f << text;
    }
    Value root;
    std::string err;
    if (!Parser(text).parse(root, err)) {
        rep.error = "offsets file does not parse: " + err;
        return rep;
    }
    if (!apply(root, rep)) return rep;
    rep.loaded = true;
    return rep;
}

}  // namespace oxc::offjson
