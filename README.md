<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-License-Identifier: GPL-3.0-or-later
-->

# xrime

xrime is an independent Android input method based on [Trime](https://github.com/osfans/trime), [X2M7/trime](https://github.com/X2M7/trime), and the [Rime](https://rime.im/) input method engine. Its application ID is `com.x2m7.xrime`: it can be installed alongside Trime, but private data is not migrated automatically.

[简体中文](README_sc.md) · [繁體中文](README_tc.md) · [Website](https://x2m7.github.io/xrime/) · [Downloads](https://github.com/X2M7/xrime/releases)

## Download and status

The current release is **[v0.1.0-alpha.1](https://github.com/X2M7/xrime/releases/tag/v0.1.0-alpha.1)**, an alpha prerelease for Android 5.0 and later. Choose the ARM64 APK for a compatible phone or the x86_64 APK for a compatible device or emulator. Back up dictionaries and configuration separately before testing.

Some upstream issues remain open, full lint reports build-plugin upgrade notices, and sound-pack directory scanning can crash with particular filenames and Android runtime conditions. The ARM64 package has not been tested on a physical device. See the [verification record](docs/xrime-verification.md) and [upstream issue checklist](docs/xrime-upstream-issues.md) for the tested scope and outstanding issues.

## Source and builds

Clone recursively so native dependencies are included:

```sh
git -c core.autocrlf=false -c core.eol=lf clone --recurse-submodules https://github.com/X2M7/xrime.git xrime
```

See [source checkout and builds](docs/source-checkout.md) for pinned source bytes and this workspace's Windows build setup. The [identity notes](docs/xrime-identity.md) explain the application ID, signing, and historical tooling limitations. The [privacy notice](PRIVACY.md) describes data handling.

## Contributing and license

Please use the [xrime issue tracker](https://github.com/X2M7/xrime/issues) and see [CONTRIBUTING.md](CONTRIBUTING.md). The project is licensed under [GPL-3.0-or-later](LICENSE). We thank the Rime and Trime communities and the contributors listed in [AUTHORS](AUTHORS); third-party notices are retained with the source.
