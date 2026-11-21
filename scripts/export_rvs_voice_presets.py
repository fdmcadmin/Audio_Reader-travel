#!/usr/bin/env python3
"""Export fixed RVS presets that resolve in the lab's production mode."""

import argparse
from hashlib import sha256
import math
from pathlib import Path
import re
import sys


STOCK_MODEL_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("reference_dir", type=Path, help="folder containing roadleaf_voices.py")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    sys.path.insert(0, str(args.reference_dir.resolve()))
    from roadleaf_voices import Library

    lib = Library(args.database.resolve().as_posix())
    try:
        engine = lib.db.execute("SELECT id, archive_sha256 FROM engine").fetchone()
        if (lib.meta.get("standard"), lib.meta.get("package_version"), engine) != (
            "rvs-0.2", "0.4.0", ("supertonic3", STOCK_MODEL_SHA256)
        ):
            raise ValueError("Expected the Step 3 library for Roadleaf's pinned Supertonic model")

        voices = []
        skipped = 0
        for name, in lib.db.execute("SELECT id FROM voice WHERE kind = 'rebuilt' AND status = 'core' ORDER BY id"):
            voices.append((name, lib.resolve(name, verified_only=True)))
        for name, in lib.db.execute("SELECT id FROM blend WHERE tier <> 'solo' AND cross_sex = 0 ORDER BY id"):
            try:
                voices.append((name, lib.resolve_blend(name, verified_only=True)))
            except ValueError:
                skipped += 1
        for name, blend, age, emotion, presence in lib.db.execute(
            "SELECT id, blend_id, age, emotion, presence FROM character ORDER BY id"
        ):
            try:
                resolved = lib.resolve_blend(blend, age=age, emotion=emotion, presence=presence, verified_only=True)
            except ValueError:
                skipped += 1
                continue
            voices.append((name.replace(":", "-"), resolved))

        if not voices or any(not re.fullmatch(r"[FM][A-Za-z0-9-]{1,70}", name) or
                             not math.isfinite(voice.speed) or not 0.5 <= voice.speed <= 2.0
                             for name, voice in voices):
            raise ValueError("Invalid RVS preset metadata")
        if len({name for name, _ in voices}) != len(voices):
            raise ValueError("Duplicate RVS preset IDs")
        args.output.mkdir(parents=True, exist_ok=True)
        table = args.output / "voice.bin"
        lib.write_supertonic_voice_bin(str(table), [voice for _, voice in voices])
        table.with_name("voice.bin.json").unlink()  # The Android index carries only playback metadata.
        revision = sha256(table.read_bytes()).hexdigest()
        lines = [f"rvs-0.2/0.4.0\t{revision}"]
        lines.extend(f"{name}\t{voice.speed:.8f}" for name, voice in voices)
        (args.output / "voices.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"Exported {len(voices)} RVS presets. Production resolver withheld {skipped} presets.")
    finally:
        lib.close()


if __name__ == "__main__":
    main()
