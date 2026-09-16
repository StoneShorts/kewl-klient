#include "http_client.hpp"
#include <winhttp.h>
#include <algorithm>

namespace {
struct Handle { HINTERNET h = nullptr; ~Handle(){ if(h) WinHttpCloseHandle(h); } operator HINTERNET() const { return h; } };
struct Parsed { std::wstring host, path; INTERNET_PORT port = INTERNET_DEFAULT_HTTPS_PORT; };
Parsed parse(const std::wstring& url) {
    URL_COMPONENTS c{}; c.dwStructSize=sizeof c; wchar_t host[256]{}, path[2048]{}; c.lpszHostName=host;c.dwHostNameLength=256;c.lpszUrlPath=path;c.dwUrlPathLength=2048;
    Parsed p; if(!WinHttpCrackUrl(url.c_str(),0,0,&c)) return p; p.host.assign(host,c.dwHostNameLength);p.path.assign(path,c.dwUrlPathLength);p.port=c.nPort; return p;
}
HttpResponse request(const wchar_t* method,const std::wstring& url,const std::string& headers,std::string_view body,std::string& error) {
    HttpResponse result; Parsed p=parse(url); if(p.host.empty()||p.port!=INTERNET_DEFAULT_HTTPS_PORT){error="Jagex requests require HTTPS";return result;}
    Handle session{WinHttpOpen(L"KewlKlient/1.0",WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,WINHTTP_NO_PROXY_NAME,WINHTTP_NO_PROXY_BYPASS,0)}; if(!session.h){error="WinHTTP session initialization failed";return result;}
    WinHttpSetTimeouts(session,10000,10000,15000,15000); Handle connection{WinHttpConnect(session,p.host.c_str(),p.port,0)}; if(!connection.h){error="WinHTTP connection failed";return result;}
    Handle req{WinHttpOpenRequest(connection,method,p.path.c_str(),nullptr,WINHTTP_NO_REFERER,WINHTTP_DEFAULT_ACCEPT_TYPES,WINHTTP_FLAG_SECURE)}; if(!req.h){error="WinHTTP request initialization failed";return result;}
    LPVOID data=body.empty()?nullptr:const_cast<char*>(body.data()); DWORD len=static_cast<DWORD>(body.size()); std::wstring wh(headers.begin(),headers.end());
    if(!WinHttpSendRequest(req,wh.empty()?WINHTTP_NO_ADDITIONAL_HEADERS:wh.c_str(),wh.empty()?0:-1,data,len,len,0)){error="WinHTTP request send failed";return result;}
    if(!WinHttpReceiveResponse(req,nullptr)){error="WinHTTP response failed";return result;}
    DWORD status=0,size=sizeof status; WinHttpQueryHeaders(req,WINHTTP_QUERY_STATUS_CODE|WINHTTP_QUERY_FLAG_NUMBER,WINHTTP_HEADER_NAME_BY_INDEX,&status,&size,WINHTTP_NO_HEADER_INDEX); result.statusCode=status;
    constexpr size_t limit=1024*1024; while(true){DWORD avail=0;if(!WinHttpQueryDataAvailable(req,&avail)) {error="WinHTTP response read failed";return result;}if(!avail)break;if(result.body.size()+avail>limit){error="Jagex response exceeded the size limit";return result;}size_t old=result.body.size();result.body.resize(old+avail);DWORD got=0;if(!WinHttpReadData(req,result.body.data()+old,avail,&got)){error="WinHTTP response read failed";return result;}result.body.resize(old+got);if(!got)break;}
    return result;
}
}
HttpResponse HttpClient::Get(const std::wstring& url,const std::string& headers,std::string& error) const{return request(L"GET",url,headers,{},error);}
HttpResponse HttpClient::Post(const std::wstring& url,const std::string& headers,std::string_view body,std::string& error) const{return request(L"POST",url,headers,body,error);}
