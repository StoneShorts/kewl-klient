#include "jagex_auth.hpp"

#include <bcrypt.h>
#include <cctype>
#include <sstream>

namespace {
std::string randomBytes(size_t count) {
    std::string bytes(count, '\0');
    if (BCryptGenRandom(nullptr, reinterpret_cast<PUCHAR>(bytes.data()), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) return {};
    return bytes;
}
std::string jsonQuote(std::string_view value) {
    std::string out = "\"";
    for (char c : value) { if (c == '\\' || c == '"') out += '\\'; out += c; }
    return out + '"';
}
std::string jsonString(const std::string& body, const char* key) {
    const std::string marker = std::string("\"") + key + "\"";
    auto p = body.find(marker); if (p == std::string::npos) return {};
    p = body.find(':', p + marker.size()); if (p == std::string::npos) return {};
    p = body.find('"', p); if (p == std::string::npos) return {};
    const auto end = body.find('"', p + 1); return end == std::string::npos ? std::string() : body.substr(p + 1, end - p - 1);
}
std::string sha256(std::string_view input) {
    BCRYPT_ALG_HANDLE alg = nullptr; BCRYPT_HASH_HANDLE hash = nullptr; DWORD cb = 0, objectSize = 0;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return {};
    BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize), sizeof objectSize, &cb, 0);
    std::string object(objectSize, '\0'), digest(32, '\0');
    if (BCryptCreateHash(alg, &hash, reinterpret_cast<PUCHAR>(object.data()), objectSize, nullptr, 0, 0) != 0) { BCryptCloseAlgorithmProvider(alg, 0); return {}; }
    BCryptHashData(hash, reinterpret_cast<PUCHAR>(const_cast<char*>(input.data())), static_cast<ULONG>(input.size()), 0);
    BCryptFinishHash(hash, reinterpret_cast<PUCHAR>(digest.data()), static_cast<ULONG>(digest.size()), 0);
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(alg, 0); return digest;
}
}

namespace jagex_auth {
std::string Base64Url(std::string_view bytes) {
    static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out; unsigned value = 0; int bits = 0;
    for (unsigned char c : bytes) { value = (value << 8) | c; bits += 8; while (bits >= 6) { bits -= 6; out += alphabet[(value >> bits) & 63]; } }
    if (bits) out += alphabet[(value << (6 - bits)) & 63]; return out;
}
std::string UrlEncode(std::string_view value) {
    static constexpr char hex[] = "0123456789ABCDEF"; std::string out;
    for (unsigned char c : value) { if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') out += char(c); else { out += '%'; out += hex[c >> 4]; out += hex[c & 15]; } }
    return out;
}
LauncherOAuthRequest BeginLauncherOAuth() {
    LauncherOAuthRequest r{Base64Url(randomBytes(32)), Base64Url(randomBytes(64)), {}};
    if (r.state.empty() || r.verifier.empty()) return r;
    std::ostringstream q;
    q << "client_id=" << UrlEncode(LAUNCHER_CLIENT_ID) << "&redirect_uri=" << UrlEncode(LAUNCHER_REDIRECT)
      << "&response_type=code&prompt=login&scope=" << UrlEncode(LAUNCHER_SCOPE)
      << "&state=" << UrlEncode(r.state) << "&code_challenge=" << UrlEncode(Base64Url(sha256(r.verifier)))
      << "&code_challenge_method=S256&auth_method=&login_type=&flow=launcher";
    r.url = "https://account.jagex.com/oauth2/auth?" + q.str(); return r;
}
ConsentOAuthRequest BeginConsent(std::string_view firstStageIdToken) {
    ConsentOAuthRequest r{Base64Url(randomBytes(32)), Base64Url(randomBytes(32)), {}};
    if (r.state.empty() || r.nonce.empty() || firstStageIdToken.empty()) return r;
    std::ostringstream q;
    q << "client_id=" << UrlEncode(GAME_CLIENT_ID) << "&redirect_uri=" << UrlEncode(GAME_REDIRECT)
      << "&response_type=id_token%20code&prompt=consent&scope=" << UrlEncode(GAME_SCOPE)
      << "&state=" << UrlEncode(r.state) << "&nonce=" << UrlEncode(r.nonce)
      << "&id_token_hint=" << UrlEncode(firstStageIdToken);
    r.url = "https://account.jagex.com/oauth2/auth?" + q.str(); return r;
}
Service::Service(HttpClient http) : http_(std::move(http)) {}
bool Service::ExchangeCode(std::string_view code, std::string_view verifier, std::string& idToken, std::string& error) const {
    const std::string body = "grant_type=authorization_code&client_id=" + UrlEncode(LAUNCHER_CLIENT_ID) + "&code=" + UrlEncode(code) + "&code_verifier=" + UrlEncode(verifier) + "&redirect_uri=" + UrlEncode(LAUNCHER_REDIRECT);
    auto response = http_.Post(TOKEN_URL, "Content-Type: application/x-www-form-urlencoded\r\nAccept: application/json\r\n", body, error);
    if (response.statusCode < 200 || response.statusCode >= 300) { error = "Jagex authorization code exchange returned HTTP " + std::to_string(response.statusCode); return false; }
    idToken = jsonString(response.body, "id_token"); if (idToken.empty()) { error = "Jagex token response did not contain id_token"; return false; } return true;
}
bool Service::CreateGameSession(std::string_view secondStageIdToken, std::string& sessionId, std::string& error) const {
    const std::string body = "{\"idToken\":" + jsonQuote(secondStageIdToken) + "}";
    auto response = http_.Post(SESSION_URL, "Content-Type: application/json\r\nAccept: application/json\r\n", body, error);
    if (response.statusCode < 200 || response.statusCode >= 300) { error = "Jagex game session request returned HTTP " + std::to_string(response.statusCode); return false; }
    sessionId = jsonString(response.body, "sessionId"); if (sessionId.empty()) { error = "Jagex game session response did not contain sessionId"; return false; } return true;
}
bool Service::FetchCharacters(std::string_view sessionId, std::vector<Character>& characters, std::string& error) const {
    const std::string headers = "Accept: application/json\r\nAuthorization: Bearer " + std::string(sessionId) + "\r\n";
    auto response = http_.Get(ACCOUNTS_URL, headers, error);
    if (response.statusCode < 200 || response.statusCode >= 300) { error = "Jagex character request returned HTTP " + std::to_string(response.statusCode); return false; }
    size_t cursor = 0;
    while ((cursor = response.body.find("\"accountId\"", cursor)) != std::string::npos) {
        auto colon = response.body.find(':', cursor); auto begin = response.body.find('"', colon); auto end = response.body.find('"', begin + 1);
        if (colon == std::string::npos || begin == std::string::npos || end == std::string::npos) break;
        Character c; c.accountId = response.body.substr(begin + 1, end - begin - 1);
        auto nameKey = response.body.find("\"displayName\"", end); if (nameKey != std::string::npos) { auto nc = response.body.find(':', nameKey); auto nb = response.body.find('"', nc); auto ne = response.body.find('"', nb + 1); if (nc != std::string::npos && nb != std::string::npos && ne != std::string::npos) c.displayName = response.body.substr(nb + 1, ne - nb - 1); }
        if (!c.accountId.empty()) characters.push_back(std::move(c)); cursor = end + 1;
    }
    if (characters.empty()) { error = "Jagex account list contained no characters"; return false; } return true;
}
}
