# Source checkout and pinned bytes

Some native sources are audited by SHA-256 over their exact bytes. On Windows,
Git's system-wide `core.autocrlf=true` can turn LF into CRLF in submodules and
cause the checks to fail. The parent repository's `.gitattributes` does not
apply inside a submodule.

For a new recursive checkout, apply the settings to Git and its child processes:

```sh
git -c core.autocrlf=false -c core.eol=lf clone --recurse-submodules https://github.com/X2M7/xrime.git xrime
```

For a checkout whose submodules have not yet been initialized, run:

```sh
git -c core.autocrlf=false -c core.eol=lf submodule update --init --recursive
python -B script/ci/check_predict_source_pins.py
```

These commands do not repair an already initialized checkout with converted
files. Preserve any local changes first; create a fresh checkout using the
command above if its audited source files do not match. Do not disable or alter
the source hash checks. The tracked prediction TSV is also required to use LF
because the shipped model manifest records its byte hash.

# 本机构建工具位置

此工作区的源码位于 `E:\Public\xrime`，Android SDK、JDK、Gradle 用户缓存和临时目录位于 `D:\xrime-tools`。在 PowerShell 中使用 `powershell -NoProfile -ExecutionPolicy Bypass -File .\script\xrime-gradle.ps1 :app:testDebugUnitTest --no-daemon --console=plain` 运行 Gradle，以便将工具缓存和临时文件留在 D 盘。项目的 Gradle 构建输出仍位于源码目录下的 `build` 目录；脚本不会更改系统级环境变量或删除其他项目在 C 盘的数据。

此工作区的 Windows x64 主机构建使用 Zig 0.15.2 生成 OpenCC 数据，并用 Android NDK 的 LLVM 工具创建静态库。Zig 的 Windows 官方归档 SHA-256 为 `3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c`；构建还校验编译器可执行文件的固定 SHA-256。脚本将 Zig、Python 和 Boost 缓存路径指向 `D:\xrime-tools`。未启用此可选路径时，Windows 构建仍要求原有的原生 MSVC 环境。

Boost 缓存先验证固定归档的 SHA-256，再逐文件比较已解压源码；不匹配时构建失败并保留现有文件。首次提取仅跳过归档中唯一的文档示例软链接，不改变构建源码。未设置 `TRIME_BOOST_TREE` 时，源码解压到项目根目录的 `build/xrime-boost-1.92.0`，避免 Android lint 扫描该依赖；已有的旧源码目录不会被移动或删除。
