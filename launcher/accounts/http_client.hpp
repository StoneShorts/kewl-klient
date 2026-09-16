#pragma once

#include <windows.h>
#include <string>
#include <string_view>

struct HttpResponse {
    DWORD statusCode = 0;
    std::string body;
};

class HttpClient {
public:
    HttpResponse Get(const std::wstring& url, const std::string& headers, std::string& error) const;
    HttpResponse Post(const std::wstring& url, const std::string& headers, std::string_view body, std::string& error) const;
};
