#include "jagex_auth_window.hpp"

#include <windows.h>
#include <wrl.h>
#include <WebView2.h>
#include <filesystem>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

struct JagexAuthWindow::Impl {
    HWND hwnd = nullptr;
    ComPtr<ICoreWebView2Environment> environment;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webview;
    std::wstring profile;
    std::string pendingUrl;
    RedirectCallback redirect;
    ClosedCallback closed;
    JagexAuthWindow* owner = nullptr;

    static LRESULT CALLBACK WindowProc(HWND h, UINT message, WPARAM w, LPARAM l) {
        auto* self = reinterpret_cast<Impl*>(GetWindowLongPtrW(h, GWLP_USERDATA));
        if (message == WM_NCCREATE) {
            self = reinterpret_cast<Impl*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);
            SetWindowLongPtrW(h, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self)); self->hwnd = h;
        }
        if (!self) return DefWindowProcW(h, message, w, l);
        if (message == WM_SIZE && self->controller) { RECT r{}; GetClientRect(h, &r); self->controller->put_Bounds(r); return 0; }
        if (message == WM_CLOSE) { if (self->closed) self->closed(); DestroyWindow(h); return 0; }
        if (message == WM_DESTROY) { self->hwnd = nullptr; return 0; }
        return DefWindowProcW(h, message, w, l);
    }

    bool createWindow(std::string& error) {
        static const wchar_t klass[] = L"KewlKlientJagexAuth";
        static bool registered = false;
        if (!registered) {
            WNDCLASSW wc{}; wc.lpfnWndProc = WindowProc; wc.hInstance = GetModuleHandleW(nullptr); wc.lpszClassName = klass; wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(IDC_ARROW));
            if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) { error = "cannot register Jagex authentication window"; return false; }
            registered = true;
        }
        hwnd = CreateWindowExW(0, klass, L"Sign in with Jagex", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                               CW_USEDEFAULT, CW_USEDEFAULT, 980, 760, nullptr, nullptr, GetModuleHandleW(nullptr), this);
        if (!hwnd) { error = "cannot create Jagex authentication window"; return false; }
        return true;
    }
    void initializeWebView() {
        auto envHandler = Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(result) || !env) return result;
                environment = env;
                environment->CreateCoreWebView2Controller(hwnd,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [this](HRESULT hr, ICoreWebView2Controller* controller) -> HRESULT {
                            if (FAILED(hr) || !controller) return hr;
                            this->controller = controller;
                            controller->get_CoreWebView2(&webview);
                            RECT r{}; GetClientRect(hwnd, &r); controller->put_Bounds(r);
                            webview->add_NavigationStarting(
                                Callback<ICoreWebView2NavigationStartingEventHandler>(
                                    [this](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                                        LPWSTR uri = nullptr;
                                        if (SUCCEEDED(args->get_Uri(&uri)) && uri) {
                                            std::wstring value(uri); CoTaskMemFree(uri);
                                            if (redirect && redirect(value)) args->put_Cancel(TRUE);
                                        }
                                        return S_OK;
                                    }).Get(), nullptr);
                            if (!pendingUrl.empty()) { webview->Navigate(std::wstring(pendingUrl.begin(), pendingUrl.end()).c_str()); pendingUrl.clear(); }
                            return S_OK;
                        }).Get());
                return S_OK;
            });
        HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(nullptr, profile.c_str(), nullptr, envHandler.Get());
        (void)hr;
    }
};

JagexAuthWindow::JagexAuthWindow() : impl_(std::make_unique<Impl>()) { impl_->owner = this; }
JagexAuthWindow::~JagexAuthWindow() { Close(); }
bool JagexAuthWindow::Open(const std::wstring& profile, RedirectCallback callback, ClosedCallback closed, std::string& error) {
    if (IsOpen()) { error = "Jagex authentication is already open"; return false; }
    impl_->profile = profile; impl_->redirect = std::move(callback); impl_->closed = std::move(closed);
    if (!impl_->createWindow(error)) return false;
    hwnd_ = impl_->hwnd; impl_->initializeWebView(); return true;
}
bool JagexAuthWindow::Navigate(const std::string& url, std::string& error) {
    if (!IsOpen()) { error = "Jagex authentication window is not open"; return false; }
    impl_->pendingUrl = url;
    if (impl_->webview) { std::wstring wide(url.begin(), url.end()); HRESULT hr = impl_->webview->Navigate(wide.c_str()); if (FAILED(hr)) { error = "WebView2 navigation failed"; return false; } impl_->pendingUrl.clear(); }
    return true;
}
void JagexAuthWindow::Close() {
    if (!impl_) return;
    if (impl_->controller) impl_->controller->Close();
    if (impl_->hwnd) DestroyWindow(impl_->hwnd);
    impl_->webview.Reset(); impl_->controller.Reset(); impl_->environment.Reset();
    std::error_code ec; if (!impl_->profile.empty()) std::filesystem::remove_all(impl_->profile, ec);
    impl_->profile.clear(); hwnd_ = nullptr;
}
