#!/usr/bin/env python3
"""Create the one machine-local IPv4 config. Requires only Python's standard library."""
import argparse
import ipaddress
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config-dir', type=Path, default=Path(__file__).resolve().parents[1] / 'config')
    parser.add_argument('--set', action='append', default=[], metavar='KEY=IP', help='Supply an address without prompting')
    args = parser.parse_args()
    directory = args.config_dir
    target = directory / 'ips.yaml'
    if target.exists():
        parser.error(f'{target} already exists; edit it directly')
    keys = set()
    for path in directory.rglob('*.yaml'):
        keys.update(re.findall(r'\$\{ip:([^}]+)}', path.read_text()))
    supplied = {}
    for item in args.set:
        key, separator, value = item.partition('=')
        if not separator or key not in keys:
            parser.error(f'Unknown key or invalid KEY=IP: {item}')
        supplied[key] = value
    addresses = {}
    try:
        for key in sorted(keys):
            value = supplied.get(key) or {'loopback': '127.0.0.1', 'any': '0.0.0.0'}.get(key)
            if value is None:
                value = input(f'{key}: ').strip()
            addresses[key] = str(ipaddress.IPv4Address(value))
    except (ValueError, EOFError) as exc:
        parser.error(str(exc))
    # Quoted scalars, valid YAML, without requiring PyYAML.
    with target.open('x') as stream:
        stream.write('# Local machine addresses. Ignored by Git.\n')
        for key, value in addresses.items():
            stream.write(f'{json.dumps(key)}: {json.dumps(value)}\n')
    print(f'Created {target}')


if __name__ == '__main__':
    main()
