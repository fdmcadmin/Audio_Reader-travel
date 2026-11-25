"""Load the saved Roadleaf ASU identities and verify the pinned renderer."""
import hashlib
import itertools
import json
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1]
BANK = ROOT / 'voice-packs' / 'asu'
CATALOG = ROOT / 'app' / 'src' / 'main' / 'assets' / 'roadleaf-asu' / 'voices.json'
BASELINE_VECTORS = 'candidate-speaker-vectors.npz'
CHECKPOINT = BANK / 'qwen-pilot-model'
REVISION = '5d83992436eae1d760afd27aff78a71d676296fc'
MODEL_HASHES = {
    'model.safetensors': '180b3b10eb1c9f1b4db7806d5475bae3071c0243c299d49926bab1da3b6946f6',
    'speech_tokenizer/model.safetensors': '836b7b357f5ea43e889936a3709af68dfe3751881acefe4ecf0dbd30ba571258',
}
VECTOR_SHA256 = 'aa3810af5202feee1a2ba9529c7b5d317f53515e03eb1d0d96f17d1b6dfba1af'


def catalog():
    data = json.loads((BANK / 'baseline-registry.json').read_text(encoding='utf8'))
    stock = {row['id']: row for row in data['stock']}
    paired = {}
    for row in data['pairs']:
        a, b = row['members']
        if row['id'] != f'pair-{a}--{b}' or row['weights_percent'] != [70, 30] or stock[a]['family'] == stock[b]['family']:
            raise ValueError('The ASU bank contains an invalid directional pair.')
        paired[row['id']] = row | {'family': stock[a]['family']}
    expected = {f'pair-{a}--{b}' for a, b in itertools.permutations(stock, 2) if stock[a]['family'] != stock[b]['family']}
    if len(stock) != 17 or len(paired) != 144 or paired.keys() != expected:
        raise ValueError('The ASU bank must contain all 161 baseline identities.')
    return stock | paired


def source_file(bundled_name, _):
    path = BANK / bundled_name
    if hashlib.sha256(path.read_bytes()).hexdigest() != VECTOR_SHA256:
        raise ValueError('The ASU vectors did not match the saved bank.')
    return path


def resolve(sources, bank):
    if len(sources) != 4 or sources[0] == 0:
        raise ValueError('Supply four slots with a lead voice.')
    weighted = [(torch.from_numpy(bank[source]), weight) for source, weight in zip(sources, (55, 25, 15, 5)) if source != 0]
    if len(weighted) == 1:
        return weighted[0][0].numpy().astype('<f4', copy=False)
    weights = sum(weight for _, weight in weighted)
    components = [(vector, torch.linalg.vector_norm(vector), weight / weights) for vector, weight in weighted]
    direction = sum(vector * (amount / length) for vector, length, amount in components)
    target = sum(length * amount for _, length, amount in components)
    norm = torch.linalg.vector_norm(direction)
    if not norm.isfinite() or norm.item() <= 1e-8:
        raise ValueError('Speaker vector blend is empty or invalid.')
    return (direction * (target / norm)).numpy().astype('<f4', copy=False)


def verify_weight(name):
    digest = hashlib.sha256()
    with (CHECKPOINT / name).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != MODEL_HASHES[name]:
        raise ValueError(f'Weight checksum mismatch for {name}')
