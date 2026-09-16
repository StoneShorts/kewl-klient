#pragma once

#include <functional>
#include <memory>
#include <string>

class JagexAuthWindow {
public:
    using RedirectCallback = std::function<bool(const std::wstring&)>;
    using ClosedCallback = std::function<void()>;

    JagexAuthWindow();
    ~JagexAuthWindow();

    JagexAuthWindow(const JagexAuthWindow&) = delete;
    JagexAuthWindow& operator=(const JagexAuthWindow&) = delete;

    bool Open(const std::wstring& userDataDirectory, RedirectCallback callback,
              ClosedCallback closed, std::string& error);
    bool Navigate(const std::string& url, std::string& error);
    void Close();
    bool IsOpen() const { return hwnd_ != nullptr; }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    void* hwnd_ = nullptr;
};
