#pragma once

#include <cstdint>
#include <string>
#include <vector>

enum class AccountType { Jagex, Legacy };
enum class AuthState { Ready, ReauthenticationRequired, Error };

struct JagexIdentity {
    std::string id;
    std::string credentialReference;
    std::int64_t createdAt = 0;
    std::int64_t lastAuthenticatedAt = 0;
    AuthState authState = AuthState::Ready;
};

struct JagexCharacter {
    std::string id;
    std::string identityId;
    std::string accountId;
    std::string displayName;
    std::string label;
    std::int64_t createdAt = 0;
    std::int64_t lastUsed = 0;
    bool available = true;
};

struct LegacyAccount {
    std::string id;
    std::string username;
    std::string label;
    std::int64_t createdAt = 0;
    std::int64_t lastUsed = 0;
};

struct AccountStoreData {
    std::vector<JagexIdentity> jagexIdentities;
    std::vector<JagexCharacter> jagexCharacters;
    std::vector<LegacyAccount> legacyAccounts;
};
