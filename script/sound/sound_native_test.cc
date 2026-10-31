#include <cerrno>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
struct SoundOps {
    virtual ~SoundOps() = default;
    virtual int root(const char* p) { return open(p, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW); }
    virtual int child(int parent, const char* p, int flags) { return openat(parent, p, flags); }
    virtual int inspect(int fd, struct stat* st) { return fstat(fd, st); }
};
class SoundFd {
    int fd_;
public:
    explicit SoundFd(int fd = -1) : fd_(fd) {}
    ~SoundFd() { if (fd_ >= 0) close(fd_); }
    SoundFd(const SoundFd&) = delete;
    SoundFd& operator=(const SoundFd&) = delete;
    SoundFd(SoundFd&& other) noexcept : fd_(other.release()) {}
    int get() const { return fd_; }
    int release() { int fd = fd_; fd_ = -1; return fd; }
};
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <fstream>
#include <iostream>
#include <thread>
#include <atomic>
#include <filesystem>

#include "../../app/src/main/jni/librime_jni/sound_access/sound_open_c.h"
static int root_bridge(void* c, const char* p) { return static_cast<SoundOps*>(c)->root(p); }
static int child_bridge(void* c, int fd, const char* p, int f) { return static_cast<SoundOps*>(c)->child(fd, p, f); }
static int inspect_bridge(void* c, int fd, struct stat* st) { return static_cast<SoundOps*>(c)->inspect(fd, st); }
static SoundFd openSoundC(SoundOps& ops, const std::string& root, const std::string& folder, const std::string& file) {
    if (root.find('\0') != std::string::npos || folder.find('\0') != std::string::npos || file.find('\0') != std::string::npos) return SoundFd();
    struct sound_ops_c access = {&ops, root_bridge, child_bridge, inspect_bridge, nullptr};
    return SoundFd(sound_open_c(&access, root.c_str(), folder.c_str(), file.c_str()));
}
struct FailOps : SoundOps {
    int failAt, calls = 0;
    explicit FailOps(int n) : failAt(n) {}
    bool fail() { if (++calls == failAt) { errno = EIO; return true; } return false; }
    int root(const char* p) override { return fail() ? -1 : SoundOps::root(p); }
    int child(int fd, const char* p, int flags) override { return fail() ? -1 : SoundOps::child(fd, p, flags); }
    int inspect(int fd, struct stat* st) override { return fail() ? -1 : SoundOps::inspect(fd, st); }
};
struct AllocationFailure { int failAt; int calls = 0; };
struct MovingOps : SoundOps {
    std::filesystem::path source, destination;
    bool moved = false;
    int child(int parent, const char* name, int flags) override {
        int fd = SoundOps::child(parent, name, flags);
        if (!moved && fd >= 0) { std::filesystem::rename(source, destination); moved = true; }
        return fd;
    }
};
static char* fail_copy(void* ctx, const char* value) {
    auto* failure = static_cast<AllocationFailure*>(ctx);
    if (++failure->calls == failure->failAt) { errno = ENOMEM; return nullptr; }
    return strdup(value);
}
static int fdCount() {
    DIR* d = opendir("/proc/self/fd"); assert(d);
    int count = 0; while (readdir(d)) ++count;
    closedir(d); return count;
}
static void put(const std::filesystem::path& p, const std::string& data) {
    std::ofstream f(p); assert(f); f << data; f.close(); assert(f);
}
int main(int argc, char** argv) {
    assert(argc == 2);
    const auto base = std::filesystem::path(argv[1]);
    assert(!std::filesystem::exists(base));
    const auto root = base / "soundeffect";
    const auto pack = root / "字母" / "nested";
    std::filesystem::create_directories(pack);
    const auto ordinary = pack / "normal.wav";
    const auto outside = base / "outside.wav";
    put(ordinary, "SAFE"); put(outside, "PRIVATE");
    SoundOps ops;
    put(root / "root.wav", "ROOT");
    assert(openSoundC(ops, root.string(), "", "root.wav").get() >= 0);
    assert(openSoundC(ops, root.string(), ".", "root.wav").get() >= 0);
    assert(openSoundC(ops, root.string(), "", "").get() < 0);
    auto good = [&] { return openSoundC(ops, root.string(), "字母/nested", "normal.wav"); };
    { auto fd = good(); assert(fd.get() >= 0); char bytes[8]{}; assert(read(fd.get(), bytes, 4) == 4); assert(std::string(bytes, 4) == "SAFE"); }
    for (const auto& p : {"../outside.wav", "/etc/passwd", "nested/../normal.wav", "nested//normal.wav", "nested/./normal.wav", "normal.wav/"}) {
        assert(openSoundC(ops, root.string(), "字母", p).get() < 0);
    }
    assert(openSoundC(ops, root.string(), "字母", std::string("normal\0.wav", 11)).get() < 0);
    assert(symlink(outside.c_str(), (pack / "outside-link.wav").c_str()) == 0);
    assert(symlink(base.c_str(), (root / "dir-link").c_str()) == 0);
    assert(openSoundC(ops, root.string(), "字母/nested", "outside-link.wav").get() < 0);
    assert(openSoundC(ops, root.string(), "dir-link", "outside.wav").get() < 0);
    assert(mkfifo((pack / "pipe.wav").c_str(), 0600) == 0);
    assert(openSoundC(ops, root.string(), "字母/nested", "pipe.wav").get() < 0);
    // root, two directories, file, fstat: inject each operation failure.
    for (int failure = 1; failure <= 5; ++failure) {
        int before = fdCount(); FailOps failing(failure);
        { auto fd = openSoundC(failing, root.string(), "字母/nested", "normal.wav"); assert(fd.get() < 0); }
        assert(failing.calls == failure); assert(fdCount() == before);
    }
    for (int failure = 1; failure <= 2; ++failure) {
        int before = fdCount(); AllocationFailure alloc{failure};
        struct sound_ops_c access = {&alloc, sound_default_root, sound_default_child, sound_default_inspect, fail_copy};
        assert(sound_open_c(&access, root.string().c_str(), "字母/nested", "normal.wav") == -1);
        assert(errno == ENOMEM && alloc.calls == failure && fdCount() == before);
    }
    // Race swaps a regular file with a symlink to an outside sentinel. Every opened fd must contain SAFE.
    const auto race = pack / "race.wav";
    put(race, "SAFE");
    std::atomic<bool> stop{false};
    std::thread swapper([&] {
        const auto temporary = pack / "swap.tmp";
        while (!stop.load()) {
            if (symlink(outside.c_str(), temporary.c_str()) == 0) rename(temporary.c_str(), race.c_str());
            put(temporary, "SAFE"); rename(temporary.c_str(), race.c_str());
        }
    });
    int accepted = 0, denied = 0;
    for (int i = 0; i < 2000; ++i) {
        auto fd = openSoundC(ops, root.string(), "字母/nested", "race.wav");
        if (fd.get() < 0) { ++denied; continue; }
        char bytes[8]{}; const auto n = read(fd.get(), bytes, sizeof(bytes));
        assert(n == 4 && std::string(bytes, 4) == "SAFE"); ++accepted;
    }
    stop = true; swapper.join();
    const auto moving = root / "moving" / "nested";
    std::filesystem::create_directories(moving);
    put(moving / "rename.wav", "SAFE");
    MovingOps movingOps; movingOps.source = root / "moving"; movingOps.destination = base / "moved-outside";
    auto renamed = openSoundC(movingOps, root.string(), "moving/nested", "rename.wav");
    assert(renamed.get() >= 0 && movingOps.moved && !std::filesystem::exists(root / "moving"));
    char movedBytes[4]{}; assert(read(renamed.get(), movedBytes, 4) == 4 && std::string(movedBytes, 4) == "SAFE");
    std::cout << "OBSERVED opened directory remains readable after rename outside root; inode anchor is not a current-path guarantee\n";
    std::cout << "PASS regular Unicode/nested, traversal, absolute, embedded NUL, symlinks, FIFO, 5 injected failures, race accepted=" << accepted << " denied=" << denied << '\n';
}
