#!/usr/bin/env python3
"""Create an importing C++ profile next to the existing machine config; leave it intact."""
import argparse
import json
from pathlib import Path


def profile(source, workers):
    return {
        'imports': [source.name],
        'java_positioning': {'enabled': False},
        'python_detector': {
            'backend': 'cpp', 'base_url': 'http://127.0.0.1:8000', 'server_concurrency': 10,
            'defer_heatmap': False, 'defer_learning_review': False,
            'include_visuals': False, 'heatmap_preview_max_width': 512,
        },
        'integration': {
            'geometry_command_linux': [], 'positioning_command_linux': [],
            'python_parallelism': 10, 'python_request_parallelism': 10, 'python_server_pool_size': 1,
            'analis_surface_autostart': {
                'enabled': True, 'inspect_workers': workers, 'working_dir': 'cppAnalisSurface',
                'startup_timeout_ms': 30000, 'health_path': '/health',
            },
            'analis_surface_command_linux': [
                'cppAnalisSurface/build/cpp_analis_surface', '--host', '127.0.0.1', '--port', '8000',
                '--data-dir', 'data',
            ],
            'performance_log': {'enabled': True, 'directory': 'logs/performance', 'queue_capacity': 8192},
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path('config/config.yaml'))
    parser.add_argument('--output', type=Path, help='Default: cpp-linux.yaml next to the source config')
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    source = args.config.resolve(strict=True)
    output = args.output.resolve() if args.output else source.with_name('cpp-linux.yaml')
    if source.suffix not in {'.yaml', '.yml'}:
        parser.error('--config must be a YAML config')
    if output.parent != source.parent or output == source or output.suffix not in {'.yaml', '.yml'}:
        parser.error('--output must be a different YAML file next to the source config')
    if not 1 <= args.workers <= 64:
        parser.error('--workers must be between 1 and 64')
    # JSON is valid YAML. No PyYAML/venv dependency and no rewriting machine camera/IO settings.
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(profile(source, args.workers), stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(f'Created {output}; existing config preserved: {source}')
    print(f'CONFIG="{output}" bash ./run-supervised.sh -f')


if __name__ == '__main__':
    main()
