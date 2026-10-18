#include "frame_engine.h"

#include "upstream/rife_warp.h"
#include <datareader.h>
#include <gpu.h>
#include <net.h>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <vector>

namespace innocent::interpolation {
namespace {
constexpr std::size_t MaxParamBytes = 1024u * 1024u;
constexpr std::size_t MaxWeightBytes = 32u * 1024u * 1024u;
constexpr std::size_t MaxPixels = 4096u * 2160u;
constexpr int MaxDimension = 4096;

class BoundedReader final : public ncnn::DataReader {
public:
    BoundedReader(const std::uint8_t* bytes, std::size_t size) : bytes_(bytes), size_(size) {}
    std::size_t read(void* destination, std::size_t size) const override {
        if (size > size_ - offset_ || (size != 0 && destination == nullptr)) {
            truncated_ = true;
            return 0;
        }
        if (size != 0) std::memcpy(destination, bytes_ + offset_, size);
        offset_ += size;
        return size;
    }
    // Never expose borrowed weights as a retained model pointer; ncnn must copy through read.
    std::size_t reference(std::size_t, const void**) const override { return 0; }
    bool truncated() const noexcept { return truncated_; }
private:
    const std::uint8_t* bytes_;
    std::size_t size_;
    mutable std::size_t offset_ = 0;
    mutable bool truncated_ = false;
};

bool layoutValid(const void* data, int width, int height, std::size_t stride,
                 std::size_t capacity, std::size_t maxPixels) noexcept {
    if (data == nullptr || width <= 0 || height <= 0 || width > MaxDimension || height > MaxDimension)
        return false;
    const auto w = static_cast<std::size_t>(width);
    const auto h = static_cast<std::size_t>(height);
    if (w > maxPixels / h) return false;
    const auto rowBytes = w * 3u;
    if (stride < rowBytes || (h > 1 && stride > (std::numeric_limits<std::size_t>::max() - rowBytes) / (h - 1)))
        return false;
    return (h - 1) * stride + rowBytes <= capacity;
}

bool modelShapeValid(const ncnn::Net& net) {
    const auto& inputs = net.input_names();
    const auto& outputs = net.output_names();
    if (inputs.size() != 3 || outputs.size() != 1 || std::strcmp(outputs[0], "out0") != 0) return false;
    for (const char* required : {"in0", "in1", "in2"}) {
        bool found = false;
        for (const char* input : inputs) found = found || std::strcmp(required, input) == 0;
        if (!found) return false;
    }
    return true;
}

Status gpuReady(int index) {
#if NCNN_VULKAN
    static std::once_flag initialized;
    static int result = -1;
    std::call_once(initialized, [] { result = ncnn::create_gpu_instance(); });
    if (result != 0 || index < 0 || index >= ncnn::get_gpu_count() || ncnn::get_gpu_device(index) == nullptr)
        return {Code::GpuUnavailable};
    return {};
#else
    (void)index;
    return {Code::UnsupportedBackend};
#endif
}

ncnn::Mat inputTensor(ImageView image, int paddedWidth, int paddedHeight) {
    ncnn::Mat tensor(paddedWidth, paddedHeight, 3);
    if (tensor.empty()) return tensor;
    tensor.fill(0.f);
    for (int channel = 0; channel < 3; ++channel) {
        ncnn::Mat plane = tensor.channel(channel);
        for (int y = 0; y < image.height; ++y) {
            const auto* pixels = image.data + static_cast<std::size_t>(y) * image.strideBytes;
            float* row = plane.row(y);
            for (int x = 0; x < image.width; ++x) row[x] = pixels[x * 3 + channel] * (1.f / 255.f);
        }
    }
    return tensor;
}
} // namespace

class Engine::Impl {
public:
    explicit Impl(Options value) : options(value) {}
    Options options;
    std::mutex mutex;
    std::atomic<bool> loaded{false};
    std::unique_ptr<ncnn::Net> network;
    std::vector<std::uint8_t> staging;
};

const char* Status::name() const noexcept {
    switch (code) {
        case Code::Ok: return "ok";
        case Code::InvalidArgument: return "invalid_argument";
        case Code::UnsupportedBackend: return "unsupported_backend";
        case Code::GpuUnavailable: return "gpu_unavailable";
        case Code::ModelTooLarge: return "model_too_large";
        case Code::InvalidModel: return "invalid_model";
        case Code::ModelLoadFailed: return "model_load_failed";
        case Code::NotLoaded: return "not_loaded";
        case Code::Busy: return "busy";
        case Code::AllocationFailed: return "allocation_failed";
        case Code::InferenceFailed: return "inference_failed";
        case Code::InvalidOutput: return "invalid_output";
    }
    return "unknown";
}

Engine::Engine(std::unique_ptr<Impl> impl) noexcept : impl_(std::move(impl)) {}
Engine::~Engine() = default;

std::unique_ptr<Engine> Engine::create(const Options& options, Status* status) noexcept {
    const auto fail = [status](Code code) -> std::unique_ptr<Engine> {
        if (status != nullptr) *status = {code};
        return nullptr;
    };
    try {
        if (options.threads < 1 || options.threads > 8 || options.maxPixels == 0 || options.maxPixels > MaxPixels)
            return fail(Code::InvalidArgument);
        if (options.backend != Backend::Cpu && options.backend != Backend::Vulkan) return fail(Code::UnsupportedBackend);
        if (options.backend == Backend::Vulkan) {
            const Status gpu = gpuReady(options.gpuIndex);
            if (!gpu.ok()) return fail(gpu.code);
        }
        auto engine = std::unique_ptr<Engine>(new Engine(std::make_unique<Impl>(options)));
        if (status != nullptr) *status = {};
        return engine;
    } catch (const std::bad_alloc&) { return fail(Code::AllocationFailed); }
    catch (...) { return fail(Code::InferenceFailed); }
}

Status Engine::load(ModelView model) noexcept {
    try {
        std::unique_lock<std::mutex> guard(impl_->mutex, std::try_to_lock);
        if (!guard.owns_lock()) return {Code::Busy};
        impl_->loaded.store(false);
        impl_->network.reset();
        if (model.param == nullptr || model.weights == nullptr || model.paramSize == 0 || model.weightsSize == 0)
            return {Code::InvalidArgument};
        if (model.paramSize > MaxParamBytes || model.weightsSize > MaxWeightBytes) return {Code::ModelTooLarge};
        if (std::memchr(model.param, 0, model.paramSize) != nullptr) return {Code::InvalidModel};
        // load_param_mem needs a NUL terminator; the caller's buffer need not provide one.
        const std::string param(reinterpret_cast<const char*>(model.param), model.paramSize);
        int magic = 0, layers = 0, blobs = 0;
        std::istringstream header(param);
        if (!(header >> magic >> layers >> blobs) || magic != 7767517 || layers <= 0 || layers > 1024 || blobs <= 0 || blobs > 8192)
            return {Code::InvalidModel};
        auto network = std::make_unique<ncnn::Net>();
        network->opt.num_threads = impl_->options.threads;
        network->opt.use_vulkan_compute = impl_->options.backend == Backend::Vulkan;
        // Keep a float32 reference path until each device's lower precision path is separately verified.
        network->opt.use_fp16_packed = false;
        network->opt.use_fp16_storage = false;
        network->opt.use_fp16_arithmetic = false;
        network->opt.use_bf16_storage = false;
        network->opt.use_int8_inference = false;
#if NCNN_VULKAN
        if (impl_->options.backend == Backend::Vulkan) network->set_vulkan_device(impl_->options.gpuIndex);
#endif
        if (network->register_custom_layer("rife.Warp", detail::createWarp) != 0 ||
            network->load_param_mem(param.c_str()) != 0 || !modelShapeValid(*network)) return {Code::InvalidModel};
        BoundedReader weights(model.weights, model.weightsSize);
        const int loaded = network->load_model(weights);
        if (loaded != 0 || weights.truncated()) return {Code::ModelLoadFailed};
#if NCNN_VULKAN
        if (impl_->options.backend == Backend::Vulkan) {
            if (!network->opt.use_vulkan_compute || network->vulkan_device() == nullptr) return {Code::GpuUnavailable};
            for (const auto* layer : network->layers()) {
                if (layer == nullptr || (!layer->support_vulkan && layer->type != "Input" && layer->type != "Split"))
                    return {Code::UnsupportedBackend};
            }
        }
#endif
        impl_->network = std::move(network);
        impl_->loaded.store(true);
        return {};
    } catch (const std::bad_alloc&) { return {Code::AllocationFailed}; }
    catch (...) { return {Code::ModelLoadFailed}; }
}

Status Engine::processRgb8(ImageView left, ImageView right, float timestep, MutableImageView output) noexcept {
    try {
        std::unique_lock<std::mutex> guard(impl_->mutex, std::try_to_lock);
        if (!guard.owns_lock()) return {Code::Busy};
        if (!impl_->loaded.load() || !impl_->network) return {Code::NotLoaded};
        if (!std::isfinite(timestep) || timestep < 0.f || timestep > 1.f ||
            left.width != right.width || left.height != right.height || left.width != output.width || left.height != output.height ||
            !layoutValid(left.data, left.width, left.height, left.strideBytes, left.byteCapacity, impl_->options.maxPixels) ||
            !layoutValid(right.data, right.width, right.height, right.strideBytes, right.byteCapacity, impl_->options.maxPixels) ||
            !layoutValid(output.data, output.width, output.height, output.strideBytes, output.byteCapacity, impl_->options.maxPixels))
            return {Code::InvalidArgument};
        const auto rowBytes = static_cast<std::size_t>(left.width) * 3u;
        impl_->staging.resize(rowBytes * static_cast<std::size_t>(left.height));
        if (timestep == 0.f || timestep == 1.f) {
            const auto image = timestep == 0.f ? left : right;
            for (int y = 0; y < image.height; ++y)
                std::memcpy(impl_->staging.data() + static_cast<std::size_t>(y) * rowBytes,
                            image.data + static_cast<std::size_t>(y) * image.strideBytes, rowBytes);
        } else {
            const int paddedWidth = (left.width + 31) / 32 * 32;
            const int paddedHeight = (left.height + 31) / 32 * 32;
            const ncnn::Mat first = inputTensor(left, paddedWidth, paddedHeight);
            const ncnn::Mat second = inputTensor(right, paddedWidth, paddedHeight);
            ncnn::Mat time(paddedWidth, paddedHeight, 1);
            if (first.empty() || second.empty() || time.empty()) return {Code::AllocationFailed};
            time.fill(timestep);
            ncnn::Extractor extractor = impl_->network->create_extractor();
            ncnn::Mat result;
            if (extractor.input("in0", first) != 0 || extractor.input("in1", second) != 0 ||
                extractor.input("in2", time) != 0 || extractor.extract("out0", result) != 0)
                return {Code::InferenceFailed};
            if (result.empty() || result.dims != 3 || result.w != paddedWidth || result.h != paddedHeight ||
                result.c != 3 || result.elempack != 1 || result.elemsize != sizeof(float)) return {Code::InvalidOutput};
            for (int y = 0; y < left.height; ++y) {
                const float* red = result.channel(0).row(y);
                const float* green = result.channel(1).row(y);
                const float* blue = result.channel(2).row(y);
                auto* pixels = impl_->staging.data() + static_cast<std::size_t>(y) * rowBytes;
                for (int x = 0; x < left.width; ++x) {
                    const float values[] = {red[x], green[x], blue[x]};
                    for (int c = 0; c < 3; ++c) {
                        if (!std::isfinite(values[c])) return {Code::InvalidOutput};
                        const float scaled = values[c] * 255.f + 0.5f;
                        pixels[x * 3 + c] = scaled <= 0.f ? 0 : scaled >= 255.f ? 255 : static_cast<std::uint8_t>(scaled);
                    }
                }
            }
        }
        // Commit only a complete valid result; error paths preserve output, including aliased inputs.
        for (int y = 0; y < output.height; ++y)
            std::memcpy(output.data + static_cast<std::size_t>(y) * output.strideBytes,
                        impl_->staging.data() + static_cast<std::size_t>(y) * rowBytes, rowBytes);
        return {};
    } catch (const std::bad_alloc&) { return {Code::AllocationFailed}; }
    catch (...) { return {Code::InferenceFailed}; }
}

Backend Engine::backend() const noexcept { return impl_->options.backend; }
bool Engine::ready() const noexcept { return impl_->loaded.load(); }
} // namespace innocent::interpolation
