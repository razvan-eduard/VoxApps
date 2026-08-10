#!/usr/bin/env python3
"""Do the native libraries inside an APK actually satisfy each other?

    ./scripts/vox check pairing <apk> [<apk> ...]

Every shared library records the libraries it needs (DT_NEEDED) and, for each, the symbol *versions*
it was compiled against (.gnu.version_r). The library on the other side records the versions it
exports (.gnu.version_d). An APK is only coherent if, for each needed library that the APK itself
provides, every required version is among the exported ones.

Gradle cannot see this. Two dependencies asking for different versions of the same coordinate is an
ordinary conflict it resolves by itself; what breaks here is one level down — a consumer compiled
against one build being packaged beside a different provider, which resolution never examines
because the provider arrived as a file inside someone else's artifact. The result builds, installs,
passes every unit test, and fails at dlopen the first time that feature is used.

Reads the ELF structures directly rather than shelling out to readelf, so it runs anywhere python3
does — including a CI job that has no NDK.
"""

import glob
import os
import struct
import sys
import zipfile
from collections import defaultdict

# Needed libraries the platform provides. Absent from the APK by design, not a finding.
PLATFORM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libGLESv2.so", "libGLESv3.so", "libEGL.so", "libOpenSLES.so", "libvulkan.so",
    "libnativewindow.so", "libjnigraphics.so", "libmediandk.so", "libcamera2ndk.so",
    "libaaudio.so", "libneuralnetworks.so", "ld-android.so", "libstdc++.so",
}

SHT_GNU_VERDEF, SHT_GNU_VERNEED = 0x6FFFFFFD, 0x6FFFFFFE
DT_NEEDED, DT_SONAME = 1, 14


def _sections(data):
    """(name_offset, type, offset, size, link) per section header, plus the section-name table."""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None, None                      # not a 64-bit ELF; nothing to say about it
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(e_shnum):
        base = e_shoff + i * e_shentsize
        sh_name, sh_type = struct.unpack_from("<II", data, base)
        sh_offset, sh_size = struct.unpack_from("<QQ", data, base + 0x18)
        sh_link, = struct.unpack_from("<I", data, base + 0x28)
        out.append((sh_name, sh_type, sh_offset, sh_size, sh_link))
    return out, out[e_shstrndx]


def _cstr(data, base, offset):
    end = data.index(b"\x00", base + offset)
    return data[base + offset:end].decode("utf-8", "replace")


def read_elf(data):
    """-> (soname, {needed_lib: {required versions}}, {exported versions})"""
    sections, _ = _sections(data)
    if not sections:
        return None, {}, set()

    by_type = defaultdict(list)
    for s in sections:
        by_type[s[1]].append(s)

    soname, needed, requires, exports = None, [], defaultdict(set), set()

    # .dynamic (SHT_DYNAMIC = 6): DT_NEEDED and DT_SONAME are offsets into the linked string table.
    for _, _, off, size, link in by_type.get(6, []):
        strtab = sections[link][2]
        for pos in range(off, off + size, 16):
            tag, val = struct.unpack_from("<QQ", data, pos)
            if tag == 0:
                break
            if tag == DT_NEEDED:
                needed.append(_cstr(data, strtab, val))
            elif tag == DT_SONAME:
                soname = _cstr(data, strtab, val)

    # .gnu.version_r: which versions this library requires, and from which library.
    for _, _, off, _, link in by_type.get(SHT_GNU_VERNEED, []):
        strtab = sections[link][2]
        pos = off
        while True:
            _, cnt, file_off, aux_off, next_off = struct.unpack_from("<HHIII", data, pos)
            lib = _cstr(data, strtab, file_off)
            aux = pos + aux_off
            for _ in range(cnt):
                _, _, _, name_off, aux_next = struct.unpack_from("<IHHII", data, aux)
                requires[lib].add(_cstr(data, strtab, name_off))
                if not aux_next:
                    break
                aux += aux_next
            if not next_off:
                break
            pos += next_off

    # .gnu.version_d: which versions this library exports. The first entry is its own soname.
    for _, _, off, _, link in by_type.get(SHT_GNU_VERDEF, []):
        strtab = sections[link][2]
        pos = off
        while True:
            _, _, _, cnt, _, aux_off, next_off = struct.unpack_from("<HHHHIII", data, pos)
            aux = pos + aux_off
            for _ in range(cnt):
                name_off, aux_next = struct.unpack_from("<II", data, aux)
                exports.add(_cstr(data, strtab, name_off))
                if not aux_next:
                    break
                aux += aux_next
            if not next_off:
                break
            pos += next_off

    return soname, {lib: requires.get(lib, set()) for lib in needed}, exports


def read_symbols(data):
    """-> (defined symbols, strongly-undefined symbols) from .dynsym.

    Weak undefined symbols are deliberately excluded: the linker is allowed to leave them null, and
    code that references them tests for null before calling. Counting them as unresolved reports
    tcmalloc hooks, gcov stubs and newer-API libc entry points as defects — all of which are how the
    library is meant to be built.
    """
    sections, _ = _sections(data)
    if not sections:
        return set(), set()
    defined, undefined = set(), set()
    for _, sh_type, off, size, link in sections:
        if sh_type != 11:                      # SHT_DYNSYM
            continue
        strtab = sections[link][2]
        for pos in range(off, off + size, 24):
            st_name, st_info, _, st_shndx = struct.unpack_from("<IBBH", data, pos)
            if not st_name:
                continue
            name = _cstr(data, strtab, st_name)
            binding = st_info >> 4
            if binding not in (1, 2):          # GLOBAL, WEAK — the ones that link dynamically
                continue
            if st_shndx != 0:
                defined.add(name)
            elif binding == 1:                 # strongly undefined: must be resolved by something
                undefined.add(name)
    return defined, undefined


def platform_symbols(min_api=29):
    """Everything Android itself exports, read from the NDK's stub libraries.

    Without this, an undefined symbol cannot be told apart from one the platform provides, and the
    check has to stay silent about libraries that carry no version records. Returns an empty set
    when no NDK is present, which downgrades the check rather than inventing findings.
    """
    roots = []
    for base in (os.environ.get("ANDROID_NDK_HOME"), os.environ.get("ANDROID_NDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk/ndk"), "/usr/local/lib/android/sdk/ndk"):
        if base and os.path.isdir(base):
            roots.append(base)
    for root in roots:
        pattern = os.path.join(root, "**", "sysroot", "usr", "lib", "aarch64-linux-android",
                               str(min_api), "*.so")
        stubs = glob.glob(pattern, recursive=True)
        if stubs:
            out = set()
            for stub in stubs:
                with open(stub, "rb") as fh:
                    defined, _ = read_symbols(fh.read())
                out |= defined
            return out
    return set()


def check(apk_path, platform=None, extra_dirs=()):
    """extra_dirs holds libraries that are not in the APK but will be present at run time.

    A `full`-mode build excludes its DLC libraries and downloads them at first launch, so the APK on
    its own shows a bridge with no provider. Checking it that way would be weaker than checking a
    `minimal` build, which is backwards: `full` is the mode where the runtime arrives separately and
    can be stale against an APK built later.
    """
    blobs = {}
    with zipfile.ZipFile(apk_path) as apk:
        entries = [n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")]
        for name in entries:
            blobs[name] = apk.read(name)
    for d in extra_dirs:
        for name in sorted(glob.glob(os.path.join(d, "**", "*.so"), recursive=True)):
            blobs.setdefault(name, open(name, "rb").read())

    if True:
        packaged, symbols = {}, {}
        for name, data in blobs.items():
            soname, needs, exports = read_elf(data)
            short = name.rsplit("/", 1)[-1]
            key = soname or short
            packaged[key] = (short, needs, exports)
            symbols[key] = read_symbols(data)

    problems, unresolved = [], []
    for _, (short, needs, _) in sorted(packaged.items()):
        for lib, wanted in sorted(needs.items()):
            if lib in PLATFORM:
                continue
            if lib not in packaged:
                unresolved.append((short, lib))
                continue
            missing = wanted - packaged[lib][2]
            if missing:
                problems.append((short, lib, sorted(wanted), sorted(packaged[lib][2]), sorted(missing)))

    # Symbol-level, for the libraries that carry no version records — an OpenCV built against a
    # different OpenCV resolves nothing here, and versions alone would never show it.
    unsatisfied = []
    if platform:
        for key, (short, needs, _) in sorted(packaged.items()):
            _, undefined = symbols[key]
            available = set(platform)
            for lib in needs:
                if lib in packaged:
                    available |= symbols[lib][0]
            missing = sorted(undefined - available)
            if missing:
                unsatisfied.append((short, missing))

    name = apk_path.rsplit("/", 1)[-1]
    mode = "symbols + versions" if platform else "versions only (no NDK sysroot found)"
    extra = f" (+{len(blobs) - len(entries)} staged)" if len(blobs) > len(entries) else ""
    print(f"  {name}: {len(entries)} native libraries{extra} — {mode}")
    for consumer, lib in unresolved:
        print(f"    ? {consumer} needs {lib}, which is neither packaged nor a platform library")
    for consumer, lib, wanted, provided, missing in problems:
        print(f"    ✗ {consumer} requires {', '.join(missing)} from {lib}")
        print(f"        it asks for : {', '.join(wanted) or '(unversioned)'}")
        print(f"        {lib} exports: {', '.join(provided) or '(unversioned)'}")
    for consumer, missing in unsatisfied:
        shown = ", ".join(missing[:4]) + (f" (+{len(missing) - 4} more)" if len(missing) > 4 else "")
        print(f"    ✗ {consumer}: {len(missing)} symbol(s) nothing in the APK or the platform defines")
        print(f"        {shown}")
    if not problems and not unsatisfied:
        print("    ✓ every packaged library satisfies what its dependants were built against")
    return len(problems) + len(unsatisfied)


if __name__ == "__main__":
    args = sys.argv[1:]
    extra_dirs = []
    while "--with-libs" in args:
        i = args.index("--with-libs")
        extra_dirs.append(args[i + 1])
        del args[i:i + 2]
    apks = [a for a in args if os.path.isfile(a)]
    if not apks:
        print("usage: check_native_pairing.py <apk> [<apk> ...] [--with-libs <dir>]", file=sys.stderr)
        sys.exit(2)
    plat = platform_symbols()
    sys.exit(1 if sum(check(a, plat, extra_dirs) for a in apks) else 0)
