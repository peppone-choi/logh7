// LOGH7 CD client compatibility module. Author: 최병호
// The original executable stays unchanged. DINPUT8 forwards to the Windows DLL.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>
#include <math.h>
#include <float.h>
#include <string.h>

#pragma comment(lib, "advapi32.lib")
#pragma comment(linker, "/EXPORT:DirectInput8Create=_ForwardDirectInput8Create@20")
#pragma comment(linker, "/EXPORT:LoghPlanetLayoutStatus=_LoghPlanetLayoutStatus@0")

static volatile LONG status = 0;
static int lastGrid = -1;
static ULONGLONG autoViewAt = 0;
static void* preparedResources[8] = {};
static bool patchBytes(void* target, const void* expected, const void* replacement, SIZE_T size) {
    if (memcmp(target, expected, size)) return false;
    DWORD previous = 0;
    if (!VirtualProtect(target, size, PAGE_EXECUTE_READWRITE, &previous)) return false;
    memcpy(target, replacement, size);
    FlushInstructionCache(GetCurrentProcess(), target, size);
    DWORD ignored = 0; VirtualProtect(target, size, previous, &ignored);
    return true;
}
static bool verifiedClient() {
    if (GetModuleHandleW(nullptr) != reinterpret_cast<HMODULE>(0x400000)) return false;
    wchar_t path[MAX_PATH];
    DWORD length = GetModuleFileNameW(nullptr, path, MAX_PATH);
    if (!length || length == MAX_PATH) return false;
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (file == INVALID_HANDLE_VALUE) return false;
    HCRYPTPROV provider = 0;
    HCRYPTHASH hash = 0;
    bool ok = CryptAcquireContextW(&provider, nullptr, nullptr, PROV_RSA_AES, CRYPT_VERIFYCONTEXT) != FALSE;
    if (ok) ok = CryptCreateHash(provider, CALG_SHA_256, 0, 0, &hash) != FALSE;
    BYTE buffer[16384]; DWORD read = 0;
    while (ok) {
        if (!ReadFile(file, buffer, sizeof(buffer), &read, nullptr)) { ok = false; break; }
        if (!read) break;
        ok = CryptHashData(hash, buffer, read, 0) != FALSE;
    }
    BYTE digest[32]; DWORD size = sizeof(digest);
    const BYTE expected[] = {0xbd,0x19,0x26,0x3c,0x10,0xde,0xcc,0x3d,0x58,0x37,0x31,0x65,0xa8,0x2d,0x42,0xa9,
        0x26,0x78,0x68,0x40,0x0d,0x40,0x7d,0xa8,0x7d,0x5f,0x4f,0x41,0x09,0xab,0x6e,0x16};
    if (ok) ok = CryptGetHashParam(hash, HP_HASHVAL, digest, &size, 0) && size == 32 && !memcmp(digest, expected, 32);
    if (hash) CryptDestroyHash(hash);
    if (provider) CryptReleaseContext(provider, 0);
    CloseHandle(file);
    return ok;
}

template<class T> static T& at(void* base, unsigned offset) {
    return *reinterpret_cast<T*>(static_cast<BYTE*>(base) + offset);
}
static void hideModels() {
    auto models = reinterpret_cast<void**>(0x9d2f74);
    for (int i = 0; i < 8; ++i) if (models[i]) at<int>(models[i], 0xb0) = 1;
}

// Replacement for 004d68d0: the CD function always draws four dummy planets.
// Read the consumed 031d catalogue; never create, move or edit world entities.
static void __fastcall drawLayout(void* map, void*, char) {
    hideModels();
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (!world || !at<BYTE>(world, 0x2b6a6c)) return;
    int ownGrid = at<int>(world, 0x2b6a70);
    if (ownGrid < 0 || ownGrid >= 5000) return;
    auto kind = reinterpret_cast<int(__cdecl*)(int, int)>(0x4d35b0);
    int mode = at<int>(map, 4);
    if (ownGrid != lastGrid) {
        lastGrid = ownGrid;
        autoViewAt = GetTickCount64() + 750;
    }
    if (autoViewAt && GetTickCount64() >= autoViewAt && (mode == 1 || mode == 4)) {
        autoViewAt = 0;
        at<int>(map, 0x18) = ownGrid % 100;
        at<int>(map, 0x1c) = ownGrid / 100;
        auto setMode = reinterpret_cast<void(__thiscall*)(void*, int)>(0x4d5030);
        setMode(map, kind(ownGrid % 100, ownGrid / 100) == 3 ? 4 : 1);
        at<int>(map, 0x18) = ownGrid % 100;
        at<int>(map, 0x1c) = ownGrid / 100;
        float center[3] = {};
        reinterpret_cast<void(__cdecl*)(float*, int, int)>(0x4d3540)(center, ownGrid % 100, ownGrid / 100);
        memcpy(reinterpret_cast<void*>(0xc5153c), center, sizeof(center));
        reinterpret_cast<void(__cdecl*)()>(0x4f6ee0)();
        mode = at<int>(map, 4);
    }
    // Destination selection must keep the client's normal input and range drawing.
    if (mode != 1 && mode != 4) return;
    int x = at<int>(map, 0x18), y = at<int>(map, 0x1c);
    if (x < 0 || x >= 100 || y < 0 || y >= 50 || kind(x, y) != 3) {
        x = ownGrid % 100; y = ownGrid / 100;
    }
    if (kind(x, y) != 3) return;
    int grid = y * 100 + x;
    BYTE* bases[8]; int count = 0; float maxRadius = 0;
    for (int i = 0; i < 350; ++i) {
        BYTE* base = static_cast<BYTE*>(world) + 0x2eb800 + i * 0x250;
        if (!at<BYTE>(base, 0) || at<int>(base, 8) != 2 || at<unsigned short>(base, 0x10) != grid) continue;
        if (count == 8) return; // Fail closed rather than silently truncating a new catalogue.
        float radius = at<float>(base, 0x34), angle = at<float>(base, 0x40);
        if (!_finite(radius) || !_finite(angle) || radius < 0) return;
        bases[count++] = base;
        if (radius > maxRadius) maxRadius = radius;
    }
    if (!count) return;
    float center[3] = {};
    reinterpret_cast<void(__cdecl*)(float*, int, int)>(0x4d3540)(center, x, y);
    auto models = reinterpret_cast<void**>(0x9d2f74);
    float extent = mode == 4 ? 1.25f : 1.0f;
    for (int i = 0; i < count; ++i) {
        float radius = maxRadius > 0 ? at<float>(bases[i], 0x34) / maxRadius * extent : 0;
        float angle = at<float>(bases[i], 0x40) * 0.017453292519943295f;
        float size[3] = {radius, 0, radius};
        reinterpret_cast<void(__thiscall*)(void*, const float*, const float*, int, int, unsigned, int, int)>(0x4cdf10)
            (reinterpret_cast<void*>(0x9d1320), center, size, 48, 1, 0xff8090a0, 0, 0);
        if (!models[i]) continue;
        void* resource = reinterpret_cast<void**>(0x9d14f0)[i];
        if (resource && (resource != preparedResources[i] || at<int>(resource, 0x2c) != 0)) {
            // Use the same material flag as the strategy star models (004d47a9).
            reinterpret_cast<void(__cdecl*)(void*, int)>(0x5e56c0)(resource, 0);
            preparedResources[i] = resource;
        }
        float matrix[16] = {};
        float diameter = at<float>(bases[i], 0x44);
        if (!_finite(diameter) || diameter <= 0) continue;
        if (diameter > 4) diameter = 4;
        matrix[0] = matrix[5] = matrix[10] = diameter * (mode == 4 ? 0.0125f : 0.0045f);
        matrix[15] = 1;
        matrix[12] = center[0] + cosf(angle) * radius;
        matrix[13] = center[1];
        matrix[14] = center[2] + sinf(angle) * radius;
        void* object = at<void*>(models[i], 4);
        if (!object) continue;
        at<int>(object, 0xdc) = 1;
        reinterpret_cast<void(__cdecl*)(void*, const float*)>(0x5dd480)(static_cast<BYTE*>(object) + 0x18, matrix);
        at<int>(models[i], 0xc0) = 10;
        at<int>(models[i], 0xb0) = 0;
    }
}

static void install() {
    if (InterlockedCompareExchange(&status, 1, 0)) return;
    if (!verifiedClient()) { status = -1; return; }
    BYTE* target = reinterpret_cast<BYTE*>(0x4d68d0);
    const BYTE expected[] = {0x83,0xec,0x58,0x53,0x56};
    if (memcmp(target, expected, sizeof(expected))) { status = -2; return; }
    DWORD previous = 0;
    if (!VirtualProtect(target, 5, PAGE_EXECUTE_READWRITE, &previous)) { status = -3; return; }
    target[0] = 0xe9;
    *reinterpret_cast<LONG*>(target + 1) = static_cast<LONG>(reinterpret_cast<BYTE*>(&drawLayout) - target - 5);
    FlushInstructionCache(GetCurrentProcess(), target, 5);
    DWORD ignored = 0; VirtualProtect(target, 5, previous, &ignored);
    // Only the strategy HUD call sites use the wrong group. Tactical group 104 remains intact.
    const unsigned hudGroups[] = {0x58d561,0x58d570,0x58d57f,0x58d58e,0x58d59d,0x58d5ac,0x58d5bb,
        0x58d5ca,0x58d5d9,0x58d5e8,0x58d6d3,0x58effb,0x58f01e,0x58f038,0x58f053,0x58f070,
        0x58f085,0x58f0e3,0x58f24c,0x58f339,0x58f38d};
    const BYTE oldGroup = 104, newGroup = 99;
    for (unsigned address : hudGroups) patchBytes(reinterpret_cast<void*>(address), &oldGroup, &newGroup, 1);
    // Both HUD paths pass cruising as a double, but the CD format consumes an int.
    patchBytes(reinterpret_cast<void*>(0x78d574), "%-4d\n", "%-4.f", 6);
    status = 2;
}

extern "C" LONG WINAPI LoghPlanetLayoutStatus() { return status; }
extern "C" HRESULT WINAPI ForwardDirectInput8Create(HINSTANCE instance, DWORD version, REFIID iid, LPVOID* output, void* outer) {
    static HMODULE real = nullptr;
    if (!real) {
        wchar_t path[MAX_PATH];
        UINT length = GetSystemDirectoryW(path, MAX_PATH);
        if (!length || length + 13 >= MAX_PATH) return E_FAIL;
        memcpy(path + length, L"\\dinput8.dll", 13 * sizeof(wchar_t));
        real = LoadLibraryW(path);
    }
    if (!real) return E_FAIL;
    auto create = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, REFIID, LPVOID*, void*)>(GetProcAddress(real, "DirectInput8Create"));
    if (!create) return E_FAIL;
    install();
    return create(instance, version, iid, output, outer);
}
