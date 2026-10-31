#include <rime_api.h>

#include <limits>
#include <memory>

#include "jni-utils.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_osfans_trime_core_RimeConfig_openRimeConfig(JNIEnv* env,
                                                     jclass /* thiz */,
                                                     jstring config_id) {
  return jniCall(env, [&]() -> jlong {
    auto api = rime_get_api();
    auto id = CString(env, config_id);
    auto config = std::make_unique<RimeConfig>();
    if (!api->config_open(id, config.get())) return 0;
    return reinterpret_cast<jlong>(config.release());
  });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_osfans_trime_core_RimeConfig_openRimeUserConfig(JNIEnv* env,
                                                         jclass /* thiz */,
                                                         jstring config_id) {
  return jniCall(env, [&]() -> jlong {
    auto api = rime_get_api();
    auto id = CString(env, config_id);
    auto config = std::make_unique<RimeConfig>();
    if (!api->user_config_open(id, config.get())) return 0;
    return reinterpret_cast<jlong>(config.release());
  });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_osfans_trime_core_RimeConfig_openRimeSchema(JNIEnv* env,
                                                     jclass /* thiz */,
                                                     jstring schema_id) {
  return jniCall(env, [&]() -> jlong {
    auto api = rime_get_api();
    auto id = CString(env, schema_id);
    auto config = std::make_unique<RimeConfig>();
    if (!api->schema_open(id, config.get())) return 0;
    return reinterpret_cast<jlong>(config.release());
  });
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_RimeConfig_closeRimeConfig(JNIEnv* env,
                                                      jclass /* thiz */,
                                                      jlong peer) {
  jniCall(env, [&] {
    auto api = rime_get_api();
    auto config =
        std::unique_ptr<RimeConfig>(reinterpret_cast<RimeConfig*>(peer));
    api->config_close(config.get());
  });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_RimeConfig_getRimeConfigInt(JNIEnv* env,
                                                       jclass /* thiz */,
                                                       jlong peer,
                                                       jstring key) {
  return jniCall(env, [&]() -> jobject {
    auto api = rime_get_api();
    int value;
    if (!api->config_get_int(reinterpret_cast<RimeConfig*>(peer),
                             CString(env, key), &value))
      return nullptr;
    return env->NewObject(GlobalRef->Integer, GlobalRef->IntegerInit, value);
  });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_RimeConfig_getRimeConfigString(JNIEnv* env,
                                                          jclass /* thiz */,
                                                          jlong peer,
                                                          jstring key) {
  return jniCall(env, [&]() -> jstring {
    auto api = rime_get_api();
    const char* value = api->config_get_cstring(
        reinterpret_cast<RimeConfig*>(peer), CString(env, key));
    if (!value) return nullptr;
    return makeJavaString(env, value);
  });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_core_RimeConfig_getRimeConfigListItemPath(
    JNIEnv* env, jclass /* thiz */, jlong peer, jstring key) {
  return jniCall(env, [&]() -> jobjectArray {
    auto api = rime_get_api();
    auto config = reinterpret_cast<RimeConfig*>(peer);
    auto cKey = CString(env, key);
    auto size = api->config_list_size(config, cKey);
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max()))
      throw std::length_error("Configuration list exceeds JNI array limit");
    RimeConfigIterator iter;
    int i = 0;
    auto array = env->NewObjectArray(static_cast<jsize>(size),
                                     GlobalRef->String, nullptr);
    if (!array || env->ExceptionCheck() ||
        !api->config_begin_list(&iter, config, cKey))
      return array;
    auto iterator_guard =
        std::unique_ptr<RimeConfigIterator, decltype(api->config_end)>(
            &iter, api->config_end);
    while (api->config_next(&iter)) {
      if (i >= static_cast<jsize>(size))
        throw std::runtime_error("Configuration list changed during iteration");
      JString path(env, iter.path);
      if (env->ExceptionCheck()) break;
      env->SetObjectArrayElement(array, i++, path);
      if (env->ExceptionCheck()) break;
    }
    return array;
  });
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_RimeConfig_setRimeConfigBool(JNIEnv* env,
                                                        jclass /* thiz */,
                                                        jlong peer, jstring key,
                                                        jboolean value) {
  jniCall(env, [&] {
    auto api = rime_get_api();
    api->config_set_bool(reinterpret_cast<RimeConfig*>(peer), CString(env, key),
                         value);
  });
}
