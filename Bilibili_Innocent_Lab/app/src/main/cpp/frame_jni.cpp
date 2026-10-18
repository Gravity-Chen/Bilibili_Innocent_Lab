#include <jni.h>
#include "player_interpolation/frame_engine.h"

#include <limits>
#include <map>
#include <mutex>
#include <utility>

using innocent::interpolation::Backend;
using innocent::interpolation::Code;
using innocent::interpolation::Engine;
using innocent::interpolation::ImageView;
using innocent::interpolation::ModelView;
using innocent::interpolation::MutableImageView;
using innocent::interpolation::Options;
using innocent::interpolation::Status;

namespace {
constexpr std::size_t kMaxEngines = 2;
constexpr std::size_t kMaxPixels = 1920u * 1080u;
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<Engine>> engines;
jlong sequence = 0;
std::size_t creating = 0;

std::shared_ptr<Engine> acquire(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = engines.find(id);
    return found == engines.end() ? nullptr : found->second;
}

jlongArray creation(JNIEnv* env, Code code, jlong handle) {
    const jlong values[] = {static_cast<jlong>(code), handle};
    auto result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

bool direct(JNIEnv* env, jobject buffer, void** data, std::size_t* capacity) {
    if (!buffer) return false;
    const auto length = env->GetDirectBufferCapacity(buffer);
    if (length <= 0 || static_cast<std::uint64_t>(length) > std::numeric_limits<std::size_t>::max()) return false;
    *data = env->GetDirectBufferAddress(buffer);
    *capacity = static_cast<std::size_t>(length);
    return *data != nullptr;
}
} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_create(
    JNIEnv* env, jobject, jint backend, jint gpuIndex, jint threads) {
    if ((backend != 0 && backend != 1) || gpuIndex < 0 || gpuIndex > 15 || threads < 1 || threads > 4)
        return creation(env, Code::InvalidArgument, 0);
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (engines.size() + creating >= kMaxEngines || sequence == std::numeric_limits<jlong>::max())
            return creation(env, Code::Busy, 0);
        ++creating;
    }
    Status status;
    std::shared_ptr<Engine> engine;
    try {
        Options options;
        options.backend = backend == 0 ? Backend::Cpu : Backend::Vulkan;
        options.gpuIndex = gpuIndex;
        options.threads = threads;
        options.maxPixels = kMaxPixels;
        engine = Engine::create(options, &status);
    } catch (...) {
        status.code = Code::AllocationFailed;
    }
    jlong id = 0;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        --creating;
        if (engine && status.ok()) {
            try {
                id = ++sequence;
                engines.emplace(id, std::move(engine));
            } catch (...) {
                id = 0;
                status.code = Code::AllocationFailed;
            }
        }
    }
    auto result = creation(env, status.code, id);
    // A failed JVM result allocation must not strand an unreachable native engine.
    if (!result && id != 0) {
        std::lock_guard<std::mutex> lock(registryMutex);
        engines.erase(id);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_load(
    JNIEnv* env, jobject, jlong id, jobject param, jobject weights) {
    auto engine = acquire(id);
    if (!engine) return static_cast<jint>(Code::NotLoaded);
    void *p = nullptr, *w = nullptr;
    std::size_t pSize = 0, wSize = 0;
    if (!direct(env, param, &p, &pSize) || !direct(env, weights, &w, &wSize))
        return static_cast<jint>(Code::InvalidArgument);
    return engine->load(ModelView{static_cast<std::uint8_t*>(p), pSize, static_cast<std::uint8_t*>(w), wSize}).value();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_process(
    JNIEnv* env, jobject, jlong id, jobject left, jobject right, jobject output,
    jint width, jint height, jint leftStride, jint rightStride, jint outputStride, jfloat timestep) {
    auto engine = acquire(id);
    if (!engine) return static_cast<jint>(Code::NotLoaded);
    void *l = nullptr, *r = nullptr, *o = nullptr;
    std::size_t lSize = 0, rSize = 0, oSize = 0;
    if (width <= 0 || height <= 0 || leftStride <= 0 || rightStride <= 0 || outputStride <= 0 ||
        !direct(env, left, &l, &lSize) || !direct(env, right, &r, &rSize) || !direct(env, output, &o, &oSize))
        return static_cast<jint>(Code::InvalidArgument);
    const auto bufferClass = env->FindClass("java/nio/ByteBuffer");
    if (!bufferClass) return static_cast<jint>(Code::InvalidArgument);
    const auto isReadOnly = env->GetMethodID(bufferClass, "isReadOnly", "()Z");
    const auto readOnly = isReadOnly ? env->CallBooleanMethod(output, isReadOnly) : JNI_TRUE;
    env->DeleteLocalRef(bufferClass);
    if (env->ExceptionCheck() || readOnly) return static_cast<jint>(Code::InvalidArgument);
    return engine->processRgb8(
        ImageView{static_cast<std::uint8_t*>(l), width, height, static_cast<std::size_t>(leftStride), lSize},
        ImageView{static_cast<std::uint8_t*>(r), width, height, static_cast<std::size_t>(rightStride), rSize}, timestep,
        MutableImageView{static_cast<std::uint8_t*>(o), width, height, static_cast<std::size_t>(outputStride), oSize}).value();
}

extern "C" JNIEXPORT void JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_close(
    JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Engine> removed;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto found = engines.find(id);
        if (found == engines.end()) return;
        removed = std::move(found->second);
        engines.erase(found);
    }
    // An in-flight call owns a shared reference. Erasing never frees its live engine.
}
