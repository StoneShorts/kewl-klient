#include "account_store.hpp"

#include <windows.h>
#include <objbase.h>

#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <sstream>

namespace {
std::string Quote(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '\\') out += "\\\\";
        else if (c == '"') out += "\\\"";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else out += static_cast<char>(c);
    }
    return out + '"';
}
std::string StringField(const std::string& object, const char* key) {
    const std::string marker = std::string("\"") + key + "\"";
    const auto keyPos = object.find(marker); if (keyPos == std::string::npos) return {};
    auto p = object.find(':', keyPos + marker.size()); if (p == std::string::npos) return {};
    while (++p < object.size() && (object[p] == ' ' || object[p] == '\t')) {}
    if (p >= object.size() || object[p] != '"') return {};
    ++p; std::string value;
    while (p < object.size() && object[p] != '"') {
        if (object[p] == '\\' && p + 1 < object.size()) {
            const char escaped = object[++p];
            value += escaped == 'n' ? '\n' : escaped == 'r' ? '\r' : escaped == 't' ? '\t' : escaped;
        } else value += object[p];
        ++p;
    }
    return value;
}
std::int64_t IntField(const std::string& object, const char* key) {
    const std::string marker = std::string("\"") + key + "\"";
    const auto keyPos = object.find(marker); if (keyPos == std::string::npos) return 0;
    const auto p = object.find(':', keyPos + marker.size()); if (p == std::string::npos) return 0;
    return std::strtoll(object.c_str() + p + 1, nullptr, 10);
}
bool BoolField(const std::string& object, const char* key) {
    const std::string marker = std::string("\"") + key + "\"";
    const auto keyPos = object.find(marker); if (keyPos == std::string::npos) return true;
    const auto p = object.find(':', keyPos + marker.size()); if (p == std::string::npos) return true;
    return object.compare(p + 1, 4, "true") == 0;
}
std::vector<std::string> ArrayObjects(const std::string& json, const char* key) {
    std::vector<std::string> result;
    const std::string marker = std::string("\"") + key + "\"";
    const auto keyPos = json.find(marker); if (keyPos == std::string::npos) return result;
    auto p = json.find('[', keyPos + marker.size()); if (p == std::string::npos) return result;
    ++p;
    while (p < json.size()) {
        const auto start = json.find('{', p); const auto endArray = json.find(']', p);
        if (start == std::string::npos || (endArray != std::string::npos && endArray < start)) break;
        int depth = 0; bool quoted = false;
        for (auto i = start; i < json.size(); ++i) {
            if (json[i] == '"' && (i == 0 || json[i - 1] != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (json[i] == '{') ++depth;
            if (json[i] == '}' && --depth == 0) { result.push_back(json.substr(start, i - start + 1)); p = i + 1; break; }
        }
    }
    return result;
}
std::string StateName(AuthState state) {
    return state == AuthState::Ready ? "ready" : state == AuthState::ReauthenticationRequired ? "reauthentication_required" : "error";
}
AuthState ParseState(const std::string& state) {
    if (state == "reauthentication_required") return AuthState::ReauthenticationRequired;
    if (state == "error") return AuthState::Error;
    return AuthState::Ready;
}
}

AccountStore::AccountStore(std::filesystem::path root)
    : root_(std::move(root)), metadataPath_(root_ / "accounts.json"), credentials_(root_ / "credentials") {}

std::int64_t AccountStore::Now() { return static_cast<std::int64_t>(std::time(nullptr)); }
std::string AccountStore::NewId() {
    GUID guid{};
    if (CoCreateGuid(&guid) != S_OK) return {};
    char text[64]{};
    std::snprintf(text, sizeof text, "%08lX-%04hX-%04hX-%02hhX%02hhX-%02hhX%02hhX%02hhX%02hhX%02hhX%02hhX",
                  guid.Data1, guid.Data2, guid.Data3, guid.Data4[0], guid.Data4[1], guid.Data4[2],
                  guid.Data4[3], guid.Data4[4], guid.Data4[5], guid.Data4[6], guid.Data4[7]);
    return text;
}

bool AccountStore::Load(std::string& error) {
    data_ = {};
    std::ifstream input(metadataPath_, std::ios::binary);
    if (!input) return true;
    const std::string json((std::istreambuf_iterator<char>(input)), {});
    if (json.find("{\"version\":") == std::string::npos || json.find("\"jagexIdentities\"") == std::string::npos) {
        error = "accounts.json is invalid; it was preserved";
        return false;
    }
    for (const auto& object : ArrayObjects(json, "jagexIdentities")) {
        JagexIdentity identity;
        identity.id = StringField(object, "id");
        identity.credentialReference = StringField(object, "credentialReference");
        identity.createdAt = IntField(object, "createdAt");
        identity.lastAuthenticatedAt = IntField(object, "lastAuthenticatedAt");
        identity.authState = ParseState(StringField(object, "authState"));
        if (identity.id.empty()) { error = "accounts.json contains an invalid Jagex identity"; return false; }
        data_.jagexIdentities.push_back(std::move(identity));
    }
    for (const auto& object : ArrayObjects(json, "jagexCharacters")) {
        JagexCharacter character;
        character.id = StringField(object, "id"); character.identityId = StringField(object, "identityId");
        character.accountId = StringField(object, "accountId"); character.displayName = StringField(object, "displayName");
        character.label = StringField(object, "label"); character.createdAt = IntField(object, "createdAt");
        character.lastUsed = IntField(object, "lastUsed"); character.available = BoolField(object, "available");
        if (character.id.empty() || character.identityId.empty() || character.accountId.empty()) { error = "accounts.json contains an invalid Jagex character"; return false; }
        data_.jagexCharacters.push_back(std::move(character));
    }
    for (const auto& object : ArrayObjects(json, "legacyAccounts")) {
        LegacyAccount account;
        account.id = StringField(object, "id"); account.username = StringField(object, "username"); account.label = StringField(object, "label");
        account.createdAt = IntField(object, "createdAt"); account.lastUsed = IntField(object, "lastUsed");
        if (account.id.empty()) { error = "accounts.json contains an invalid legacy account"; return false; }
        data_.legacyAccounts.push_back(std::move(account));
    }
    return true;
}

bool AccountStore::Save(std::string& error) const {
    std::error_code ec; std::filesystem::create_directories(root_, ec);
    if (ec) { error = "cannot create account data directory"; return false; }
    const auto temporary = metadataPath_.string() + ".tmp";
    std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
    if (!out) { error = "cannot write accounts.json"; return false; }
    out << "{\"version\":1,\"jagexIdentities\":[";
    for (size_t i = 0; i < data_.jagexIdentities.size(); ++i) { const auto& x = data_.jagexIdentities[i]; if (i) out << ','; out << "{\"id\":" << Quote(x.id) << ",\"credentialReference\":" << Quote(x.credentialReference) << ",\"createdAt\":" << x.createdAt << ",\"lastAuthenticatedAt\":" << x.lastAuthenticatedAt << ",\"authState\":" << Quote(StateName(x.authState)) << "}"; }
    out << "],\"jagexCharacters\":[";
    for (size_t i = 0; i < data_.jagexCharacters.size(); ++i) { const auto& x = data_.jagexCharacters[i]; if (i) out << ','; out << "{\"id\":" << Quote(x.id) << ",\"identityId\":" << Quote(x.identityId) << ",\"accountId\":" << Quote(x.accountId) << ",\"displayName\":" << Quote(x.displayName) << ",\"label\":" << Quote(x.label) << ",\"createdAt\":" << x.createdAt << ",\"lastUsed\":" << x.lastUsed << ",\"available\":" << (x.available ? "true" : "false") << "}"; }
    out << "],\"legacyAccounts\":[";
    for (size_t i = 0; i < data_.legacyAccounts.size(); ++i) { const auto& x = data_.legacyAccounts[i]; if (i) out << ','; out << "{\"id\":" << Quote(x.id) << ",\"username\":" << Quote(x.username) << ",\"label\":" << Quote(x.label) << ",\"createdAt\":" << x.createdAt << ",\"lastUsed\":" << x.lastUsed << "}"; }
    out << "]}"; out.flush();
    if (!out.good()) { error = "cannot flush accounts.json"; return false; }
    out.close();
    if (!MoveFileExW(std::filesystem::path(temporary).c_str(), metadataPath_.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) { DeleteFileW(std::filesystem::path(temporary).c_str()); error = "cannot replace accounts.json"; return false; }
    return true;
}

JagexIdentity* AccountStore::FindIdentity(const std::string& id) { for (auto& x : data_.jagexIdentities) if (x.id == id) return &x; return nullptr; }
JagexCharacter* AccountStore::FindCharacter(const std::string& id) { for (auto& x : data_.jagexCharacters) if (x.id == id) return &x; return nullptr; }
JagexCharacter* AccountStore::FindCharacterByAccountId(const std::string& id) { for (auto& x : data_.jagexCharacters) if (x.accountId == id) return &x; return nullptr; }
LegacyAccount* AccountStore::FindLegacy(const std::string& id) { for (auto& x : data_.legacyAccounts) if (x.id == id) return &x; return nullptr; }
void AccountStore::UpsertIdentity(JagexIdentity value) { if (auto* old = FindIdentity(value.id)) *old = std::move(value); else data_.jagexIdentities.push_back(std::move(value)); }
void AccountStore::UpsertCharacter(JagexCharacter value) { if (auto* old = FindCharacterByAccountId(value.accountId)) { value.id = old->id; if (value.label.empty()) value.label = old->label; value.createdAt = old->createdAt; *old = std::move(value); } else data_.jagexCharacters.push_back(std::move(value)); }
void AccountStore::UpsertLegacy(LegacyAccount value) { if (auto* old = FindLegacy(value.id)) *old = std::move(value); else data_.legacyAccounts.push_back(std::move(value)); }
bool AccountStore::RemoveCharacter(const std::string& id, std::string&) { for (auto it = data_.jagexCharacters.begin(); it != data_.jagexCharacters.end(); ++it) if (it->id == id) { data_.jagexCharacters.erase(it); return true; } return false; }
bool AccountStore::RemoveIdentity(const std::string& id, std::string& error) { for (auto it = data_.jagexIdentities.begin(); it != data_.jagexIdentities.end(); ++it) if (it->id == id) { data_.jagexIdentities.erase(it); return true; } error = "identity not found"; return false; }
