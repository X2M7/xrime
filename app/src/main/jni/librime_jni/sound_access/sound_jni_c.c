#include "sound_open_c.h"
#include <jni.h>

/* Return UTF-8 bytes in the encoding used by Android JNI for Java paths. */
static int safe_string(JNIEnv* env, jstring s, const char** out) {
    if (!s) return 0;
    jsize n = (*env)->GetStringLength(env, s);
    if ((*env)->ExceptionCheck(env) || n < 0 || n > 32768) return 0;
    const jchar* chars = (*env)->GetStringChars(env, s, NULL);
    if (!chars) return 0;
    int valid = 1;
    for (jsize i = 0; i < n; ++i) if (chars[i] == 0) { valid = 0; break; }
    (*env)->ReleaseStringChars(env, s, chars);
    if (!valid || (*env)->ExceptionCheck(env)) return 0;
    *out = (*env)->GetStringUTFChars(env, s, NULL);
    return *out != NULL && !(*env)->ExceptionCheck(env);
}
JNIEXPORT jint JNICALL Java_com_osfans_trime_data_soundeffect_SoundFileAccess_openNative(
    JNIEnv* env, jclass clazz, jstring root, jstring folder, jstring name) {
    (void)clazz;
    const char *r = NULL, *f = NULL, *n = NULL;
    int fd = -1;
    if ((*env)->ExceptionCheck(env)) return -1;
    if (!safe_string(env, root, &r) || !safe_string(env, folder, &f) || !safe_string(env, name, &n)) goto done;
    struct sound_ops_c ops = {NULL, sound_default_root, sound_default_child, sound_default_inspect, NULL};
    fd = sound_open_c(&ops, r, f, n);
done:
    if (n) (*env)->ReleaseStringUTFChars(env, name, n);
    if (f) (*env)->ReleaseStringUTFChars(env, folder, f);
    if (r) (*env)->ReleaseStringUTFChars(env, root, r);
    return fd;
}
JNIEXPORT void JNICALL Java_com_osfans_trime_data_soundeffect_SoundFileAccess_closeNative(JNIEnv* env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd >= 0) close(fd);
}
