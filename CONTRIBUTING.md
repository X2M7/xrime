<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Contributing to xrime

Thank you for contributing. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Issues

Search the [xrime issue tracker](https://github.com/X2M7/xrime/issues) before opening a report. For bugs, include the xrime version, device and Android version, reproduction steps, and expected and actual behavior. Check logs and screenshots for personal data before sharing them; do not upload private dictionaries or signing credentials.

## Changes

1. Fork [X2M7/xrime](https://github.com/X2M7/xrime) and create a branch from `develop`.
2. Clone with submodules as described in [source checkout](docs/source-checkout.md). Keep third-party notices and source attribution intact.
3. Make a focused change and run the checks appropriate to it. Describe the checks you ran and any limitations in the pull request.
4. Open a pull request against `X2M7/xrime:develop` and link any related issues.

The [verification record](docs/xrime-verification.md) describes known limitations; historical scripts may target the original Trime package and must be checked before use. The [identity notes](docs/xrime-identity.md) explain those boundaries. Never commit signing keys, passwords, or personal data.
