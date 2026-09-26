#!/usr/bin/env python3
"""Verify every PT_LOAD segment of the given ELF files is 16 KB aligned.

Android 15+ devices may use 16 KB memory pages; shipping a native binary that
assumes 4 KB alignment can crash on them. Mirrors the AGP/NDK guidance.
"""
import struct
import sys


def check(path: str) -> None:
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:4] != b"\x7fELF":
        raise SystemExit(f"{path}: not an ELF file")
    if data[4] != 2:  # ELFCLASS64
        raise SystemExit(f"{path}: only 64-bit ELF is supported")

    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]

    worst = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != 1:  # PT_LOAD
            continue
        p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
        worst = p_align if worst is None or p_align < worst else worst

    if worst is None:
        raise SystemExit(f"{path}: no PT_LOAD segments found")
    if worst < 0x4000:
        raise SystemExit(f"{path}: PT_LOAD alignment {hex(worst)} < 0x4000 (16 KB)")
    print(f"OK  {path}  (PT_LOAD align {hex(worst)})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: check_elf_align.py <elf> [elf ...]")
    for arg in sys.argv[1:]:
        check(arg)
