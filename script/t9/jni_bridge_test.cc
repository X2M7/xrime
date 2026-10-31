// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/jni/librime_jni/jni-utils.h"
#include "../../app/src/main/jni/librime_jni/objconv.h"

#include <cassert>
#include <iostream>
#include <new>
#include <type_traits>

GlobalRefSingleton* GlobalRef = nullptr;

namespace {
bool attached = false;
int attachments = 0;
int detachments = 0;
int attach_status = JNI_OK;
int env_status = JNI_OK;
JNIEnv fake_env{};
std::u16string java_result;
int utf_calls = 0;
int releases = 0;
bool pending = false;
bool fail_chars = false;
bool fail_new_string = false;
bool pending_new_string = false;
bool fail_new_array = false;
int thrown = 0;
int object_calls = 0;
int array_set_calls = 0;
std::string fallback_message;
jsize array_length = 0;
int array_length_calls = 0;
int array_element_calls = 0;
bool inject_init_failure = false;
int fail_operation = 0;
int init_operation = 0;
int global_created = 0;
int global_deleted = 0;
int local_created = 0;
int local_deleted = 0;

bool fail_init_operation() {
  assert(!pending);
  if (!inject_init_failure) return false;
  if (++init_operation != fail_operation) return false;
  pending = true;
  return true;
}

jint get_env(JavaVM*, void** result, jint) {
  *result = attached ? &fake_env : nullptr;
  return env_status != JNI_OK ? env_status : attached ? JNI_OK : JNI_EDETACHED;
}
jint attach(JavaVM*, JNIEnv** result, void*) {
  if (attach_status != JNI_OK) return attach_status;
  attached = true;
  ++attachments;
  *result = &fake_env;
  return JNI_OK;
}
jint detach(JavaVM*) {
  assert(attached);
  attached = false;
  ++detachments;
  return JNI_OK;
}
jsize length(JNIEnv*, jstring value) {
  return static_cast<jsize>(reinterpret_cast<std::u16string*>(value)->size());
}
const jchar* chars(JNIEnv*, jstring value, jboolean*) {
  if (fail_chars) { pending = true; return nullptr; }
  return reinterpret_cast<const jchar*>(reinterpret_cast<std::u16string*>(value)->data());
}
void release(JNIEnv*, jstring, const jchar*) { ++releases; }
jstring new_string(JNIEnv*, const jchar* value, jsize size) {
  assert(!pending);
  if (pending_new_string) { pending = true; return nullptr; }
  if (fail_new_string) throw std::bad_alloc();
  java_result.assign(reinterpret_cast<const char16_t*>(value), size);
  return reinterpret_cast<jstring>(&java_result);
}
jstring new_utf(JNIEnv*, const char*) {
  ++utf_calls;
  return nullptr;
}
void delete_ref(JNIEnv*, jobject value) { if (inject_init_failure && value) ++local_deleted; }
}  // namespace

int main() {
  JNIInvokeInterface invoke{};
  invoke.GetEnv = get_env;
  invoke.AttachCurrentThread = attach;
  invoke.DetachCurrentThread = detach;
  JavaVM vm{&invoke};
  JNINativeInterface native{};
  native.GetStringLength = length;
  native.GetStringChars = chars;
  native.ReleaseStringChars = release;
  native.NewString = new_string;
  native.NewStringUTF = new_utf;
  native.DeleteLocalRef = delete_ref;
  native.ExceptionCheck = [](JNIEnv*) -> jboolean { return pending; };
  native.GetArrayLength = [](JNIEnv*, jarray) -> jsize {
    ++array_length_calls;
    return array_length;
  };
  native.GetObjectArrayElement = [](JNIEnv*, jobjectArray, jsize) -> jobject {
    ++array_element_calls;
    pending = true;
    return nullptr;
  };
  native.FindClass = [](JNIEnv*, const char*) -> jclass {
    if (fail_init_operation()) return nullptr;
    if (inject_init_failure) ++local_created;
    return reinterpret_cast<jclass>(&java_result);
  };
  native.NewGlobalRef = [](JNIEnv*, jobject value) -> jobject {
    if (fail_init_operation()) return nullptr;
    if (inject_init_failure) ++global_created;
    return value;
  };
  native.DeleteGlobalRef = [](JNIEnv*, jobject value) { if (inject_init_failure && value) ++global_deleted; };
  native.GetMethodID = [](JNIEnv*, jclass, const char*, const char*) -> jmethodID {
    return fail_init_operation() ? nullptr : reinterpret_cast<jmethodID>(&java_result);
  };
  native.GetStaticMethodID = [](JNIEnv*, jclass, const char*, const char*) -> jmethodID {
    return fail_init_operation() ? nullptr : reinterpret_cast<jmethodID>(&java_result);
  };
  native.NewObjectArray = [](JNIEnv*, jsize, jclass, jobject) -> jobjectArray {
    assert(!pending);
    if (fail_new_array) { pending = true; return nullptr; }
    return reinterpret_cast<jobjectArray>(&java_result);
  };
  native.SetObjectArrayElement = [](JNIEnv*, jobjectArray, jsize, jobject) {
    assert(!pending);
    ++array_set_calls;
  };
  native.NewObjectV = [](JNIEnv*, jclass, jmethodID, va_list) -> jobject {
    assert(!pending);
    ++object_calls;
    return reinterpret_cast<jobject>(&java_result);
  };
  native.Throw = [](JNIEnv*, jthrowable) -> jint { pending = true; ++thrown; return JNI_OK; };
  native.ThrowNew = [](JNIEnv*, jclass, const char* msg) -> jint {
    pending = true;
    ++thrown;
    fallback_message = msg;
    return JNI_OK;
  };
  fake_env.functions = &native;

  attached = true;
  { JEnv env(&vm); assert(static_cast<JNIEnv*>(env) == &fake_env); }
  assert(attached && attachments == 0 && detachments == 0);
  attached = false;
  for (int i = 0; i < 100; ++i) {
    {
      JEnv outer(&vm);
      { JEnv inner(&vm); }
      assert(attached && detachments == i);
    }
    assert(!attached && attachments == i + 1 && detachments == i + 1);
  }
  static_assert(!std::is_copy_constructible<JEnv>::value);
  attach_status = JNI_ERR;
  bool failed = false;
  try { JEnv env(&vm); } catch (const std::exception&) { failed = true; }
  assert(failed && !attached && detachments == 100);
  attach_status = JNI_OK;
  env_status = JNI_EVERSION;
  failed = false;
  try { JEnv env(&vm); } catch (const std::exception&) { failed = true; }
  assert(failed && attachments == 100);
  env_status = JNI_OK;

  for (std::u16string text : {u"ascii", u"\u4f60\u597d", u"\U00020bb7\U0001f30f", u""}) {
    const auto before = releases;
    const auto utf8 = static_cast<std::string>(CString(&fake_env, reinterpret_cast<jstring>(&text)));
    JString java(&fake_env, utf8);
    assert(java_result == text && releases == before + 1);
    if (text == u"\U00020bb7\U0001f30f") assert(utf8 == "\xf0\xa0\xae\xb7\xf0\x9f\x8c\x8f");
  }
  std::u16string with_nul = {u'a', 0, u'b'};
  const auto utf8 = static_cast<std::string>(CString(&fake_env, reinterpret_cast<jstring>(&with_nul)));
  assert(utf8.size() == 3 && utf8[1] == 0);
  { JString java(&fake_env, utf8); assert(java_result == with_nul); }
  std::u16string invalid = {u'a', 0xd800, u'x', 0xdc00};
  const auto normalized = static_cast<std::string>(CString(&fake_env, reinterpret_cast<jstring>(&invalid)));
  { JString java(&fake_env, normalized); assert(java_result == u"a\ufffdx\ufffd"); }
  { JString java(&fake_env, std::string("\xffx")); assert(java_result == u"\ufffdx"); }
  assert(utf_calls == 0);
  const auto result = jniCall(&fake_env, []() -> bool { throw std::runtime_error("\xf0\xa0\xae\xb7"); });
  assert(!result && pending && thrown == 1 && java_result == u"\U00020bb7");
  throwJavaException(&fake_env, "must not overwrite pending exception");
  assert(thrown == 1 && makeJavaString(&fake_env, "no allocation while pending") == nullptr);
  pending = false;
  fail_chars = true;
  std::u16string source = u"option";
  const auto failed_string = jniCall(&fake_env, [&]() -> bool {
    CString value(&fake_env, reinterpret_cast<jstring>(&source));
    return true;
  });
  assert(!failed_string && pending && thrown == 1);
  pending = false;
  fail_chars = false;
  fail_new_string = true;
  const auto failed_report = jniCall(&fake_env, []() -> bool {
    throw std::runtime_error("failure whose Java message cannot be allocated");
  });
  assert(!failed_report && pending && thrown == 2 && fallback_message == "Native operation failed");
  pending = false;
  fail_new_string = false;
  const auto unknown_failure = jniCall(&fake_env, []() -> bool { throw 7; });
  assert(!unknown_failure && pending && thrown == 3 && java_result == u"Native operation failed");
  pending = false;
  const auto null_array = jniCall(&fake_env, [&]() -> bool {
    stringArrayToStringVector(&fake_env, nullptr, 512);
    return true;
  });
  assert(!null_array && pending && thrown == 4 && array_length_calls == 0);
  pending = false;
  auto array = reinterpret_cast<jobjectArray>(&java_result);
  array_length = 513;
  const auto oversized_array = jniCall(&fake_env, [&]() -> bool {
    stringArrayToStringVector(&fake_env, array, 512);
    return true;
  });
  assert(!oversized_array && pending && thrown == 5 && array_element_calls == 0);
  pending = false;
  array_length = 1;
  const auto pending_array = jniCall(&fake_env, [&]() -> bool {
    stringArrayToStringVector(&fake_env, array, 512);
    return true;
  });
  assert(!pending_array && pending && thrown == 5 && array_element_calls == 1);
  pending = false;
  int calls = 0;
  jniCall(&fake_env, [&] { ++calls; });
  assert(calls == 1 && !pending);
  GlobalRefSingleton refs(&vm);
  GlobalRef = &refs;
  std::vector<CandidateProto> candidates(1);
  candidates[0].text = "candidate";
  const int prior_objects = object_calls;
  fail_new_array = true;
  assert(rimeCandidateListToJObjectArray(&fake_env, candidates) == nullptr);
  assert(pending && object_calls == prior_objects && array_set_calls == 0);
  pending = false;
  fail_new_array = false;
  pending_new_string = true;
  assert(rimeCandidateListToJObjectArray(&fake_env, candidates) == nullptr);
  assert(pending && object_calls == prior_objects && array_set_calls == 0);
  pending = false;
  pending_new_string = false;
  assert(rimeCandidateListToJObjectArray(&fake_env, candidates) != nullptr);
  assert(!pending && object_calls == prior_objects + 1 && array_set_calls == 1);
  attached = true;
  inject_init_failure = true;
  int tested_failures = 0;
  for (fail_operation = 1; fail_operation < 100; ++fail_operation) {
    pending = false;
    init_operation = global_created = global_deleted = local_created = local_deleted = 0;
    bool failed = false;
    try {
      GlobalRefSingleton candidate(&vm);
      candidate.releaseGlobalRefs(&fake_env);
    } catch (const std::exception&) {
      failed = true;
    }
    assert(global_created == global_deleted && local_created == local_deleted);
    if (!failed) {
      assert(!pending && tested_failures == init_operation);
      break;
    }
    assert(pending && init_operation == fail_operation);
    ++tested_failures;
  }
  assert(tested_failures > 40 && fail_operation < 100);
  inject_init_failure = false;
  std::cout << "PASS: JNI attachment ownership, nested/repeated calls, failure paths, UTF-16/UTF-8, supplementary characters, NUL and malformed input\n";
}
