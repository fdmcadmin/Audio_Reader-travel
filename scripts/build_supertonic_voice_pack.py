#!/usr/bin/env python3
"""Build Roadleaf's Supertonic 3 voice table from the pinned sherpa-onnx bundle.

The Antigravity prototype supplied the blend recipes and norm-preserving math.
This version reads only the stock table and leaves the model graphs in the
upstream archive. It preserves stock IDs and keeps the 120-voice IDs stable
when the optional four-voice tier is added.
"""

import argparse
from array import array
import hashlib
from itertools import combinations
import math
from pathlib import Path
import struct
import sys
import tarfile


STOCK_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
LICENSE_SHA256 = "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f"
STOCK_MEMBER = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/voice.bin"
STOCK_NAMES = tuple(f"{group}{index}" for group in "FM" for index in range(1, 6))
HEADER = struct.Struct("<6q")
TTL_SIZE = 50 * 256
DP_SIZE = 8 * 16


def check_hash(path, expected):
    with path.open("rb") as source:
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise ValueError(f"SHA-256 mismatch for {path}")


def read_stock(bundle):
    with tarfile.open(bundle, "r:bz2") as archive:
        entry = archive.getmember(STOCK_MEMBER)
        if not entry.isfile() or entry.size != HEADER.size + 10 * (TTL_SIZE + DP_SIZE) * 4:
            raise ValueError("Invalid Supertonic 3 stock table")
        with archive.extractfile(entry) as member:
            raw = member.read()
    if HEADER.unpack_from(raw) != (10, 50, 256, 10, 8, 16):
        raise ValueError("Unexpected Supertonic 3 style dimensions")
    values = array("f")
    values.frombytes(raw[HEADER.size:])
    if sys.byteorder != "little":
        values.byteswap()
    if not all(math.isfinite(value) for value in values):
        raise ValueError("The stock table contains non-finite style values")
    ttl = [values[index * TTL_SIZE:(index + 1) * TTL_SIZE] for index in range(10)]
    start = 10 * TTL_SIZE
    dp = [values[start + index * DP_SIZE:start + (index + 1) * DP_SIZE] for index in range(10)]
    return ttl, dp


class StyleBlender:
    def __init__(self, stock):
        self.mean = [sum(column) / len(stock) for column in zip(*stock)]
        self.offsets = [[value - mean for value, mean in zip(voice, self.mean)] for voice in stock]
        self.norms = [math.sqrt(sum(value * value for value in offset)) for offset in self.offsets]

    def blend(self, sources):
        offsets = [0.0] * len(self.mean)
        magnitude = 0.0
        for speaker, weight in sources:
            magnitude += weight * self.norms[speaker]
            for index, value in enumerate(self.offsets[speaker]):
                offsets[index] += weight * value
        norm = math.sqrt(sum(value * value for value in offsets))
        scale = magnitude / norm if norm > 1e-9 else 1.0
        return [mean + scale * value for mean, value in zip(self.mean, offsets)]

    def express(self, speaker, scale):
        return [mean + scale * value for mean, value in zip(self.mean, self.offsets[speaker])]


def blend_recipes(groups, size, weights):
    weighted = len(set(weights)) > 1
    for group in groups:
        for speakers in combinations(group, size):
            for shift in range(len(speakers) if weighted else 1):
                order = speakers[shift:] + speakers[:shift]
                label = "+".join(STOCK_NAMES[index] + (str(round(weight * 100)) if weighted else "")
                                 for index, weight in zip(order, weights))
                yield label, tuple(zip(order, weights))


def build_table(stock_ttl, stock_dp, include_quads, expressive_range):
    names = list(STOCK_NAMES)
    ttl, dp = list(stock_ttl), list(stock_dp)
    timbre, duration = StyleBlender(stock_ttl), StyleBlender(stock_dp)
    groups = (range(5), range(5, 10))

    def append_blends(size, weights):
        for name, sources in blend_recipes(groups, size, weights):
            names.append(name)
            ttl.append(timbre.blend(sources))
            dp.append(duration.blend(sources))

    append_blends(2, (0.5, 0.5))
    append_blends(2, (0.7, 0.3))
    append_blends(3, (1 / 3,) * 3)
    soft = max(0.5, 2 - expressive_range) if expressive_range > 1 else 0.8
    for speaker, name in enumerate(STOCK_NAMES):
        for label, scale in (("soft", soft), ("lively", (1 + expressive_range) / 2), ("dramatic", expressive_range)):
            names.append(f"{name}-{label}")
            ttl.append(timbre.express(speaker, scale))
            dp.append(duration.express(speaker, (1 + scale) / 2))
    if include_quads:
        # Preserve the prototype's relative order within the optional tier.
        for group in groups:
            for speakers in combinations(group, 4):
                for weights in ((0.25,) * 4, (0.4, 0.3, 0.2, 0.1)):
                    for name, sources in blend_recipes((speakers,), 4, weights):
                        names.append(name)
                        ttl.append(timbre.blend(sources))
                        dp.append(duration.blend(sources))

    count = 170 if include_quads else 120
    if len(names) != count or len(set(names)) != count:
        raise ValueError("Invalid voice recipe")
    packed = array("f")
    for voice in (*ttl, *dp):
        packed.extend(voice)
    if not all(math.isfinite(value) for value in packed):
        raise ValueError("Non-finite generated style values")
    if sys.byteorder != "little":
        packed.byteswap()
    return names, HEADER.pack(count, 50, 256, count, 8, 16) + packed.tobytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True, help="pinned upstream stock archive")
    parser.add_argument("--license", type=Path, required=True, help="pinned upstream model license")
    parser.add_argument("--out", type=Path, required=True, help="destination for Roadleaf voice assets")
    parser.add_argument("--quads", action="store_true", help="include all 50 four-voice blends")
    parser.add_argument("--range", type=float, default=1.3, help="expressive style strength (1.0–2.0)")
    args = parser.parse_args()
    if not 1.0 <= args.range <= 2.0:
        parser.error("--range must be between 1.0 and 2.0")
    check_hash(args.bundle, STOCK_SHA256)
    check_hash(args.license, LICENSE_SHA256)
    names, bank = build_table(*read_stock(args.bundle), args.quads, args.range)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "voice.bin").write_bytes(bank)
    (args.out / "voices.txt").write_text("\n".join(names) + "\n", encoding="utf-8", newline="\n")
    (args.out / "MODEL-LICENSE.txt").write_bytes(args.license.read_bytes())
    (args.out / "MODEL-NOTICE.txt").write_text(
        "Supertonic 3 model and stock voices by Supertone Inc.\n"
        "Roadleaf modified voice.bin by adding blended and expressive styles.\n"
        "The model and derived styles are under the accompanying Open RAIL-M license, "
        "including paragraph 5 and Attachment A.\n", encoding="utf-8", newline="\n"
    )
    print(f"Built {len(names)} Supertonic 3 voices ({len(bank):,} bytes)")


if __name__ == "__main__":
    main()
