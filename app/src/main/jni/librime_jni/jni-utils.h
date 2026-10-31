/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <jni.h>
#include <utf8.h>

#include <iterator>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>

// Rime/OpenCC use UTF-8; JNI's NewStringUTF/GetStringUTFChars use Modified
// UTF-8 instead. Cross this boundary through UTF-16, including supplementary
// Han.
static inline jstring makeJavaString(JNIEnv* env, std::string_view text) {
  if (env->ExceptionCheck()) return nullptr;
  std::u16string result;
  if (utf8::is_valid(text.begin(), text.end())) {
    utf8::utf8to16(text.begin(), text.end(), std::back_inserter(result));
  } else {
    std::string normalized;
    utf8::replace_invalid(text.begin(), text.end(),
                          std::back_inserter(normalized));
    utf8::utf8to16(normalized.begin(), normalized.end(),
                   std::back_inserter(result));
  }
  return env->NewString(reinterpret_cast<const jchar*>(result.data()),
                        result.size());
}

static inline void throwJavaException(JNIEnv* env, const char* msg) {
  if (env->ExceptionCheck()) return;
  jclass c = env->FindClass("java/lang/Exception");
  if (!c) return;
  try {
    auto init = env->GetMethodID(c, "<init>", "(Ljava/lang/String;)V");
    if (init) {
      auto message = makeJavaString(env, msg);
      if (message) {
        auto exception =
            static_cast<jthrowable>(env->NewObject(c, init, message));
        if (exception) {
          env->Throw(exception);
          env->DeleteLocalRef(exception);
        }
        env->DeleteLocalRef(message);
      }
    }
  } catch (...) {
    // Exception reporting must not let a second C++ allocation failure escape
    // across JNI, and must never replace a Java exception already pending.
    if (!env->ExceptionCheck()) env->ThrowNew(c, "Native operation failed");
  }
  env->DeleteLocalRef(c);
}

template <typename F>
static auto jniCall(JNIEnv* env, F&& call) -> decltype(call()) {
  try {
    return call();
  } catch (const std::exception& error) {
    throwJavaException(env, error.what());
    if constexpr (!std::is_void_v<decltype(call())>) return {};
  } catch (...) {
    throwJavaException(env, "Native operation failed");
    if constexpr (!std::is_void_v<decltype(call())>) return {};
  }
}

class CString {
 private:
  std::string value_;

 public:
  CString(JNIEnv* env, jstring str) {
    if (!str) return;
    auto size = env->GetStringLength(str);
    auto chars = env->GetStringChars(str, nullptr);
    if (!chars) throw std::runtime_error("Cannot access Java string");
    try {
      try {
        utf8::utf16to8(chars, chars + size, std::back_inserter(value_));
      } catch (const utf8::invalid_utf16&) {
        // Java strings may contain unpaired surrogates. Normalize only those;
        // never read past the supplied length or discard the valid prefix.
        std::u16string normalized;
        for (jsize i = 0; i < size; ++i) {
          auto c = chars[i];
          if (c >= 0xd800 && c <= 0xdbff && i + 1 < size &&
              chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            normalized += c;
            normalized += chars[++i];
          } else {
            normalized += c >= 0xd800 && c <= 0xdfff ? 0xfffd : c;
          }
        }
        value_.clear();
        utf8::utf16to8(normalized.begin(), normalized.end(),
                       std::back_inserter(value_));
      }
    } catch (...) {
      env->ReleaseStringChars(str, chars);
      throw;
    }
    env->ReleaseStringChars(str, chars);
  }

  operator std::string() const { return value_; }
  operator const char*() const { return value_.c_str(); }
  const char* operator*() const { return value_.c_str(); }
};

template <typename T = jobject>
class JRef {
 private:
  JNIEnv* env_;
  T ref_;

 public:
  JRef(JNIEnv* env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

  ~JRef() { env_->DeleteLocalRef(ref_); }

  operator T() { return ref_; }

  T operator*() { return ref_; }
};

class JString {
 private:
  JNIEnv* env_;
  jstring jstring_;

 public:
  JString(JNIEnv* env, const char* chars)
      : env_(env), jstring_(chars ? makeJavaString(env, chars) : nullptr) {}

  JString(JNIEnv* env, const std::string& string)
      : env_(env), jstring_(makeJavaString(env, string)) {}

  ~JString() { env_->DeleteLocalRef(jstring_); }

  operator jstring() { return jstring_; }

  jstring operator*() { return jstring_; }
};

class JEnv {
 private:
  JNIEnv* env = nullptr;
  JavaVM* jvm_;
  bool attached_ = false;

 public:
  explicit JEnv(JavaVM* jvm) : jvm_(jvm) {
    auto status = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
        throw std::runtime_error("Cannot attach native Rime thread");
      attached_ = true;
    } else if (status != JNI_OK) {
      throw std::runtime_error("Cannot access Java environment");
    }
  }

  ~JEnv() {
    if (attached_) jvm_->DetachCurrentThread();
  }

  JEnv(const JEnv&) = delete;
  JEnv& operator=(const JEnv&) = delete;
  JEnv(JEnv&&) = delete;
  JEnv& operator=(JEnv&&) = delete;

  operator JNIEnv*() { return env; }

  JNIEnv* operator->() { return env; }

  bool ownsAttachment() const { return attached_; }
};

class GlobalRefSingleton {
 public:
  JavaVM* jvm;

  jclass Object = nullptr;

  jclass String = nullptr;

  jclass Integer = nullptr;
  jmethodID IntegerInit = nullptr;

  jclass Boolean = nullptr;
  jmethodID BooleanInit = nullptr;

  jclass Rime = nullptr;
  jmethodID HandleRimeMessage = nullptr;

  jclass CandidateProto = nullptr;
  jmethodID CandidateProtoInit = nullptr;

  jclass CommitProto = nullptr;
  jmethodID CommitProtoInit = nullptr;

  jclass ContextProto = nullptr;
  jmethodID ContextProtoInit = nullptr;

  jclass CompositionProto = nullptr;
  jmethodID CompositionProtoInit = nullptr;

  jclass StatusProto = nullptr;
  jmethodID StatusProtoInit = nullptr;

  jclass RimeResponse = nullptr;
  jmethodID RimeResponseInit = nullptr;

  jclass CandidatesPaged = nullptr;
  jmethodID CandidatesPagedInit = nullptr;
  jmethodID CandidatesPagedSetPrediction = nullptr;

  jclass CandidatesBulk = nullptr;
  jmethodID CandidatesBulkInit = nullptr;
  jmethodID CandidatesBulkSetPrediction = nullptr;

  jclass SchemaListItem = nullptr;
  jmethodID SchemaListItemInit = nullptr;

  jclass KeyEvent = nullptr;
  jmethodID KeyEventInit = nullptr;

  explicit GlobalRefSingleton(JavaVM* jvm_) : jvm(jvm_) {
    JEnv attachment(jvm);
    JNIEnv* env = attachment;
    try {
      Object = globalClass(env, "java/lang/Object");
      String = globalClass(env, "java/lang/String");
      Integer = globalClass(env, "java/lang/Integer");
      IntegerInit = method(env, Integer, "<init>", "(I)V");
      Boolean = globalClass(env, "java/lang/Boolean");
      BooleanInit = method(env, Boolean, "<init>", "(Z)V");
      Rime = globalClass(env, "com/osfans/trime/core/Rime");
      HandleRimeMessage = method(env, Rime, "handleRimeMessage",
                                 "(I[Ljava/lang/Object;)V", true);
      CandidateProto = globalClass(env, "com/osfans/trime/core/CandidateProto");
      CandidateProtoInit =
          method(env, CandidateProto, "<init>",
                 "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
      CommitProto = globalClass(env, "com/osfans/trime/core/CommitProto");
      CommitProtoInit =
          method(env, CommitProto, "<init>", "(Ljava/lang/String;)V");
      ContextProto = globalClass(env, "com/osfans/trime/core/ContextProto");
      ContextProtoInit = method(
          env, ContextProto, "<init>",
          "(Lcom/osfans/trime/core/CompositionProto;Ljava/lang/String;I)V");
      CompositionProto =
          globalClass(env, "com/osfans/trime/core/CompositionProto");
      CompositionProtoInit =
          method(env, CompositionProto, "<init>",
                 "(IIIILjava/lang/String;Ljava/lang/String;)V");
      StatusProto = globalClass(env, "com/osfans/trime/core/StatusProto");
      StatusProtoInit =
          method(env, StatusProto, "<init>",
                 "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");
      RimeResponse = globalClass(env, "com/osfans/trime/core/RimeResponse");
      RimeResponseInit =
          method(env, RimeResponse, "<init>",
                 "(Lcom/osfans/trime/core/CommitProto;Lcom/osfans/trime/core/"
                 "CompositionProto;Lcom/osfans/trime/core/Candidates;Lcom/"
                 "osfans/trime/core/StatusProto;)V");
      CandidatesPaged =
          globalClass(env, "com/osfans/trime/core/Candidates$Paged");
      CandidatesPagedInit =
          method(env, CandidatesPaged, "<init>",
                 "(ZZZI[Lcom/osfans/trime/core/CandidateProto;)V");
      CandidatesPagedSetPrediction =
          method(env, CandidatesPaged, "setPrediction", "(Z)V");
      CandidatesBulk =
          globalClass(env, "com/osfans/trime/core/Candidates$Bulk");
      CandidatesBulkInit =
          method(env, CandidatesBulk, "<init>",
                 "(II[Lcom/osfans/trime/core/CandidateProto;)V");
      CandidatesBulkSetPrediction =
          method(env, CandidatesBulk, "setPrediction", "(Z)V");
      SchemaListItem = globalClass(env, "com/osfans/trime/core/SchemaItem");
      SchemaListItemInit = method(env, SchemaListItem, "<init>",
                                  "(Ljava/lang/String;Ljava/lang/String;)V");
      KeyEvent = globalClass(env, "com/osfans/trime/core/RimeKeyEvent");
      KeyEventInit = method(env, KeyEvent, "<init>", "(IILjava/lang/String;)V");
    } catch (...) {
      releaseGlobalRefs(env);
      throw;
    }
  }

  [[nodiscard]] JEnv AttachEnv() const { return JEnv(jvm); }

  /** Only called when initialization fails, while the JVM remains attached. */
  void releaseGlobalRefs(JNIEnv* env) {
    jclass* refs[] = {&Object,         &String,         &Integer,
                      &Boolean,        &Rime,           &CandidateProto,
                      &CommitProto,    &ContextProto,   &CompositionProto,
                      &StatusProto,    &RimeResponse,   &CandidatesPaged,
                      &CandidatesBulk, &SchemaListItem, &KeyEvent};
    for (auto ref : refs) {
      if (*ref) env->DeleteGlobalRef(*ref);
      *ref = nullptr;
    }
  }

 private:
  static jclass globalClass(JNIEnv* env, const char* name) {
    if (env->ExceptionCheck())
      throw std::runtime_error("Java class lookup failed");
    JRef<jclass> local(env, env->FindClass(name));
    if (!*local || env->ExceptionCheck())
      throw std::runtime_error("Java class lookup failed");
    auto global = reinterpret_cast<jclass>(env->NewGlobalRef(*local));
    if (!global || env->ExceptionCheck()) {
      if (global) env->DeleteGlobalRef(global);
      throw std::runtime_error("Java global reference creation failed");
    }
    return global;
  }

  static jmethodID method(JNIEnv* env, jclass type, const char* name,
                          const char* signature, bool is_static = false) {
    if (env->ExceptionCheck())
      throw std::runtime_error("Java method lookup failed");
    auto result = is_static ? env->GetStaticMethodID(type, name, signature)
                            : env->GetMethodID(type, name, signature);
    if (!result || env->ExceptionCheck())
      throw std::runtime_error("Java method lookup failed");
    return result;
  }
};

extern GlobalRefSingleton* GlobalRef;
