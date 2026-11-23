#!/usr/bin/env python3
"""Copy the canonical theme into each independent Docker build context."""
import argparse
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true', help='Fail if deployed assets differ from the shared source')
args = parser.parse_args()
for name in ('theme.js', 'theme.css'):
    source = root / 'shared/theme' / name
    for directory in ('app/src/main/resources/META-INF/resources/assets/theme', 'paper-data-extractor/static/theme'):
        target = root / directory / name
        if args.check:
            if not target.exists() or source.read_bytes() != target.read_bytes():
                raise SystemExit(f'Theme asset out of date: {target.relative_to(root)}; run scripts/sync-theme-assets.py')
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
