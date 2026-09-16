#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

class DpapiCredentials {
public:
    explicit DpapiCredentials(std::filesystem::path directory);

    bool StoreSecret(const std::string& reference, std::string_view secret, std::string& error) const;
    std::optional<std::string> LoadSecret(const std::string& reference, std::string& error) const;
    bool DeleteSecret(const std::string& reference, std::string& error) const;

private:
    std::filesystem::path directory_;
};
