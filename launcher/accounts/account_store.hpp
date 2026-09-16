#pragma once

#include "account_model.hpp"
#include "dpapi_credentials.hpp"
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

class AccountStore {
public:
    explicit AccountStore(std::filesystem::path root);

    bool Load(std::string& error);
    bool Save(std::string& error) const;

    const AccountStoreData& data() const { return data_; }
    AccountStoreData& data() { return data_; }

    JagexIdentity* FindIdentity(const std::string& id);
    JagexCharacter* FindCharacter(const std::string& id);
    JagexCharacter* FindCharacterByAccountId(const std::string& accountId);
    LegacyAccount* FindLegacy(const std::string& id);

    void UpsertIdentity(JagexIdentity identity);
    void UpsertCharacter(JagexCharacter character);
    void UpsertLegacy(LegacyAccount account);
    bool RemoveIdentity(const std::string& id, std::string& error);
    bool RemoveCharacter(const std::string& id, std::string& error);

    DpapiCredentials& credentials() { return credentials_; }
    const DpapiCredentials& credentials() const { return credentials_; }
    std::filesystem::path metadataPath() const { return metadataPath_; }

    static std::string NewId();
    static std::int64_t Now();

private:
    std::filesystem::path root_;
    std::filesystem::path metadataPath_;
    AccountStoreData data_;
    DpapiCredentials credentials_;
};
