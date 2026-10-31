# Native sound opener tests

These are host POSIX tests for the app's native code; use a Linux host compiler and an Android NDK `jni.h`. Keep build output, compiler caches, and the test fixture on a dedicated writable volume. The fixture argument must name a new, nonexistent directory; the tests retain their fixture, including links, for inspection.

```sh
c++ -std=c++17 -O1 -pthread script/sound/sound_native_test.cc -o /path/on/disk/sound_native_test
cc -D_GNU_SOURCE -std=c11 -O1 -I "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/<host>/sysroot/usr/include" script/sound/sound_jni_test.c -o /path/on/disk/sound_jni_test
/path/on/disk/sound_native_test /path/on/disk/new-unique-fixture
/path/on/disk/sound_jni_test
```

Do not compile with `NDEBUG`: assertions are the test checks. The native test exercises relative-path validation, root folder aliases, ordinary and special files, symlink races, injected open/stat/allocation failures, FD counts, and a deterministic directory-rename boundary. The JNI test uses a simulated JNI environment to inject string acquisition failures and verify pending exceptions/cleanup. Android instrumentation separately exercises the real app UID and Java ownership transfer. The directory-rename result is a documented limit: an opened directory remains usable after it is moved elsewhere.
