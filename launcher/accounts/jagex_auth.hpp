#pragma once

#include "account_model.hpp"
#include "http_client.hpp"
#include <string>
#include <string_view>
#include <vector>

namespace jagex_auth {
inline constexpr wchar_t AUTH_URL[] = L"https://account.jagex.com/oauth2/auth";
inline constexpr wchar_t TOKEN_URL[] = L"https://account.jagex.com/oauth2/token";
inline constexpr wchar_t SESSION_URL[] = L"https://auth.jagex.com/game-session/v1/sessions";
inline constexpr wchar_t ACCOUNTS_URL[] = L"https://auth.jagex.com/game-session/v1/accounts";
inline constexpr char LAUNCHER_CLIENT_ID[] = "com_jagex_auth_desktop_launcher";
inline constexpr char GAME_CLIENT_ID[] = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
inline constexpr char LAUNCHER_REDIRECT[] = "https://secure.runescape.com/m=weblogin/launcher-redirect";
inline constexpr char GAME_REDIRECT[] = "http://localhost";
inline constexpr char LAUNCHER_SCOPE[] = "openid offline gamesso.token.create user.profile.read";
inline constexpr char GAME_SCOPE[] = "openid offline";

struct LauncherOAuthRequest { std::string state; std::string verifier; std::string url; };
struct ConsentOAuthRequest { std::string state; std::string nonce; std::string url; };
struct Character { std::string accountId; std::string displayName; };

std::string Base64Url(std::string_view bytes);
std::string UrlEncode(std::string_view value);
LauncherOAuthRequest BeginLauncherOAuth();
ConsentOAuthRequest BeginConsent(std::string_view firstStageIdToken);

class Service {
public:
    explicit Service(HttpClient http = {});
    bool ExchangeCode(std::string_view code, std::string_view verifier, std::string& idToken, std::string& error) const;
    bool CreateGameSession(std::string_view secondStageIdToken, std::string& sessionId, std::string& error) const;
    bool FetchCharacters(std::string_view sessionId, std::vector<Character>& characters, std::string& error) const;
private:
    HttpClient http_;
};
}
