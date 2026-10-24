// Negative test: loading the proxy in another x86 process must not install game hooks.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "hud_atlas.h"
int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 1;
    // Known CD model families and variants must not alias unrelated artwork.
    const int icons[][3] = {{0,791,0},{61,824,198},{30,791,99},{90,791,297},
        {102,857,330},{110,923,0},{111,956,0},{112,923,33},{115,923,132}};
    for (const auto& icon : icons) {
        int x = -1, y = -1;
        if (!logh::hudIconRect(icon[0], x, y) || x != icon[1] || y != icon[2]) return 5;
    }
    const unsigned unsupported[] = {2u,32u,103u,116u,119u,129u,130u};
    for (unsigned model : unsupported) {
        int x = -1, y = -1;
        if (logh::hudIconRect(model, x, y) || x != -1 || y != -1) return 6;
    }
    HMODULE module = LoadLibraryW(argv[1]);
    if (!module) return 2;
    auto status = reinterpret_cast<LONG(WINAPI*)()>(GetProcAddress(module, "LoghPlanetLayoutStatus"));
    auto create = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, const GUID&, void**, void*)>(GetProcAddress(module, "DirectInput8Create"));
    if (!status || !create || status() != 0) return 3;
    GUID iid = {}; void* input = nullptr;
    HRESULT result = create(GetModuleHandleW(nullptr), 0, iid, &input, nullptr);
    if (status() != -1 || SUCCEEDED(result) || input) return 4;
    FreeLibrary(module);
    return 0;
}
