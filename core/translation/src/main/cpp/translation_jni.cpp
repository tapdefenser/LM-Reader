#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include "translator/parser.h"
#include "translator/service.h"
#include "common/logging.h"

namespace {
using namespace marian::bergamot;
std::mutex guard;
std::unique_ptr<BlockingService> service;
std::unordered_map<std::string, std::shared_ptr<TranslationModel>> models;

std::string bytes(JNIEnv* env, jbyteArray value) {
  const auto count = env->GetArrayLength(value);
  std::string result(count, '\0');
  env->GetByteArrayRegion(value, 0, count, reinterpret_cast<jbyte*>(result.data()));
  return result;
}
void fail(JNIEnv* env, const std::exception& error) {
  // Error messages are ASCII diagnostics. Text payloads always use UTF-8 byte arrays.
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_load(JNIEnv* env, jobject, jbyteArray key, jbyteArray config) {
  std::lock_guard<std::mutex> lock(guard);
  try {
    // The blocking service is single-threaded. Checked Marian failures must
    // become Java errors instead of aborting the reader process.
    marian::setThrowExceptionOnAbort(true);
    if (!service) {
      BlockingService::Config settings;
      settings.cacheSize = 0;
      settings.logger.level = "off";
      service = std::make_unique<BlockingService>(settings);
    }
    const auto id = bytes(env, key);
    if (models.count(id) == 0) {
      auto options = parseOptionsFromString(bytes(env, config), true, "");
      models.emplace(id, std::make_shared<TranslationModel>(options));
    }
  } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lmreader_core_translation_BergamotNative_translate(JNIEnv* env, jobject, jbyteArray key, jbyteArray input) {
  std::lock_guard<std::mutex> lock(guard);
  try {
    auto model = models.at(bytes(env, key));
    ResponseOptions options;
    options.HTML = false;
    options.alignment = false;
    options.qualityScores = false;
    options.sentenceMappings = false;
    std::vector<std::string> texts{bytes(env, input)};
    auto result = service->translateMultiple(model, std::move(texts), {options});
    if (result.size() != 1) throw std::runtime_error("Unexpected translation count");
    const auto& output = result.front().target.text;
    auto array = env->NewByteArray(static_cast<jsize>(output.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
    return array;
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_unload(JNIEnv* env, jobject, jbyteArray key) {
  std::lock_guard<std::mutex> lock(guard);
  models.erase(bytes(env, key));
  if (models.empty()) service.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_release(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(guard);
  models.clear();
  service.reset();
}
