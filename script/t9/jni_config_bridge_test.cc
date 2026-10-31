// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the production JNI config iterator with a fake JVM and Rime API.
#include "../../app/src/main/jni/librime_jni/config.cc"

#include <cassert>
#include <iostream>
#include <new>
#include <string>

GlobalRefSingleton* GlobalRef = nullptr;
static RimeApi fake_api{};
extern "C" RimeApi* rime_get_api() { return &fake_api; }

namespace {
JNIEnv env{};
bool pending = false;
int mode = 0;
int ended = 0;
int began = 0;
int next_count = 0;
int set_count = 0;
int thrown = 0;
std::u16string key = u"switches";
int token;
}  // namespace

int main() {
  JNINativeInterface native{};
  native.ExceptionCheck = [](JNIEnv*) -> jboolean { return pending; };
  native.FindClass = [](JNIEnv*, const char*) -> jclass { assert(!pending); return reinterpret_cast<jclass>(&token); };
  native.NewGlobalRef = [](JNIEnv*, jobject) -> jobject { assert(!pending); return reinterpret_cast<jobject>(&token); };
  native.GetMethodID = [](JNIEnv*, jclass, const char*, const char*) -> jmethodID { assert(!pending); return reinterpret_cast<jmethodID>(&token); };
  native.GetStaticMethodID = [](JNIEnv*, jclass, const char*, const char*) -> jmethodID { assert(!pending); return reinterpret_cast<jmethodID>(&token); };
  native.DeleteLocalRef = [](JNIEnv*, jobject) {};
  native.GetStringLength = [](JNIEnv*, jstring) -> jsize { assert(!pending); return static_cast<jsize>(key.size()); };
  native.GetStringChars = [](JNIEnv*, jstring, jboolean*) -> const jchar* { assert(!pending); return reinterpret_cast<const jchar*>(key.data()); };
  native.ReleaseStringChars = [](JNIEnv*, jstring, const jchar*) {};
  native.NewObjectArray = [](JNIEnv*, jsize, jclass, jobject) -> jobjectArray {
    assert(!pending);
    if (mode == 3) { pending = true; return nullptr; }
    return reinterpret_cast<jobjectArray>(&token);
  };
  native.NewString = [](JNIEnv*, const jchar*, jsize) -> jstring {
    assert(!pending);
    if (mode == 1) { pending = true; return nullptr; }
    if (mode == 2) throw std::bad_alloc();
    return reinterpret_cast<jstring>(&token);
  };
  native.SetObjectArrayElement = [](JNIEnv*, jobjectArray, jsize, jobject) { assert(!pending); ++set_count; };
  native.ThrowNew = [](JNIEnv*, jclass, const char*) -> jint { assert(!pending); pending = true; ++thrown; return JNI_OK; };
  env.functions = &native;
  JNIInvokeInterface invoke{};
  invoke.GetEnv = [](JavaVM*, void** result, jint) -> jint { *result = &env; return JNI_OK; };
  invoke.AttachCurrentThread = [](JavaVM*, JNIEnv** result, void*) -> jint { *result = &env; return JNI_OK; };
  JavaVM vm{&invoke};
  GlobalRefSingleton refs(&vm);
  GlobalRef = &refs;

  fake_api.config_list_size = [](RimeConfig*, const char*) -> size_t { return 1; };
  fake_api.config_begin_list = [](RimeConfigIterator*, RimeConfig*, const char*) -> Bool { ++began; next_count = 0; return True; };
  fake_api.config_next = [](RimeConfigIterator* iter) -> Bool {
    if (next_count++) return False;
    iter->path = "switches/@0";
    return True;
  };
  fake_api.config_end = [](RimeConfigIterator*) { ++ended; };
  auto run = [&] {
    return Java_com_osfans_trime_core_RimeConfig_getRimeConfigListItemPath(
        &env, nullptr, 0, reinterpret_cast<jstring>(&key));
  };

  assert(run() != nullptr && !pending && began == 1 && ended == 1 && set_count == 1);
  mode = 1;
  assert(run() != nullptr && pending && began == 2 && ended == 2 && set_count == 1);
  pending = false;
  mode = 2;
  assert(run() == nullptr && pending && began == 3 && ended == 3 && set_count == 1 && thrown == 1);
  pending = false;
  mode = 3;
  assert(run() == nullptr && pending && began == 3 && ended == 3);
  std::cout << "PASS: config iterator closes once on normal, JNI pending and C++ failure paths\n";
}
