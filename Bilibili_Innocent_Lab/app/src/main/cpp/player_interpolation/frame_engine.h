#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace innocent::interpolation {

enum class Backend : int { Cpu = 0, Vulkan = 1 };
enum class Code : int {
    Ok = 0,
    InvalidArgument = 1,
    UnsupportedBackend = 2,
    GpuUnavailable = 3,
    ModelTooLarge = 4,
    InvalidModel = 5,
    ModelLoadFailed = 6,
    NotLoaded = 7,
    Busy = 8,
    AllocationFailed = 9,
    InferenceFailed = 10,
    InvalidOutput = 11
};

struct Status {
    Code code = Code::Ok;
    bool ok() const noexcept { return code == Code::Ok; }
    int value() const noexcept { return static_cast<int>(code); }
    const char* name() const noexcept;
};

struct Options {
    Backend backend = Backend::Vulkan;
    int gpuIndex = 0;
    int threads = 1;
    std::size_t maxPixels = 1920u * 1080u;
};

// Callers verify the approved model hashes before load. Buffers are borrowed only during load.
struct ModelView {
    const std::uint8_t* param = nullptr;
    std::size_t paramSize = 0;
    const std::uint8_t* weights = nullptr;
    std::size_t weightsSize = 0;
};

// Interleaved RGB8, top-to-bottom. Capacity includes the final row's visible bytes.
struct ImageView {
    const std::uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t strideBytes = 0;
    std::size_t byteCapacity = 0;
};

struct MutableImageView {
    std::uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t strideBytes = 0;
    std::size_t byteCapacity = 0;
};

// One bounded worker owns an engine. Concurrent load/process returns Busy without waiting.
// Destroy only after callers have stopped; the destructor never destroys ncnn's global GPU instance.
class Engine final {
public:
    static std::unique_ptr<Engine> create(const Options& options, Status* status = nullptr) noexcept;
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    Status load(ModelView model) noexcept;
    Status processRgb8(ImageView left, ImageView right, float timestep, MutableImageView output) noexcept;
    Backend backend() const noexcept;
    bool ready() const noexcept;

private:
    class Impl;
    explicit Engine(std::unique_ptr<Impl> impl) noexcept;
    std::unique_ptr<Impl> impl_;
};

} // namespace innocent::interpolation
