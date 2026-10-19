/* SPDX-License-Identifier: GPL-3.0-or-later
 * Private original-call observation only. No ClockSnapshot or presentation permission. */
#include "native_api_v2.h"
#if defined(__aarch64__)
#include "target_profile.h"
#endif
#include <jni.h>
#include <stdatomic.h>
#include <dlfcn.h>
#include <link.h>
#include <time.h>
#include <unistd.h>
#include <string.h>
#include <limits.h>
#include <stdbool.h>
#include <shadowhook.h>

_Static_assert(ATOMIC_INT_LOCK_FREE == 2 && ATOMIC_POINTER_LOCK_FREE == 2, "lock-free observer state");
#define EXPORT __attribute__((visibility("default")))
#define THREADS 32u
#define TOKENS 128u
#define DEPTH 4u
#define RING 64u
#define WORDS 16u

enum { CALLBACK = 1, PRELOADED = 2, TARGET_SEEN = 4, FINGERPRINT = 8,
       INSTALLING = 16, INSTALLED = 32, INSTALL_FAILED = 64, UNSUPPORTED = 128 };
enum { CORRELATED = 1, RETURN_BITS_MATCH = 2, REENTRANT = 4, BAD_BRACKET = 8 };
typedef double (*DoubleFunction)(void *);
typedef int (*IntFunction)(void *);
typedef int64_t (*PositionFunction)(void *);
typedef struct { uint64_t word[WORDS]; } Event;
typedef struct {
    uint32_t id, parent, vs_token, selector_count, clock_count, clock_token;
    int selector;
    void *vs;
    uint64_t clock_bits, before;
    unsigned int invalid;
} Master;
typedef struct { uint32_t id, token, master_count, last_master, invalid; uint64_t before; } Position;
typedef struct {
    atomic_uint tid, read_at, write_at;
    atomic_flag publishing;
    unsigned int master_depth, position_depth, stride;
    Master master[DEPTH];
    Position position[DEPTH];
    Event ring[RING];
} ThreadSlot;

static atomic_uint entered, claimed, api_version, api_flags, state, attempts, installed, drops, epoch, started, next_id;
static atomic_int hook_rc[4];
static atomic_uintptr_t tokens[TOKENS];
static ThreadSlot threads[THREADS];
#if defined(__aarch64__)
static void *stubs[4];
#endif
static atomic_uint sdk_initialized;
static void *backups[4];

static uint64_t bits(double value) { union { double d; uint64_t u; } result = {.d = value}; return result.u; }
static uint64_t monotonic_ns(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0 || value.tv_sec < 0 || value.tv_nsec < 0 || value.tv_nsec >= 1000000000L) return 0;
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}
static uint32_t identifier(void) {
    uint32_t value = atomic_fetch_add_explicit(&next_id, 1u, memory_order_relaxed) + 1u;
    if (value == 0) { atomic_store_explicit(&epoch, 0u, memory_order_release); atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed); }
    return value;
}
/* Address equivalence only: never a media/player/thread incarnation or ownership claim. */
static uint32_t token(void *pointer) {
    uintptr_t key = (uintptr_t)pointer;
    if (key == 0) return 0;
    for (unsigned int i = 0; i < TOKENS; ++i) {
        uintptr_t found = atomic_load_explicit(&tokens[i], memory_order_acquire);
        if (found == key) return i + 1u;
        if (found == 0 && atomic_compare_exchange_strong_explicit(&tokens[i], &found, key,
                memory_order_acq_rel, memory_order_acquire)) return i + 1u;
        if (found == key) return i + 1u;
    }
    atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed); return 0;
}
static ThreadSlot *slot(int create) {
    pid_t actual_tid = gettid();
    if (actual_tid <= 0) return NULL;
    unsigned int tid = (unsigned int)actual_tid;
    for (unsigned int i = 0; i < THREADS; ++i) {
        unsigned int found = atomic_load_explicit(&threads[i].tid, memory_order_acquire);
        if (found == tid) return &threads[i];
        if (create && found == 0 && atomic_compare_exchange_strong_explicit(&threads[i].tid, &found, tid,
                memory_order_acq_rel, memory_order_acquire)) return &threads[i];
        if (found == tid) return &threads[i];
    }
    if (create) atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed);
    return NULL;
}
static void publish(ThreadSlot *owner, Event *event, unsigned int expected_epoch) {
    if (expected_epoch == 0 || atomic_load_explicit(&epoch, memory_order_acquire) != expected_epoch) return;
    if (atomic_flag_test_and_set_explicit(&owner->publishing, memory_order_acquire)) {
        atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed); return;
    }
    unsigned int write = atomic_load_explicit(&owner->write_at, memory_order_relaxed);
    unsigned int read = atomic_load_explicit(&owner->read_at, memory_order_acquire);
    if (write - read < RING && atomic_load_explicit(&epoch, memory_order_acquire) == expected_epoch) {
        event->word[3] = (uint64_t)(owner - threads) + 1u;
        event->word[15] = atomic_load_explicit(&drops, memory_order_relaxed);
        owner->ring[write % RING] = *event;
        atomic_store_explicit(&owner->write_at, write + 1u, memory_order_release);
    } else atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed);
    atomic_flag_clear_explicit(&owner->publishing, memory_order_release);
}

static __attribute__((unused)) double observe_master(void *vs) {
    DoubleFunction original = (DoubleFunction)__atomic_load_n(&backups[0], __ATOMIC_ACQUIRE);
    unsigned int current = atomic_load_explicit(&epoch, memory_order_acquire);
    if (current == 0) return original(vs);
    ThreadSlot *owner = slot(1);
    if (owner == NULL || owner->master_depth >= DEPTH) {
        if (owner != NULL && owner->master_depth > 0) owner->master[owner->master_depth - 1u].invalid |= REENTRANT;
        atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed); return original(vs);
    }
    /* Force a sample in an original FFP position call; otherwise sample one in four. */
    if (owner->position_depth == 0 && owner->master_depth == 0 && owner->stride++ % 4u != 0) return original(vs);
    unsigned int level = owner->master_depth++;
    Master *call = &owner->master[level];
    memset(call, 0, sizeof(*call)); call->id = identifier(); call->vs = vs; call->vs_token = token(vs);
    call->selector = -1; call->before = monotonic_ns();
    if (owner->position_depth > 0) {
        Position *parent = &owner->position[owner->position_depth - 1u];
        call->parent = parent->id; ++parent->master_count; parent->last_master = call->id;
    }
    if (level != 0) { call->invalid = REENTRANT; owner->master[level - 1u].invalid |= REENTRANT; }
    double value = original(vs);
    uint64_t after = monotonic_ns(), value_bits = bits(value);
    Event event = {{0}};
    event.word[0] = 1; event.word[1] = call->id; event.word[2] = call->parent;
    event.word[4] = call->vs_token; event.word[5] = call->before; event.word[6] = after;
    event.word[7] = value_bits; event.word[8] = (uint64_t)(int64_t)call->selector;
    event.word[9] = call->selector_count; event.word[10] = call->clock_token;
    event.word[11] = call->clock_bits; event.word[12] = call->clock_count;
    unsigned int flags = call->invalid;
    if (call->before == 0 || after < call->before || after - call->before > UINT64_C(2000000)) flags |= BAD_BRACKET;
    if (call->selector_count == 1 && call->clock_count == 1 && call->selector >= 0 && call->selector <= 2 && call->vs_token != 0 && call->clock_token != 0) flags |= CORRELATED;
    if (call->clock_count == 1 && value_bits == call->clock_bits) flags |= RETURN_BITS_MATCH;
    event.word[13] = flags;
    --owner->master_depth;
    publish(owner, &event, current);
    return value;
}
static __attribute__((unused)) int observe_selector(void *vs) {
    IntFunction original = (IntFunction)__atomic_load_n(&backups[1], __ATOMIC_ACQUIRE);
    if (atomic_load_explicit(&epoch, memory_order_acquire) == 0) return original(vs);
    ThreadSlot *owner = slot(0);
    int value = original(vs);
    if (owner != NULL && owner->master_depth > 0) {
        Master *call = &owner->master[owner->master_depth - 1u];
        if (call->vs == vs) { ++call->selector_count; call->selector = value; }
        else call->invalid |= REENTRANT;
    }
    return value;
}
static __attribute__((unused)) double observe_clock(void *clock) {
    DoubleFunction original = (DoubleFunction)__atomic_load_n(&backups[2], __ATOMIC_ACQUIRE);
    if (atomic_load_explicit(&epoch, memory_order_acquire) == 0) return original(clock);
    ThreadSlot *owner = slot(0);
    double value = original(clock);
    if (owner != NULL && owner->master_depth > 0) {
        Master *call = &owner->master[owner->master_depth - 1u];
        ++call->clock_count; call->clock_token = token(clock); call->clock_bits = bits(value);
    }
    return value;
}
static __attribute__((unused)) int64_t observe_position(void *ffp) {
    PositionFunction original = (PositionFunction)__atomic_load_n(&backups[3], __ATOMIC_ACQUIRE);
    unsigned int current = atomic_load_explicit(&epoch, memory_order_acquire);
    if (current == 0) return original(ffp);
    ThreadSlot *owner = slot(1);
    if (owner == NULL || owner->position_depth >= DEPTH) {
        if (owner != NULL) {
            if (owner->position_depth > 0) owner->position[owner->position_depth - 1u].invalid |= REENTRANT;
            if (owner->master_depth > 0) owner->master[owner->master_depth - 1u].invalid |= REENTRANT;
        }
        atomic_fetch_add_explicit(&drops, 1u, memory_order_relaxed); return original(ffp);
    }
    unsigned int level = owner->position_depth++;
    Position *call = &owner->position[level];
    memset(call, 0, sizeof(*call)); call->id = identifier(); call->token = token(ffp); call->before = monotonic_ns();
    int64_t value = original(ffp);
    Event event = {{0}};
    event.word[0] = 2; event.word[1] = call->id; event.word[2] = level == 0 ? 0 : owner->position[level - 1u].id;
    event.word[4] = call->token; event.word[5] = call->before; event.word[6] = monotonic_ns(); event.word[7] = (uint64_t)value;
    event.word[8] = call->master_count; event.word[9] = call->last_master;
    event.word[13] = call->invalid | (level == 0 ? 0u : REENTRANT);
    --owner->position_depth;
    publish(owner, &event, current);
    return value;
}

#if defined(__aarch64__)
static int target_name(const char *name) {
    if (name == NULL) return 0;
    size_t size = strnlen(name, 512u);
    const char suffix[] = "libijkplayer.so";
    if (size >= 512u || size < sizeof(suffix) - 1u) return 0;
    size_t begin = size - (sizeof(suffix) - 1u);
    return (begin == 0 || name[begin - 1u] == '/' || name[begin - 1u] == '!') && strcmp(name + begin, suffix) == 0;
}
typedef struct { unsigned int count; uintptr_t base; unsigned int ranges_valid; } Candidate;
static int readable_range(struct dl_phdr_info *info, uintptr_t rva, size_t bytes, unsigned int flags) {
    if (rva > UINTPTR_MAX - info->dlpi_addr || info->dlpi_phnum > 1024u) return 0;
    uintptr_t start = info->dlpi_addr + rva;
    for (unsigned int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type != PT_LOAD || (p->p_flags & flags) != flags || p->p_vaddr > UINTPTR_MAX - info->dlpi_addr) continue;
        uintptr_t low = info->dlpi_addr + p->p_vaddr;
        if (p->p_memsz > UINTPTR_MAX - low) continue;
        uintptr_t high = low + p->p_memsz;
        if (start >= low && start <= high && bytes <= high - start) return 1;
    }
    return 0;
}
static int mapped_candidate(struct dl_phdr_info *info, size_t size, void *opaque) {
    (void)size;
    Candidate *candidate = opaque;
    if (target_name(info->dlpi_name)) {
        ++candidate->count; candidate->base = info->dlpi_addr;
        candidate->ranges_valid = (unsigned int)readable_range(info, TARGET_BUILD_ID_RVA, sizeof(target_build_id), PF_R);
        for (unsigned int i = 0; i < 4; ++i)
            candidate->ranges_valid &= (unsigned int)readable_range(info, target_defs[i].rva, sizeof(target_defs[i].prefix), PF_R | PF_X);
    }
    return 0;
}
#endif
/* Called on the sole bounded JNI worker, never from the framework loader callback. */
JNIEXPORT jboolean JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_tryInstall(JNIEnv *env, jclass owner) {
    (void)env; (void)owner;
#if defined(__aarch64__)
    if (!atomic_load_explicit(&sdk_initialized, memory_order_acquire) || !atomic_load_explicit(&epoch, memory_order_acquire)) return JNI_FALSE;
    unsigned int previous = atomic_load_explicit(&state, memory_order_acquire);
    if ((previous & TARGET_SEEN) != 0) return (previous & INSTALLED) != 0;
    Candidate candidate = {0}; dl_iterate_phdr(mapped_candidate, &candidate);
    if (candidate.count == 0) return JNI_FALSE;
    atomic_fetch_or_explicit(&state, TARGET_SEEN, memory_order_release);
    if (candidate.count != 1u || !candidate.ranges_valid) { atomic_fetch_or_explicit(&state, UNSUPPORTED, memory_order_release); return JNI_FALSE; }
    void *handle = shadowhook_dlopen("libijkplayer.so");
    if (handle == NULL) return JNI_FALSE;
    void *targets[4];
    Dl_info first;
    int valid = 1;
    for (unsigned int i = 0; i < 4; ++i) { targets[i] = shadowhook_dlsym_dynsym(handle, target_defs[i].name); if (targets[i] == NULL) valid = 0; }
    if (!valid || dladdr(targets[0], &first) == 0 || first.dli_fbase == NULL) { shadowhook_dlclose(handle); return JNI_FALSE; }
    uintptr_t base = (uintptr_t)first.dli_fbase;
    if (base != candidate.base) valid = 0;
    for (unsigned int i = 0; i < 4; ++i) {
        Dl_info location;
        if (base > UINTPTR_MAX - target_defs[i].rva || (uintptr_t)targets[i] != base + target_defs[i].rva ||
            dladdr(targets[i], &location) == 0 || location.dli_fbase != first.dli_fbase ||
            memcmp(targets[i], target_defs[i].prefix, sizeof(target_defs[i].prefix)) != 0) valid = 0;
    }
    if (base > UINTPTR_MAX - TARGET_BUILD_ID_RVA || memcmp((void *)(base + TARGET_BUILD_ID_RVA), target_build_id, sizeof(target_build_id)) != 0) valid = 0;
    shadowhook_dlclose(handle);
    if (!valid) { atomic_fetch_or_explicit(&state, UNSUPPORTED, memory_order_release); return JNI_FALSE; }
    atomic_fetch_or_explicit(&state, FINGERPRINT | INSTALLING, memory_order_release);
    void *replacements[4] = {(void *)observe_master, (void *)observe_selector, (void *)observe_clock, (void *)observe_position};
    const unsigned int order[] = {1u, 2u, 3u, 0u};
    for (unsigned int step = 0; step < 4; ++step) {
        unsigned int i = order[step];
        atomic_fetch_or_explicit(&attempts, 1u << i, memory_order_relaxed);
        stubs[i] = shadowhook_hook_func_addr(targets[i], replacements[i], &backups[i]);
        int result = shadowhook_get_errno();
        atomic_store_explicit(&hook_rc[i], result, memory_order_relaxed);
        if (result != 0 || stubs[i] == NULL || __atomic_load_n(&backups[i], __ATOMIC_ACQUIRE) == NULL) { atomic_fetch_or_explicit(&state, INSTALL_FAILED, memory_order_release); atomic_store_explicit(&epoch, 0u, memory_order_release); break; }
        atomic_fetch_or_explicit(&installed, 1u << i, memory_order_release);
    }
    atomic_fetch_and_explicit(&state, ~INSTALLING, memory_order_release);
    if (atomic_load_explicit(&installed, memory_order_acquire) == 15u) atomic_fetch_or_explicit(&state, INSTALLED, memory_order_release);
    return atomic_load_explicit(&installed, memory_order_acquire) == 15u;
#else
    atomic_fetch_or_explicit(&state, UNSUPPORTED, memory_order_release); return JNI_FALSE;
#endif
}

EXPORT NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    atomic_store_explicit(&entered, 1u, memory_order_release);
    unsigned int expected = 0;
    if (!atomic_compare_exchange_strong_explicit(&claimed, &expected, 1u, memory_order_relaxed, memory_order_relaxed)) return NULL;
    unsigned int flags = 32u, version = 0;
    if (entries != NULL) {
        version = entries->version; flags |= 2u;
        if (version == 2u) { flags |= 4u; if (entries->hookFunc != NULL) flags |= 8u; if (entries->unhookFunc != NULL) flags |= 16u; }
    }
    atomic_store_explicit(&api_version, version, memory_order_relaxed);
    atomic_store_explicit(&api_flags, flags, memory_order_release);
    return NULL;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved; JNIEnv *env = NULL;
    return vm != NULL && (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
JNIEXPORT jint JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_initialize(JNIEnv *env, jclass owner, jstring expected) {
    (void)owner;
    if (expected == NULL) return -1;
    const char *path = (*env)->GetStringUTFChars(env, expected, NULL); if (path == NULL) return -1;
    Dl_info info; int matches = dladdr((void *)shadowhook_init, &info) != 0 && info.dli_fname != NULL && strcmp(path, info.dli_fname) == 0;
    (*env)->ReleaseStringUTFChars(env, expected, path);
    if (!matches || strcmp(shadowhook_get_version(), "shadowhook version 2.0.1") != 0) return -1;
    int result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (result == 0) {
        for (unsigned int i = 0; i < THREADS; ++i) atomic_flag_clear_explicit(&threads[i].publishing, memory_order_relaxed);
        atomic_store_explicit(&sdk_initialized, 1u, memory_order_release);
        atomic_store_explicit(&state, CALLBACK, memory_order_release);
    }
    return result;
}
JNIEXPORT jlong JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_queryFlags(JNIEnv *env, jclass owner) {
    (void)env; (void)owner;
    unsigned int flags = atomic_load_explicit(&api_flags, memory_order_acquire);
    if (atomic_load_explicit(&entered, memory_order_acquire)) flags |= 1u;
    return (jlong)(((uint64_t)atomic_load_explicit(&api_version, memory_order_relaxed) << 32) | flags);
}
JNIEXPORT jboolean JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_beginObservation(JNIEnv *env, jclass owner) {
    (void)env; (void)owner;
    unsigned int flags = atomic_load_explicit(&state, memory_order_acquire);
    if ((flags & (PRELOADED | UNSUPPORTED | INSTALL_FAILED)) != 0 || (flags & CALLBACK) == 0) return JNI_FALSE;
    unsigned int expected = 0;
    if (!atomic_compare_exchange_strong_explicit(&started, &expected, 1u, memory_order_relaxed, memory_order_relaxed)) return JNI_FALSE;
    atomic_store_explicit(&epoch, 1u, memory_order_release);
    if ((atomic_load_explicit(&state, memory_order_acquire) & INSTALL_FAILED) != 0) {
        atomic_store_explicit(&epoch, 0u, memory_order_release); return JNI_FALSE;
    }
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_endObservation(JNIEnv *env, jclass owner) {
    (void)env; (void)owner;
    atomic_store_explicit(&started, 1u, memory_order_release);
    atomic_store_explicit(&epoch, 0u, memory_order_release);
}
JNIEXPORT jlongArray JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_statistics(JNIEnv *env, jclass owner) {
    (void)owner;
    unsigned int installed_value = atomic_load_explicit(&installed, memory_order_acquire);
    unsigned int attempts_value = atomic_load_explicit(&attempts, memory_order_acquire);
    jlong values[10] = {(jlong)atomic_load_explicit(&state, memory_order_acquire),
        attempts_value, installed_value,
        atomic_load_explicit(&drops, memory_order_relaxed), atomic_load_explicit(&epoch, memory_order_acquire),
        atomic_load_explicit(&next_id, memory_order_relaxed), 0, 0, 0, 0};
    for (unsigned int i = 0; i < 4; ++i) values[6u + i] = atomic_load_explicit(&hook_rc[i], memory_order_relaxed);
    jlongArray array = (*env)->NewLongArray(env, 10);
    if (array != NULL) (*env)->SetLongArrayRegion(env, array, 0, 10, values);
    return array;
}
JNIEXPORT jlongArray JNICALL Java_research_innocent_frame_observer_OwnedNativeBridge_drain(JNIEnv *env, jclass owner) {
    (void)owner;
    jlong values[64u * WORDS]; unsigned int count = 0;
    for (unsigned int i = 0; i < THREADS && count < 64u; ++i) {
        ThreadSlot *source = &threads[i];
        unsigned int read = atomic_load_explicit(&source->read_at, memory_order_relaxed);
        unsigned int write = atomic_load_explicit(&source->write_at, memory_order_acquire);
        while (read != write && count < 64u) {
            for (unsigned int j = 0; j < WORDS; ++j) values[count * WORDS + j] = (jlong)source->ring[read % RING].word[j];
            ++read; ++count;
        }
        atomic_store_explicit(&source->read_at, read, memory_order_release);
    }
    jlongArray array = (*env)->NewLongArray(env, (jsize)(count * WORDS));
    if (array == NULL) { atomic_fetch_add_explicit(&drops, count, memory_order_relaxed); return NULL; }
    if (count != 0) (*env)->SetLongArrayRegion(env, array, 0, (jsize)(count * WORDS), values);
    if ((*env)->ExceptionCheck(env)) atomic_fetch_add_explicit(&drops, count, memory_order_relaxed);
    return array;
}
