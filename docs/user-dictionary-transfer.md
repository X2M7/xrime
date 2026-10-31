<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# User dictionary transfer limits

User dictionary imports and snapshot restores accept up to 32 MiB; custom phrase imports and exports accept up to 256 KiB. Dictionary text exports accept up to 256 MiB. Exports larger than 32 MiB cannot currently be imported through the app's bounded import flow. Older versions did not impose the 256 MiB export limit.

Transfers use at most two app-private staging slots. External document reads and writes happen outside the Rime maintenance operation. If an external provider never returns from a single operation, a slot may remain occupied until that operation ends or the process exits; further transfers fail rather than queue indefinitely.

The native Rime export API writes directly to a file and has no streaming size limit. The app checks the completed file and its format before opening the external destination, but the native generation phase may temporarily use more than 256 MiB and may fail if local storage is full. It does not change the source dictionary.

The storage picker or provider may create, truncate, or partially write the selected external document. A later failure or cancellation cannot guarantee that document is restored to its previous state.
