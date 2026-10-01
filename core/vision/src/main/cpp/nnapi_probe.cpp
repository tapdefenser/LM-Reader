#include <jni.h>
#include <dlfcn.h>
#include <android/NeuralNetworks.h>

// Resolve dynamically: some Android distributions omit NNAPI or expose an
// incomplete shim. ORT assumes these entry points exist and may otherwise crash.
extern "C" JNIEXPORT jstring JNICALL
Java_com_lmreader_core_vision_NnapiAvailability_nativeUnavailableReason(JNIEnv* env, jobject) {
    void* library = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return env->NewStringUTF("系统没有可用的 NNAPI 运行库");
    using Count = int (*)(uint32_t*);
    using Device = int (*)(uint32_t, ANeuralNetworksDevice**);
    using Type = int (*)(const ANeuralNetworksDevice*, int32_t*);
    auto count = reinterpret_cast<Count>(dlsym(library, "ANeuralNetworks_getDeviceCount"));
    auto device = reinterpret_cast<Device>(dlsym(library, "ANeuralNetworks_getDevice"));
    auto type = reinterpret_cast<Type>(dlsym(library, "ANeuralNetworksDevice_getType"));
    const char* reason = nullptr;
    if (!count || !device || !type) reason = "系统 NNAPI 驱动接口不完整";
    else {
        uint32_t total = 0;
        bool hardware = false;
        if (count(&total) != ANEURALNETWORKS_NO_ERROR) reason = "系统无法枚举 NNAPI 设备";
        else {
            for (uint32_t i = 0; i < total; ++i) {
                ANeuralNetworksDevice* current = nullptr;
                int32_t kind = ANEURALNETWORKS_DEVICE_UNKNOWN;
                if (device(i, &current) == ANEURALNETWORKS_NO_ERROR && current &&
                    type(current, &kind) == ANEURALNETWORKS_NO_ERROR &&
                    (kind == ANEURALNETWORKS_DEVICE_GPU || kind == ANEURALNETWORKS_DEVICE_ACCELERATOR)) {
                    hardware = true;
                    break;
                }
            }
            if (!hardware) reason = "系统 NNAPI 没有提供 GPU/NPU 设备";
        }
    }
    dlclose(library);
    return reason ? env->NewStringUTF(reason) : nullptr;
}
