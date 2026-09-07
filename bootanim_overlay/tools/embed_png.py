#!/usr/bin/env python3
"""Embed explicit Soong inputs; never scan undeclared files inside the sandbox."""

import argparse
import json
import re
import struct
from pathlib import Path


def natural_key(name):
    return tuple((1, int(part)) if part.isdigit() else (0, part.lower())
                 for part in re.split(r"([0-9]+)", name))


def render(paths):
    groups = {}
    names = {}
    for path in map(Path, paths):
        group_name = path.parent.name
        if not re.fullmatch(r"[0-9]+", group_name) or path.suffix != ".png":
            raise ValueError(f"expected <numeric-group>/<frame>.png: {path}")
        group_id = int(group_name)
        if group_id > 2147483647:
            raise ValueError(f"group id exceeds int32: {path}")
        if group_id in names and names[group_id] != path.parent:
            raise ValueError(f"duplicate numeric group id: {path.parent}")
        names[group_id] = path.parent
        frames = groups.setdefault(group_id, {})
        if path.name in frames:
            raise ValueError(f"duplicate frame: {path}")
        frames[path.name] = path
    if not groups:
        raise ValueError("no PNG inputs")

    lines = ['// Generated from assets; do not edit.\n#include "embedded_assets.h"\n',
             'namespace bootanim_overlay {\nnamespace {\n']
    entries = []
    for group_id, frames in sorted(groups.items()):
        for index, name in enumerate(sorted(frames, key=lambda n: (natural_key(n), n))):
            path = frames[name]
            data = path.read_bytes()
            if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
                raise ValueError(f"invalid PNG header: {path}")
            width, height = struct.unpack(">II", data[16:24])
            if not (0 < width <= 4096 and 0 < height <= 4096):
                raise ValueError(f"PNG dimensions exceed overlay limit: {path}")
            symbol = f"png_g{group_id}_f{index}"
            lines.append(f"alignas(4) const unsigned char {symbol}[] = {{\n")
            for offset in range(0, len(data), 12):
                lines.append("    " + ", ".join(f"0x{b:02x}" for b in data[offset:offset + 12]) + ",\n")
            lines.append("};\n")
            label = json.dumps(f"{path.parent.name}/{path.name}", ensure_ascii=True)
            entries.append(f"    {{{group_id}, {index}, {label}, {symbol}, sizeof({symbol})}},\n")
    lines.append("}  // namespace\nconst EmbeddedPng kEmbeddedPngs[] = {\n")
    lines.extend(entries)
    lines.append("};\nconst size_t kEmbeddedPngCount = sizeof(kEmbeddedPngs) / sizeof(kEmbeddedPngs[0]);\n")
    lines.append("}  // namespace bootanim_overlay\n")
    return "".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("inputs", nargs="+")
    args = parser.parse_args()
    Path(args.out).write_text(render(args.inputs), encoding="utf-8")


if __name__ == "__main__":
    main()
