#!/usr/bin/env python3
"""Export/apply factory host settings, never machine identities or secrets."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

SERVICES = ('defect-detector.service', 'tailscaled.service', 'ssh.service', 'irqbalance.service')
FIXED = (
    '/etc/systemd/system/defect-detector.service',
    '/usr/local/bin/defect-detector-kiosk',
    '/usr/share/xsessions/defect-detector.desktop',
)
RT_KEYS = ('isolcpus', 'nohz_full', 'rcu_nocbs', 'irqaffinity', 'kthread_cpus')


def run(*args, check=True):
    return subprocess.run(args, check=check, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE).stdout.strip()


def topology():
    rows = []
    for cpu in sorted(Path('/sys/devices/system/cpu').glob('cpu[0-9]*'),
                      key=lambda p: int(p.name[3:])):
        rows.append([int(cpu.name[3:]),
                     int((cpu / 'topology/core_id').read_text()),
                     int((cpu / 'topology/physical_package_id').read_text())])
    return rows


def allowed(path, users):
    return path in FIXED or path in [f'/var/lib/AccountsService/users/{u}' for u in users] or bool(
        re.fullmatch(r'/etc/systemd/system/(defect-detector|tailscaled|ssh|irqbalance)\.service\.d/[^/]+\.conf', path))


def require_linux_root():
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise ValueError('Run on Linux with sudo.')


def export_profile(args):
    require_linux_root()
    users = [args.admin, 'kiosk']
    for user in users:
        if not re.fullmatch(r'[a-z_][a-z0-9_-]*', user):
            raise ValueError('Invalid Linux username.')
    paths = list(FIXED) + [f'/var/lib/AccountsService/users/{u}' for u in users]
    for service in SERVICES:
        paths.extend(str(p) for p in Path(f'/etc/systemd/system/{service}.d').glob('*.conf'))
    files = {}
    for name in paths:
        path = Path(name)
        if path.is_file():
            data = path.read_bytes()
            files[name] = {'sha256': hashlib.sha256(data).hexdigest(),
                           'mode': (0o755 if name == '/usr/local/bin/defect-detector-kiosk' else
                                    0o600 if name.startswith('/var/lib/AccountsService/') else 0o644),
                           'data': data}
    missing = [p for p in FIXED if p not in files]
    if missing:
        raise ValueError('Missing factory settings: ' + ', '.join(missing))
    cmdline = Path('/proc/cmdline').read_text().split()
    rt = [token for token in cmdline if token.split('=')[0] in RT_KEYS]
    if not any(token.startswith('isolcpus=') for token in rt):
        raise ValueError('Source machine has no active CPU isolation.')
    governors = {p.read_text().strip() for p in Path('/sys/devices/system/cpu').glob('cpu*/cpufreq/scaling_governor')}
    if len(governors) > 1:
        raise ValueError('Mixed CPU governors: normalize them before export.')
    gdm = Path('/etc/gdm3/custom.conf').read_text()
    enabled = {s: run('systemctl', 'is-enabled', s, check=False) for s in SERVICES}
    manifest = {
        'version': 1, 'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'admin': args.admin, 'app_path': args.app_path, 'topology': topology(),
        'os_release': Path('/etc/os-release').read_text(), 'kernel': os.uname().release,
        'rt_parameters': rt, 'governor': next(iter(governors), None),
        'gdm_autologin_kiosk': bool(re.search(r'^\s*AutomaticLogin\s*=\s*kiosk\s*$', gdm, re.M))
            and bool(re.search(r'^\s*AutomaticLoginEnable\s*=\s*true\s*$', gdm, re.M | re.I)),
        'enabled': enabled,
        'files': {name: {k: v for k, v in info.items() if k != 'data'} for name, info in files.items()},
    }
    with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
        archive.writestr('packages.txt', run('dpkg-query', '-W', '-f=${binary:Package}\t${Version}\n'))
        for name, info in files.items():
            archive.writestr('files' + name, info['data'])
    # The bundle contains settings, not credentials; let the sudo caller copy it.
    if os.environ.get('SUDO_UID'):
        os.chown(args.output, int(os.environ['SUDO_UID']), int(os.environ['SUDO_GID']))
    print(f'Exported {len(files)} files to {args.output}. Kernel: {manifest["kernel"]}')


def load_profile(filename):
    with zipfile.ZipFile(filename) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        if manifest.get('version') != 1:
            raise ValueError('Unsupported profile version.')
        users = [manifest['admin'], 'kiosk']
        if any(not re.fullmatch(r'[a-z_][a-z0-9_-]*', user) for user in users):
            raise ValueError('Invalid username in profile.')
        files = {}
        for name, info in manifest['files'].items():
            if not allowed(name, users):
                raise ValueError('Forbidden target path: ' + name)
            if info['mode'] not in (0o600, 0o644, 0o755):
                raise ValueError('Invalid file permissions: ' + name)
            data = archive.read('files' + name)
            if hashlib.sha256(data).hexdigest() != info['sha256']:
                raise ValueError('Checksum mismatch: ' + name)
            files[name] = data
    return manifest, files


def edit_daemon(text, values):
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == '[daemon]'), None)
    if start is None:
        lines.extend(['', '[daemon]'])
        start = len(lines) - 1
    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip().startswith('[')), len(lines))
    section = [line for line in lines[start + 1:end]
               if not any(re.match(r'\s*' + re.escape(key) + r'\s*=', line) for key in values)]
    section.extend(f'{key}={value}' for key, value in values.items())
    return '\n'.join(lines[:start + 1] + section + lines[end:]) + '\n'


def apply_profile(args):
    require_linux_root()
    manifest, files = load_profile(args.bundle)
    if 'ID=ubuntu' not in Path('/etc/os-release').read_text() or 'VERSION_ID="24.04"' not in Path('/etc/os-release').read_text():
        raise ValueError('This profile installer requires Ubuntu 24.04.')
    if topology() != manifest['topology']:
        raise ValueError('CPU topology differs from the source. Do not reuse this isolation profile.')
    kernel_config = Path('/boot/config-' + os.uname().release)
    if not kernel_config.exists() or 'CONFIG_PREEMPT_RT=y' not in kernel_config.read_text():
        raise ValueError('Install and boot an RT kernel first. See deploy/linux/README.md.')
    rt_config = kernel_config.read_text()
    for key in ('CONFIG_NO_HZ_FULL=y', 'CONFIG_RCU_NOCB_CPU=y'):
        if key not in rt_config:
            raise ValueError('Kernel lacks ' + key)
    run('id', manifest['admin'])
    app = Path(manifest['app_path'])
    for path in (app / 'run-rt.sh', app / 'config/config.yaml',
                 app / 'orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar',
                 Path('/opt/defect-detector-ui/node_modules/electron/dist/chrome-sandbox'),
                 Path('/opt/defect-detector-ui/dist/index.html')):
        if not path.is_file():
            raise ValueError('Prepare the application/UI first; missing: ' + str(path))
    for command in ('java', 'update-grub', 'systemctl'):
        if not shutil.which(command):
            raise ValueError('Required command is missing: ' + command)
    for token in manifest['rt_parameters']:
        if not re.fullmatch(r'(isolcpus|nohz_full|rcu_nocbs|irqaffinity|kthread_cpus)=[a-z0-9,\-]+', token):
            raise ValueError('Invalid RT parameter: ' + token)
    # Existing isolation must be empty or identical to avoid conflicting GRUB assignments.
    params = ' '.join(manifest['rt_parameters'])
    for config in [Path('/etc/default/grub'), *Path('/etc/default/grub.d').glob('*.cfg')]:
        for line in config.read_text().splitlines():
            if line.lstrip().startswith('#'):
                continue
            for token in re.findall(r'(?:isolcpus|nohz_full|rcu_nocbs|irqaffinity|kthread_cpus)=[a-z0-9,\-]+', line):
                if token not in manifest['rt_parameters']:
                    raise ValueError('Conflicting isolation in ' + str(config) + ': ' + token)
    print('Profile validated. CPU layout, RT kernel and application paths match.')
    sandbox = Path('/opt/defect-detector-ui/node_modules/electron/dist/chrome-sandbox')
    if any(p.stat().st_uid != 0 or p.stat().st_mode & 0o022 for p in (sandbox, *sandbox.parents)):
        raise ValueError('UI sandbox and its parent directories must be root-owned and not group/world writable.')
    if run('id', 'kiosk', check=False) and set(run('id', '-nG', 'kiosk').split()) & {'sudo', 'admin'}:
        raise ValueError('kiosk must not have administrator group membership.')
    if manifest['governor'] and manifest['governor'] not in ('performance', 'powersave', 'schedutil', 'ondemand', 'conservative', 'userspace'):
        raise ValueError('Invalid governor.')
    if manifest['governor']:
        for available in Path('/sys/devices/system/cpu').glob('cpu*/cpufreq/scaling_available_governors'):
            if manifest['governor'] not in available.read_text().split():
                raise ValueError('CPU governor not supported: ' + manifest['governor'])
    if args.check:
        return
    if run('id', 'kiosk', check=False) == '':
        run('useradd', '--create-home', '--shell', '/bin/bash', 'kiosk')
    if set(run('id', '-nG', 'kiosk').split()) & {'sudo', 'admin'}:
        raise ValueError('kiosk must not have administrator group membership.')
    backup = Path('/var/backups/defect-detector') / datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup.mkdir(parents=True, mode=0o700)
    before = {'enabled': {s: run('systemctl', 'is-enabled', s, check=False) for s in SERVICES}, 'new_files': []}

    def write(name, data, mode=0o644):
        target = Path(name)
        if target.is_symlink() or any(parent.is_symlink() for parent in target.parents):
            raise ValueError('Refusing symlink target: ' + name)
        if target.exists():
            saved = backup / name.lstrip('/')
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, saved)
        else:
            before['new_files'].append(name)
        (backup / 'before.json').write_text(json.dumps(before, indent=2))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(mode)
        os.chown(target, 0, 0)

    for name, data in files.items():
        write(name, data, manifest['files'][name]['mode'])
    gdm_path = '/etc/gdm3/custom.conf'
    write(gdm_path, edit_daemon(Path(gdm_path).read_text(), {
        'AutomaticLoginEnable': 'True' if manifest['gdm_autologin_kiosk'] else 'False',
        'AutomaticLogin': 'kiosk',
    }).encode())
    write('/etc/default/grub.d/90-defect-detector-isolation.cfg',
          ('# Generated factory CPU profile\nGRUB_CMDLINE_LINUX_DEFAULT="${GRUB_CMDLINE_LINUX_DEFAULT} ' + params + '"\n').encode())
    if manifest['governor']:
        governor = manifest['governor']
        if governor not in ('performance', 'powersave', 'schedutil', 'ondemand', 'conservative', 'userspace'):
            raise ValueError('Invalid governor.')
        script = '#!/bin/sh\nset -eu\nfor f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do\n    [ -f "$f" ] || continue\n    echo ' + governor + ' > "$f"\ndone\n'
        write('/usr/local/bin/defect-detector-governor', script.encode(), 0o755)
        write('/etc/systemd/system/defect-detector-governor.service', b'[Unit]\nDescription=Factory CPU governor\nBefore=defect-detector.service\n[Service]\nType=oneshot\nExecStart=/usr/local/bin/defect-detector-governor\nRemainAfterExit=yes\n[Install]\nWantedBy=multi-user.target\n')
    sandbox.chmod(0o4755)
    run('systemd-analyze', 'verify', '/etc/systemd/system/defect-detector.service')
    run('systemctl', 'daemon-reload')
    for service, state in manifest['enabled'].items():
        if service not in SERVICES:
            raise ValueError('Unexpected service: ' + service)
        exists = run('systemctl', 'show', service, '-p', 'LoadState', '--value', check=False) != 'not-found'
        if not exists:
            print('Not installed, skipped: ' + service)
        elif state in ('enabled', 'disabled', 'masked'):
            run('systemctl', 'unmask', service)
            run('systemctl', 'enable' if state == 'enabled' else 'disable', service)
            if state == 'masked':
                run('systemctl', 'mask', service)
    if manifest['governor']:
        run('systemctl', 'enable', 'defect-detector-governor.service')
    run('update-grub')
    print('Installed. Backup: ' + str(backup))
    print('No services restarted. Reboot locally when ready; then run the verify command.')


def verify_profile(args):
    if sys.platform != 'linux':
        raise ValueError('Run verification on Linux.')
    manifest, _ = load_profile(args.bundle)
    errors = []
    kernel_config = Path('/boot/config-' + os.uname().release)
    if not kernel_config.exists() or 'CONFIG_PREEMPT_RT=y' not in kernel_config.read_text():
        errors.append('Current kernel is not PREEMPT_RT')
    cmdline = Path('/proc/cmdline').read_text().split()
    for token in manifest['rt_parameters']:
        if token not in cmdline:
            errors.append('Boot parameter missing: ' + token)
    if topology() != manifest['topology']:
        errors.append('CPU topology mismatch')
    for service in ('defect-detector.service', 'tailscaled.service'):
        print(service, 'enabled=' + run('systemctl', 'is-enabled', service, check=False),
              'active=' + run('systemctl', 'is-active', service, check=False))
    for name in ('isolated', 'nohz_full'):
        print(name + ': ' + Path('/sys/devices/system/cpu', name).read_text().strip())
    if manifest['governor']:
        governors = {p.read_text().strip() for p in Path('/sys/devices/system/cpu').glob('cpu*/cpufreq/scaling_governor')}
        if governors != {manifest['governor']}:
            errors.append('CPU governor mismatch: ' + repr(governors))
    print('Kernel: ' + os.uname().release)
    print('This checks host settings, not readiness of cameras, IO or the inspection pipeline.')
    if errors:
        raise ValueError('\n'.join(errors))
    print('CPU profile checks passed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    export = commands.add_parser('export')
    export.add_argument('output')
    export.add_argument('--admin', default='o')
    export.add_argument('--app-path', default='/home/o/Документы/core-dev')
    install = commands.add_parser('install')
    install.add_argument('bundle')
    install.add_argument('--check', action='store_true', help='Validate without changing system settings')
    commands.add_parser('verify').add_argument('bundle')
    args = parser.parse_args()
    try:
        {'export': export_profile, 'install': apply_profile, 'verify': verify_profile}[args.command](args)
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, KeyError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        if isinstance(exc, subprocess.CalledProcessError):
            print(exc.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
