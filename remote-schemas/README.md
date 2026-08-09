# Shipped schemas

Every JSON here is copied into an app's assets at build time **and** served from this repository at
runtime, so the same file is both what an app ships with and what it can be corrected by.

    commander/   read by vox-commander
    expenses/    read by vox-expenses
    shared/      read by more than one app

The folder is the list: an app copies `<its own>/*.json` and `shared/*.json`, and nothing names
individual files. Dropping a file into a folder ships it; moving it between folders changes which
apps read it.

An app fetches each file from `<repo>/main/remote-schemas/<folder>/<file>` and compares it by hash
with the copy in force. A copy that differs and still parses is written to the app's `filesDir` and
becomes the source of truth until the user resets it — see `RemoteSchema` in `:core:services`.
