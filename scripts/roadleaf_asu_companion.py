"""Render Roadleaf Main ASU speech on this PC over a local ADB connection."""
import argparse
import importlib
import io
import json
import os
import re
import sys
import uuid
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

os.environ.setdefault('ONEDNN_PRIMITIVE_CACHE_CAPACITY', '32')
os.environ.setdefault('DNNL_PRIMITIVE_CACHE_CAPACITY', '32')

import numpy as np
import soundfile as sf
import torch
PREFIX = 'roadleaf-asu:'
NARRATORS = {'narrator-f': 'o-tara', 'narrator-m': 'o-leo'}
PORT = 18765
MODEL = 'qwen3-base-0.6b/asu-four-slot-v1'
READY = MODEL + '/block-v1'
BLOCK_BYTES = 64 * 1024


def voice_sources(voice, roster):
    if not voice.startswith(PREFIX):
        raise ValueError('Voice is outside the ASU bank.')
    code = voice.removeprefix(PREFIX)
    if code in NARRATORS:
        return [NARRATORS[code], 0, 0, 0]
    family, _, recipe = code.partition(':')
    sources = recipe.split('~')
    if len(sources) != 4 or len(set(sources)) != 4 or any(source not in roster for source in sources):
        raise ValueError('Invalid ASU recipe.')
    if family != roster[sources[0]]['family']:
        raise ValueError('ASU recipe does not match its lead.')
    return sources


def catalog(dest, pairing_policy=None):
    roster = profiles.catalog()
    if hasattr(profiles, 'CATALOG'):
        if pairing_policy:
            raise ValueError('Use --lab-root to export an alternate pairing policy.')
        raw = profiles.CATALOG.read_bytes()
        stored = json.loads(raw)
        for voice in stored['voices'] + stored['starters']:
            voice_sources(voice, roster)
        if dest.resolve() != profiles.CATALOG.resolve():
            dest.write_bytes(raw)
        return
    manifest_path = lab.ROOT / 'four-slot-profiles.json'
    if not manifest_path.is_file():
        manifest_path = profiles.OUT / manifest_path.name
    manifest = json.loads(manifest_path.read_text(encoding='utf8'))
    def voice_id(row):
        return PREFIX + roster[row['primary']]['family'] + ':' + '~'.join(row['source_slots'])

    voices = [voice_id(row) for row in manifest['recipes']]
    assert len(set(voices)) == 161
    for voice in voices:
        voice_sources(voice, roster)
    data = {'model': MODEL, 'voices': voices}
    if pairing_policy:
        applied = json.loads(pairing_policy.read_text(encoding='utf8'))
        assert applied['threshold'] == .90 and applied['source_count'] == len(roster)
        blocked = [entry['pair'] for entry in applied['blocked']]
        assert all(a != b and a in roster and b in roster for a, b in blocked)
        forbidden = {frozenset(pair) for pair in blocked}
        from itertools import combinations
        starters = []
        for row in applied['starters']:
            voice = voice_id(row)
            sources = voice_sources(voice, roster)
            assert row['primary'] == sources[0] and all(frozenset(pair) not in forbidden for pair in combinations(sources, 2))
            starters.append(voice)
        assert len(starters) == len(voices) and {row['primary'] for row in applied['starters']} == roster.keys()
        data.update(starters=starters, blockedPairs=blocked)
    lab.save(dest, data)


class Renderer:
    def __init__(self):
        os.environ['HF_HUB_OFFLINE'] = '1'
        from qwen_tts import Qwen3TTSModel

        for weight in lab.MODEL_HASHES:
            lab.verify_weight(weight)
        torch.set_num_threads(min(8, os.cpu_count() or 4))
        self.roster = profiles.catalog()
        bank_file = profiles.source_file(profiles.BASELINE_VECTORS, profiles.BASELINE_VECTORS)
        with np.load(bank_file, allow_pickle=False) as data:
            self.bank = {name: data[name] for name in data.files}
        if set(self.bank) != self.roster.keys():
            raise ValueError('The voice vectors and the catalog disagree.')
        device = 'cuda:0' if torch.cuda.is_available() else 'cpu'
        dtype = torch.bfloat16 if device == 'cpu' or torch.cuda.is_bf16_supported() else torch.float32
        self.model = Qwen3TTSModel.from_pretrained(str(lab.CHECKPOINT), device_map=device, dtype=dtype, attn_implementation='sdpa')

    def speech(self, voice, text):
        from qwen_tts.inference.qwen3_tts_model import VoiceClonePromptItem

        if not isinstance(text, str) or not 1 <= len(text) <= 320 or not any(c.isalnum() for c in text):
            raise ValueError('Send one spoken span of at most 320 characters.')
        sources = voice_sources(voice, self.roster)
        vector = profiles.resolve(sources, self.bank)
        prompt = VoiceClonePromptItem(ref_code=None, ref_spk_embedding=torch.from_numpy(vector),
                                     x_vector_only_mode=True, icl_mode=False)
        clips, rate = self.model.generate_voice_clone(text=text, language='English', voice_clone_prompt=[prompt],
                                                      max_new_tokens=720, do_sample=False)
        clip = clips[0]
        if not len(clip) or not np.isfinite(clip).all() or np.max(np.abs(clip)) < .002:
            raise RuntimeError('The model returned no audible speech.')
        if len(clip) / rate >= 56.5:
            raise RuntimeError('The speech reached the model limit. Use a shorter span.')
        payload = io.BytesIO()
        sf.write(payload, clip, rate, format='WAV', subtype='PCM_16')
        return payload.getvalue()


class Handler(BaseHTTPRequestHandler):
    def permitted(self):
        return self.headers.get('Host') in (f'127.0.0.1:{PORT}', f'localhost:{PORT}') and not self.headers.get('Origin')

    def answer(self, code, content_type, data):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if not self.permitted():
            self.send_error(404)
            return
        if self.path == '/ready':
            self.answer(200, 'text/plain', READY.encode('ascii'))
            return
        match = re.fullmatch(r'/audio/([a-f0-9]{32})/([0-9]{1,7})', self.path)
        if not match or match[1] not in self.server.audio:
            self.send_error(404)
            return
        offset = int(match[2])
        audio = self.server.audio[match[1]]
        if offset >= len(audio) or offset % BLOCK_BYTES:
            self.send_error(416)
            return
        self.answer(200, 'application/octet-stream', audio[offset:offset + BLOCK_BYTES])

    def do_POST(self):
        if self.path != '/speech' or not self.permitted() or self.headers.get_content_type() != 'application/json':
            self.send_error(404)
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 8192:
                raise ValueError('Invalid input size.')
            self.connection.settimeout(10)
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict) or not isinstance(body.get('voice'), str):
                raise ValueError('Invalid input.')
            audio = self.server.renderer.speech(body['voice'], body.get('text'))
        except (ValueError, OSError, TypeError):
            self.send_error(400, 'Invalid voice or text span')
        except Exception:
            self.send_error(500, 'Roadleaf model could not render the span')
        else:
            clip = uuid.uuid4().hex
            self.server.audio[clip] = audio
            if len(self.server.audio) > 4:
                self.server.audio.popitem(last=False)
            self.answer(200, 'application/json', json.dumps(dict(clip=clip, size=len(audio))).encode('ascii'))


def main():
    global profiles, lab
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--lab-root', type=Path, help='Optional extracted four-slot lab pack instead of the bundled bank.')
    cli.add_argument('--checkpoint', type=Path, help='The verified local Qwen Base checkpoint directory.')
    cli.add_argument('--export-catalog', type=Path)
    cli.add_argument('--pairing-policy', type=Path, help='Apply the measured 90 percent pairing limits to the exported Android catalog.')
    args = cli.parse_args()
    if args.lab_root:
        root = args.lab_root.resolve(strict=True)
        if not (root / 'asu-four-slot.py').is_file():
            cli.error('Choose the Meridian-Voice-Lab directory or an extracted four-slot lab pack.')
        sys.path.insert(0, str(root))
        profiles = importlib.import_module('asu-four-slot')
        lab = profiles.lab
    else:
        import roadleaf_asu_bank
        profiles = lab = roadleaf_asu_bank
    if args.checkpoint:
        lab.CHECKPOINT = args.checkpoint.resolve(strict=True)
    if args.export_catalog:
        local_policy = lab.ROOT / 'asu-similarity-audit' / 'pairing-policy.json'
        catalog(args.export_catalog, args.pairing_policy or (local_policy if local_policy.is_file() else None))
        return
    with HTTPServer(('127.0.0.1', PORT), Handler) as server:
        server.audio = OrderedDict()
        server.renderer = Renderer()
        print(f'Roadleaf ASU companion ready on 127.0.0.1:{PORT}.', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
