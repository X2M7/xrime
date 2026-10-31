/* Experimental C sound file opener; no C++ runtime dependency. */
#ifndef XRIME_SOUND_OPEN_C_H
#define XRIME_SOUND_OPEN_C_H
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

struct sound_ops_c {
    void *ctx;
    int (*root)(void*, const char*);
    int (*child)(void*, int, const char*, int);
    int (*inspect)(void*, int, struct stat*);
    char* (*copy)(void*, const char*);
};
static int sound_default_root(void* unused, const char* path) {
    (void)unused;
    return open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
}
static int sound_default_child(void* unused, int fd, const char* name, int flags) {
    (void)unused; return openat(fd, name, flags);
}
static int sound_default_inspect(void* unused, int fd, struct stat* st) {
    (void)unused; return fstat(fd, st);
}
static int sound_valid_relative(const char* value) {
    if (!value || !*value || *value == '/') return 0;
    const char* start = value;
    for (;;) {
        const char* end = strchr(start, '/');
        size_t n = end ? (size_t)(end - start) : strlen(start);
        if (!n || (n == 1 && start[0] == '.') || (n == 2 && start[0] == '.' && start[1] == '.')) return 0;
        if (!end) return 1;
        start = end + 1;
    }
}
static int sound_walk(struct sound_ops_c* ops, int *dir, char* path, int last_is_file) {
    char *part = path;
    for (;;) {
        char *end = strchr(part, '/');
        if (end) *end = '\0';
        const int file = last_is_file && !end;
        int flags = O_RDONLY | O_NOFOLLOW | O_CLOEXEC | (file ? O_NONBLOCK : O_DIRECTORY);
        int next = ops->child(ops->ctx, *dir, part, flags);
        if (next < 0) return -1;
        close(*dir);
        *dir = next;
        if (!end) return 0;
        part = end + 1;
    }
}
/* Caller owns successful returned fd. Failures close every native fd and preserve errno. */
static int sound_open_c(struct sound_ops_c* ops, const char* root, const char* folder, const char* file) {
    int fd = -1, result = -1, saved = EINVAL;
    int root_folder = 0;
    char *dirs = NULL, *name = NULL;
    if (!root || !*root || !folder || !file) goto done;
    root_folder = !*folder || strcmp(folder, ".") == 0;
    if ((!root_folder && !sound_valid_relative(folder)) || !sound_valid_relative(file)) goto done;
    if (strlen(root) > 65536 || strlen(folder) > 65536 || strlen(file) > 65536) goto done;
    if (!root_folder && !(dirs = ops->copy ? ops->copy(ops->ctx, folder) : strdup(folder))) { saved = errno; goto done; }
    if (!(name = ops->copy ? ops->copy(ops->ctx, file) : strdup(file))) { saved = errno; goto done; }
    fd = ops->root(ops->ctx, root);
    if (fd < 0) { saved = errno; goto done; }
    if (dirs && sound_walk(ops, &fd, dirs, 0) < 0) { saved = errno; goto done; }
    if (sound_walk(ops, &fd, name, 1) < 0) { saved = errno; goto done; }
    struct stat st;
    if (ops->inspect(ops->ctx, fd, &st) != 0) { saved = errno; goto done; }
    if (!S_ISREG(st.st_mode)) goto done;
    result = fd; fd = -1;
done:
    if (fd >= 0) close(fd);
    free(dirs); free(name);
    if (result < 0) errno = saved;
    return result;
}
#endif
