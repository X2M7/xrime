#include <assert.h>
#include <stdio.h>
#include "../../app/src/main/jni/librime_jni/sound_access/sound_jni_c.c"
struct fake_string { const jchar *chars; jsize len; const char *utf; };
static int fail_at, allocation_calls, held_chars, held_utf, pending, pending_with_utf;
static jsize fake_length(JNIEnv* e, jstring s) { (void)e; return ((struct fake_string*)s)->len; }
static jboolean fake_exception(JNIEnv* e) { (void)e; return pending ? JNI_TRUE : JNI_FALSE; }
static const jchar* fake_chars(JNIEnv* e, jstring s, jboolean* c) {
    (void)e;(void)c; if (++allocation_calls == fail_at) { pending=1; return NULL; }
    held_chars++; return ((struct fake_string*)s)->chars;
}
static void fake_release_chars(JNIEnv* e, jstring s, const jchar* p) { (void)e;(void)s;(void)p; held_chars--; }
static const char* fake_utf(JNIEnv* e, jstring s, jboolean* c) {
    (void)e;(void)c; if (++allocation_calls == fail_at) { pending=1; return NULL; }
    held_utf++; if (pending_with_utf) pending=1; return ((struct fake_string*)s)->utf;
}
static void fake_release_utf(JNIEnv* e, jstring s, const char* p) { (void)e;(void)s;(void)p; held_utf--; }
int main(void) {
    struct JNINativeInterface table = {0};
    table.GetStringLength=fake_length; table.ExceptionCheck=fake_exception;
    table.GetStringChars=fake_chars; table.ReleaseStringChars=fake_release_chars;
    table.GetStringUTFChars=fake_utf; table.ReleaseStringUTFChars=fake_release_utf;
    JNIEnv env = &table;
    static const jchar chars[]={'x'}, bad[]={'x',0,'y'};
    struct fake_string str={chars,1,"x"}, invalid={bad,3,"x"};
    for (int failure=1; failure<=6; ++failure) {
        fail_at=failure; allocation_calls=held_chars=held_utf=pending=0;
        int result=Java_com_osfans_trime_data_soundeffect_SoundFileAccess_openNative(&env, NULL, (jstring)&str, (jstring)&str, (jstring)&str);
        assert(result == -1 && pending && allocation_calls == failure && held_chars == 0 && held_utf == 0);
    }
    fail_at=100; allocation_calls=held_chars=held_utf=pending=0;
    assert(Java_com_osfans_trime_data_soundeffect_SoundFileAccess_openNative(&env, NULL, (jstring)&str, (jstring)&invalid, (jstring)&str) == -1);
    assert(!pending && held_chars == 0 && held_utf == 0);
    pending=1; allocation_calls=held_chars=held_utf=0;
    assert(Java_com_osfans_trime_data_soundeffect_SoundFileAccess_openNative(&env, NULL, (jstring)&str, (jstring)&str, (jstring)&str) == -1);
    assert(pending && allocation_calls == 0 && held_chars == 0 && held_utf == 0);
    pending=0; pending_with_utf=1; allocation_calls=held_chars=held_utf=0;
    assert(Java_com_osfans_trime_data_soundeffect_SoundFileAccess_openNative(&env, NULL, (jstring)&str, (jstring)&str, (jstring)&str) == -1);
    assert(pending && allocation_calls == 2 && held_chars == 0 && held_utf == 0);
    puts("PASS six JNI acquisition failures preserve pending exception and release all acquired handles; embedded NUL rejected");
}
