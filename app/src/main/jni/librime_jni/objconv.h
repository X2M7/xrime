/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <rime_api.h>

#include <limits>

#include "helper-types.h"
#include "jni-utils.h"

inline jobject rimeSchemaListItemToJObject(JNIEnv* env,
                                           const SchemaItem& item) {
  if (env->ExceptionCheck()) return nullptr;
  JString schema_id(env, item.schemaId);
  if (env->ExceptionCheck()) return nullptr;
  JString name(env, item.name);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->SchemaListItem,
                        GlobalRef->SchemaListItemInit, *schema_id, *name);
}

inline jobjectArray rimeSchemaListToJObjectArray(
    JNIEnv* env, const std::vector<SchemaItem>& list) {
  if (env->ExceptionCheck()) return nullptr;
  if (list.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
    throw std::length_error("Schema list exceeds JNI array limit");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(list.size()),
                                           GlobalRef->SchemaListItem, nullptr);
  if (!array || env->ExceptionCheck()) return nullptr;
  int i = 0;
  for (const auto& item : list) {
    auto jItem = JRef(env, rimeSchemaListItemToJObject(env, item));
    if (!*jItem || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(array, i++, jItem);
    if (env->ExceptionCheck()) return nullptr;
  }
  return array;
}

inline std::vector<std::string> stringArrayToStringVector(
    JNIEnv* env, jobjectArray array,
    jsize maximum = std::numeric_limits<jsize>::max()) {
  if (!array || env->ExceptionCheck())
    throw std::invalid_argument("Invalid string array");
  auto length = env->GetArrayLength(array);
  if (env->ExceptionCheck() || length < 0 || length > maximum)
    throw std::invalid_argument("Invalid string array length");
  std::vector<std::string> result;
  result.reserve(length);
  for (int i = 0; i < length; ++i) {
    // JNI local reference limits are especially small on Android 5. Keep
    // one element alive at a time, even when converting several large arrays.
    JRef<jstring> element(
        env, reinterpret_cast<jstring>(env->GetObjectArrayElement(array, i)));
    if (env->ExceptionCheck() || !*element)
      throw std::invalid_argument("Invalid string array element");
    CString cstr(env, *element);
    result.emplace_back(cstr);
  }
  return std::move(result);
}

inline jobjectArray stringVectorToJStringArray(
    JNIEnv* env, const std::vector<std::string>& strings) {
  if (env->ExceptionCheck()) return nullptr;
  if (strings.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
    throw std::length_error("String list exceeds JNI array limit");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(strings.size()),
                                           GlobalRef->String, nullptr);
  if (!array || env->ExceptionCheck()) return nullptr;
  int i = 0;
  for (const auto& s : strings) {
    JString value(env, s);
    if (env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(array, i++, value);
    if (env->ExceptionCheck()) return nullptr;
  }
  return array;
}

inline jobject rimeCommitToJObject(JNIEnv* env, const CommitProto& commit) {
  if (env->ExceptionCheck()) return nullptr;
  JRef<jstring> text(env,
                     commit.text ? makeJavaString(env, *commit.text) : nullptr);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->CommitProto, GlobalRef->CommitProtoInit,
                        *text);
}

inline jobject rimeCandidateToJObject(JNIEnv* env,
                                      const CandidateProto& candidate) {
  if (env->ExceptionCheck()) return nullptr;
  JString text(env, candidate.text);
  if (env->ExceptionCheck()) return nullptr;
  JString comment(env, candidate.comment);
  if (env->ExceptionCheck()) return nullptr;
  JString label(env, candidate.label);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->CandidateProto,
                        GlobalRef->CandidateProtoInit, *text, *comment, *label);
}

inline jobjectArray rimeCandidateListToJObjectArray(
    JNIEnv* env, const std::vector<CandidateProto>& list) {
  if (env->ExceptionCheck()) return nullptr;
  if (list.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
    throw std::length_error("Candidate list exceeds JNI array limit");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(list.size()),
                                           GlobalRef->CandidateProto, nullptr);
  if (!array || env->ExceptionCheck()) return nullptr;
  int i = 0;
  for (const auto& candidate : list) {
    auto obj = JRef(env, rimeCandidateToJObject(env, candidate));
    if (!*obj || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(array, i++, obj);
    if (env->ExceptionCheck()) return nullptr;
  }
  return array;
}

inline jobject rimeCompositionToJObject(JNIEnv* env,
                                        const CompositionProto& composition) {
  if (env->ExceptionCheck()) return nullptr;
  JRef<jstring> preedit(env, composition.preedit
                                 ? makeJavaString(env, *composition.preedit)
                                 : nullptr);
  if (env->ExceptionCheck()) return nullptr;
  JRef<jstring> preview(
      env, composition.commitTextPreview
               ? makeJavaString(env, *composition.commitTextPreview)
               : nullptr);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->CompositionProto,
                        GlobalRef->CompositionProtoInit, composition.length,
                        composition.cursorPos, composition.selStart,
                        composition.selEnd, *preedit, *preview);
}

inline jobject rimeCandidatesPagedToJObject(JNIEnv* env, const MenuProto& menu,
                                            bool is_horizontal_layout) {
  if (env->ExceptionCheck()) return nullptr;
  if (menu.candidates.size() >
      static_cast<size_t>(std::numeric_limits<jsize>::max()))
    throw std::length_error("Candidate page exceeds JNI array limit");
  int numCandidates = static_cast<int>(menu.candidates.size());
  auto jCandidates = JRef<jobjectArray>(
      env,
      env->NewObjectArray(numCandidates, GlobalRef->CandidateProto, nullptr));
  if (!*jCandidates || env->ExceptionCheck()) return nullptr;
  for (int i = 0; i < numCandidates; ++i) {
    auto jCandidate =
        JRef(env, rimeCandidateToJObject(env, menu.candidates[i]));
    if (!*jCandidate || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(jCandidates, i, jCandidate);
    if (env->ExceptionCheck()) return nullptr;
  }
  return env->NewObject(GlobalRef->CandidatesPaged,
                        GlobalRef->CandidatesPagedInit, menu.pageNumber != 0,
                        !menu.isLastPage, is_horizontal_layout,
                        menu.highlightedCandidateIndex, *jCandidates);
}

inline jobject rimeContextToJObject(JNIEnv* env, const ContextProto& context) {
  if (env->ExceptionCheck()) return nullptr;
  JRef composition(env, rimeCompositionToJObject(env, context.composition));
  if (!*composition || env->ExceptionCheck()) return nullptr;
  JString input(env, context.input);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->ContextProto, GlobalRef->ContextProtoInit,
                        *composition, *input, context.caretPos);
}

inline jobject rimeStatusToJObject(JNIEnv* env, const StatusProto& status) {
  if (env->ExceptionCheck()) return nullptr;
  JString schema_id(env, status.schemaId);
  if (env->ExceptionCheck()) return nullptr;
  JString schema_name(env, status.schemaName);
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->StatusProto, GlobalRef->StatusProtoInit,
                        *schema_id, *schema_name, status.isDisabled,
                        status.isComposing, status.isAsciiMode,
                        status.isFullShape, status.isSimplified,
                        status.isTraditional, status.isAsciiPunct);
}

inline jobject rimeResponseToJObject(JNIEnv* env, jobject commit,
                                     jobject composition, jobject candidates,
                                     jobject status) {
  if (env->ExceptionCheck()) return nullptr;
  return env->NewObject(GlobalRef->RimeResponse, GlobalRef->RimeResponseInit,
                        commit, composition, candidates, status);
}
