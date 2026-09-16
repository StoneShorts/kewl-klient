#pragma once

#include "account_model.hpp"
#include <string>
#include <windows.h>

class OsClientLauncher {
public:
    static bool BuildJagexEnvironment(const std::string& sessionId, const JagexCharacter& character, std::wstring& block, std::string& error);
    static bool BuildLegacyEnvironment(std::wstring& block, std::string& error);
    static bool Create(const std::wstring& executable, const std::wstring& workingDirectory, const std::wstring& environmentBlock,
                       PROCESS_INFORMATION& process, std::string& error);
};
