#!/usr/bin/env python3
"""
Retag iOS static libraries and Mach-O objects for Mac Catalyst in-place.
Patches LC_BUILD_VERSION platform: IOS(2) -> MACCATALYST(6).
Handles BSD ar extended name format (#1/N) correctly.

Usage: retag_for_catalyst.py <static_lib_or_object> [...]
"""
import sys
import struct

LC_BUILD_VERSION = 0x32
PLATFORM_IOS = 2
PLATFORM_MACCATALYST = 6
MAGIC_64_LE = b'\xcf\xfa\xed\xfe'
AR_GLOBAL_HEADER = b'!<arch>\n'
AR_MEMBER_MAGIC = b'`\n'


def patch_macho_in_bytearray(data, base):
    """Patch LC_BUILD_VERSION IOS->MACCATALYST in Mach-O data starting at base.
    Returns True if any change was made."""
    if base + 4 > len(data) or data[base:base+4] != MAGIC_64_LE:
        return False
    if base + 32 > len(data):
        return False

    ncmds = struct.unpack_from('<I', data, base + 16)[0]
    off = base + 32  # load commands follow 32-byte mach_header_64
    changed = False

    for _ in range(ncmds):
        if off + 8 > len(data):
            break
        cmd, cmdsize = struct.unpack_from('<II', data, off)
        if cmd == LC_BUILD_VERSION and off + 12 <= len(data):
            platform = struct.unpack_from('<I', data, off + 8)[0]
            if platform == PLATFORM_IOS:
                # In-place patch: same field size, no layout change needed
                struct.pack_into('<I', data, off + 8, PLATFORM_MACCATALYST)
                changed = True
        if cmdsize < 8:
            break
        off += cmdsize

    return changed


def patch_ar_archive(path):
    """Patch all Mach-O members inside a BSD ar static library in-place."""
    with open(path, 'rb') as f:
        raw = f.read()

    if raw[:8] != AR_GLOBAL_HEADER:
        return False  # Not an ar archive

    data = bytearray(raw)
    offset = 8  # Skip 8-byte global header
    changed = False

    while offset + 60 <= len(data):
        # Each ar member has a 60-byte header
        magic = data[offset + 58: offset + 60]
        if magic != AR_MEMBER_MAGIC:
            break  # Corrupt or end of archive

        name_raw = data[offset:offset + 16].decode('ascii', errors='replace').strip()
        size_bytes = data[offset + 48: offset + 58]
        try:
            member_size = int(size_bytes.decode('ascii').strip())
        except ValueError:
            break

        member_start = offset + 60  # member data (including any embedded name)

        # BSD extended names: "#1/N" means actual name is the first N bytes of member data
        # The member_size includes those N name bytes.
        data_start = member_start
        if name_raw.startswith('#1/'):
            try:
                name_len = int(name_raw[3:])
                data_start = member_start + name_len  # Mach-O content starts here
            except ValueError:
                pass

        # Attempt to patch as Mach-O at the true content offset
        if patch_macho_in_bytearray(data, data_start):
            changed = True

        # Advance past member + optional 1-byte alignment padding
        offset = member_start + member_size
        if offset % 2 != 0:
            offset += 1

    if changed:
        with open(path, 'wb') as f:
            f.write(bytes(data))

    return changed


def patch_file(path):
    """Patch a static library or standalone Mach-O object file."""
    with open(path, 'rb') as f:
        header = f.read(8)

    if header == AR_GLOBAL_HEADER:
        patch_ar_archive(path)
    elif header[:4] == MAGIC_64_LE:
        with open(path, 'rb') as f:
            raw = f.read()
        data = bytearray(raw)
        if patch_macho_in_bytearray(data, 0):
            with open(path, 'wb') as f:
                f.write(bytes(data))


if __name__ == '__main__':
    for path in sys.argv[1:]:
        try:
            patch_file(path)
        except Exception as e:
            print(f"Warning: could not patch {path}: {e}", file=sys.stderr)
