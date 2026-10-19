/* SPDX-License-Identifier: GPL-3.0-or-later
 * Public ABI fields adapted from LSPosed/LSPosed commit
 * df74d83eb03a44cc6ad268841ac2ada28d077c77, native_api.h lines 32-45.
 * No framework implementation or internal header is included.
 * Full provenance and GPL license are included in assets/licenses/.
 */
#ifndef INNOCENT_NATIVE_API_V2_H
#define INNOCENT_NATIVE_API_V2_H
#include <stdint.h>
#include <stddef.h>
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct {
    uint32_t version;
    HookFunType hookFunc;
    UnhookFunType unhookFunc;
} NativeAPIEntries;
typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);
_Static_assert(sizeof(HookFunType) == sizeof(void *), "Android function pointer ABI");
_Static_assert(offsetof(NativeAPIEntries, hookFunc) == (sizeof(void *) == 8 ? 8 : 4), "API2 hook offset");
_Static_assert(offsetof(NativeAPIEntries, unhookFunc) == (sizeof(void *) == 8 ? 16 : 8), "API2 unhook offset");
_Static_assert(sizeof(NativeAPIEntries) == (sizeof(void *) == 8 ? 24 : 12), "API2 entry size");
#endif
