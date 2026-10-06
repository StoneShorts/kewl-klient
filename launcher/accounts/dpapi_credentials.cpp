#include "dpapi_credentials.hpp"

#include <windows.h>
#include <wincrypt.h>

#include <fstream>
#include <vector>

DpapiCredentials::DpapiCredentials(std::filesystem::path directory) : directory_(std::move(directory)) {}

bool DpapiCredentials::StoreSecret(const std::string& reference, std::string_view secret, std::string& error) const {
    if (reference.empty() || secret.empty()) { error = "cannot store an empty credential"; return false; }
    std::error_code ec;
    std::filesystem::create_directories(directory_, ec);
    if (ec) { error = "cannot create credential directory"; return false; }

    DATA_BLOB input{static_cast<DWORD>(secret.size()), reinterpret_cast<BYTE*>(const_cast<char*>(secret.data()))};
    DATA_BLOB protectedBlob{};
    if (!CryptProtectData(&input, L"0xClient credential", nullptr, nullptr, nullptr,
                          CRYPTPROTECT_UI_FORBIDDEN, &protectedBlob)) {
        error = "DPAPI encryption failed";
        return false;
    }

    const auto path = directory_ / (reference + ".bin");
    const auto temporary = directory_ / (reference + ".tmp");
    bool ok = false;
    {
        std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
        if (out) {
            out.write(reinterpret_cast<const char*>(protectedBlob.pbData), protectedBlob.cbData);
            out.flush();
            ok = out.good();
        }
    }
    SecureZeroMemory(protectedBlob.pbData, protectedBlob.cbData);
    LocalFree(protectedBlob.pbData);
    if (!ok || !MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(temporary.c_str());
        error = "cannot commit protected credential";
        return false;
    }
    return true;
}

std::optional<std::string> DpapiCredentials::LoadSecret(const std::string& reference, std::string& error) const {
    if (reference.empty()) { error = "empty credential reference"; return std::nullopt; }
    std::ifstream in(directory_ / (reference + ".bin"), std::ios::binary);
    if (!in) { error = "protected credential is missing"; return std::nullopt; }
    std::vector<BYTE> ciphertext((std::istreambuf_iterator<char>(in)), {});
    if (ciphertext.empty()) { error = "protected credential is empty"; return std::nullopt; }

    DATA_BLOB input{static_cast<DWORD>(ciphertext.size()), ciphertext.data()};
    DATA_BLOB plaintext{};
    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
                            CRYPTPROTECT_UI_FORBIDDEN, &plaintext)) {
        error = "DPAPI decrypt failed";
        SecureZeroMemory(ciphertext.data(), ciphertext.size());
        return std::nullopt;
    }
    std::string result(reinterpret_cast<char*>(plaintext.pbData), plaintext.cbData);
    SecureZeroMemory(plaintext.pbData, plaintext.cbData);
    LocalFree(plaintext.pbData);
    SecureZeroMemory(ciphertext.data(), ciphertext.size());
    return result;
}

bool DpapiCredentials::DeleteSecret(const std::string& reference, std::string& error) const {
    if (reference.empty()) return true;
    std::error_code ec;
    std::filesystem::remove(directory_ / (reference + ".bin"), ec);
    if (ec) { error = "cannot remove protected credential"; return false; }
    return true;
}
