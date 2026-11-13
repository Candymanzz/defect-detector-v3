#!/usr/bin/env python3
"""Install the inspected femboy36 runtime on oleg15, preserving its network.

Run without arguments for read-only checks. --apply requires local sudo.
The vendor MVS setup.sh/ScriptServer are deliberately not executed: they change
socket buffers, rp_filter and network drivers. Install the original vendor MVS
payload, environment, desktop entry and device permissions instead.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import time
import urllib.request

BASE = Path('/home/oleg/femboy15-setup/stack-20261113')
APP = Path('/home/oleg/Документы/core-dev')
SDK_SHA = 'fe8caae51d5211023ed15c726463e28bc6f47f6223608f0f67cfc42fdfde0c79'
UNITS = ['cppAnalisSurface.service', 'defect-detector.service', 'MvLogServer.service']


def run(*args, check=True, **kwargs):
    result = subprocess.run(args, text=True, capture_output=True, check=check, **kwargs)
    return result.stdout.strip()


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def network_snapshot():
    files = {}
    for folder in ['/etc/NetworkManager/system-connections', '/etc/netplan', '/etc/sysctl.d']:
        for path in Path(folder).glob('*'):
            if path.is_file():
                files[str(path)] = sha(path)
    files['/etc/sysctl.conf'] = sha(Path('/etc/sysctl.conf'))
    return {'files': files,
            'addresses': json.loads(run('ip', '-j', 'address')),
            'routes': json.loads(run('ip', '-j', 'route', 'show', 'table', 'all')),
            'rules': json.loads(run('ip', '-j', 'rule')),
            'sysctl': {key: run('sysctl', '-n', key) for key in
                       ['net.core.rmem_max', 'net.core.wmem_max', 'net.ipv4.conf.all.rp_filter',
                        'net.ipv4.conf.default.rp_filter']}}


def health(url, seconds=40):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                body = response.read().decode()
            if body.strip() == 'ok' or json.loads(body).get('status') == 'ok':
                return body
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Health check timed out: ' + url)


def units():
    common = 'Restart=on-failure\nRestartSec=15\nKillMode=control-group\nTimeoutStopSec=60\n'
    cpp = f'''[Unit]
Description=C++ surface detector (priority nice -10)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=oleg
WorkingDirectory={APP}/cppAnalisSurface
Nice=-10
CPUAffinity=3 4 5
Environment=ANALIS_INSPECT_WORKERS=3
Environment=CAS_PERFORMANCE_LOG=1
Environment=CAS_PERFORMANCE_LOG_DIR={APP}/logs/performance
ExecStart={APP}/cppAnalisSurface/build/cpp_analis_surface --inspect-cpus 3-5 --host 127.0.0.1 --port 8000 --data-dir data
{common}
[Install]
WantedBy=multi-user.target
'''
    java = f'''[Unit]
Description=Factory defect detector orchestrator
After=network-online.target cppAnalisSurface.service
Wants=network-online.target cppAnalisSurface.service
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=oleg
WorkingDirectory={APP}
CPUAffinity=2 8
Environment=IML_FRONTEND_AUTOSTART=false
Environment=MVCAM_COMMON_RUNENV=/opt/MVS/lib
Environment=MVCAM_SOFTWARE_LIBENV=/opt/MVS/lib
Environment=MVCAM_SDK_PATH=/opt/MVS
Environment=MVCAM_GENICAM_CLPROTOCOL=/opt/MVS/lib/CLProtocol
Environment=LD_LIBRARY_PATH=/opt/MVS/lib/64:/opt/MVS/bin
ExecStartPre=/usr/bin/python3 {APP}/deploy/linux/wait-installed-detector.py
ExecStart=/usr/bin/java -Xms256m -Xmx1024m -XX:+ExitOnOutOfMemoryError -jar {APP}/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar {APP}/config/config.yaml
{common}
[Install]
WantedBy=multi-user.target
'''
    log = '''[Unit]
Description=MVS SDK log server
After=local-fs.target

[Service]
Type=forking
WorkingDirectory=/opt/MVS/logserver
ExecStart=/opt/MVS/logserver/MvLogServer
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
'''
    return dict(zip(UNITS, [cpp, java, log]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if os.uname().nodename != 'oleg15':
        raise SystemExit('This installer is restricted to hostname oleg15.')
    if os.uname().release != '6.8.1-1015-realtime':
        raise SystemExit('Expected already installed RT kernel is not active.')
    if 'CONFIG_PREEMPT_RT=y' not in Path('/boot/config-' + os.uname().release).read_text():
        raise SystemExit('The active kernel does not have PREEMPT_RT enabled.')
    staged = BASE / 'app'
    receipt = json.loads((BASE / 'prepared.json').read_text())
    for name, expected in receipt['critical_sha256'].items():
        if sha(staged / name) != expected:
            raise SystemExit('Prepared runtime changed: ' + name)
    if sha(BASE / 'mvs/lib/64/libMvCameraControl.so') != SDK_SHA:
        raise SystemExit('MVS SDK checksum differs from femboy36.')
    for directory in ['/opt/MVS', '/opt/defect-detector-ui']:
        if Path(directory).exists():
            raise SystemExit('Existing installation must be reviewed first: ' + directory)
    for name in UNITS:
        if run('systemctl', 'show', name, '-p', 'LoadState', '--value', check=False) != 'not-found':
            raise SystemExit('Existing service must be reviewed first: ' + name)
    run('java', '-cp', str(APP / 'orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar'),
        '/home/oleg/femboy15-setup/ExportConfig.java', str(APP / 'config/config.yaml'), str(BASE / 'config-last-check.json'))
    target_now = json.loads((BASE / 'config-last-check.json').read_text())
    before_config = json.loads(Path('/home/oleg/femboy15-setup/config-before-20261113.json').read_text())
    if target_now != before_config:
        raise SystemExit('Target configuration changed after preparation; regenerate the snapshot first.')
    print('Preflight passed: RT kernel, prepared application, SDK and preserved configuration.', flush=True)
    if not args.apply:
        print('Apply locally: sudo python3 ' + str(Path(__file__).resolve()) + ' --apply')
        return
    if os.geteuid() != 0:
        raise SystemExit('--apply requires sudo.')
    backup = Path('/var/backups/femboy15-stack') / datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup.mkdir(parents=True, mode=0o700)
    lock = Path('/var/lib/femboy15-stack-install.lock')
    lock.mkdir()
    original_files = {}
    created_dirs = []
    moved_app = False
    def write(path, content, mode=0o644):
        path = Path(path)
        original_files[str(path)] = path.read_bytes().hex() if path.exists() else None
        (backup / 'original-files.json').write_text(json.dumps(original_files, indent=2))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(mode)
    def own_tree(directory, uid, gid, strip_write=False):
        for parent, folders, files in os.walk(directory):
            for path in [Path(parent)] + [Path(parent) / n for n in folders + files]:
                os.lchown(path, uid, gid)
                if strip_write and not path.is_symlink():
                    path.chmod(path.stat().st_mode & ~0o022)
    before_network = network_snapshot()
    before_power = run('powerprofilesctl', 'get')
    before_cpu = {str(path): path.read_text() for pattern in ['policy*/scaling_governor', 'policy*/energy_performance_preference']
                  for path in Path('/sys/devices/system/cpu/cpufreq').glob(pattern)}
    (backup / 'network-before.json').write_text(json.dumps(before_network, indent=2))
    (backup / 'prepared.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2))
    print('Backup:', backup, flush=True)
    try:
        for source, destination in [(BASE / 'mvs', Path('/opt/MVS')), (BASE / 'ui', Path('/opt/defect-detector-ui'))]:
            shutil.copytree(source, destination, symlinks=True)
            created_dirs.append(destination)
            own_tree(destination, 0, 0, strip_write=destination.name == 'defect-detector-ui')
        Path('/opt/defect-detector-ui/node_modules/electron/dist/chrome-sandbox').chmod(0o4755)
        (Path('/opt/MVS') / 'defect-detector-install.json').write_text(json.dumps({
            'method': 'original vendor payload; setup.sh and network hooks not executed',
            'mvs': '5.0.2 Build 20260728', 'sdk': '4.8.1.2 Build 20260721',
            'vendor_deb_sha256': 'd2e72437dcff53444feef140dc2dabf30e1d4ea789cae440336e0a93661bc9f5',
            'control_library_sha256': SDK_SHA}, indent=2) + '\n')
        os.rename(APP, backup / 'application-before')
        moved_app = True
        os.rename(staged, APP)
        account = pwd.getpwnam('oleg')
        own_tree(APP, account.pw_uid, account.pw_gid)
        # The original stack-stopping scripts kill C++ by executable identity.
        # Direct Java under systemd avoids stopping the independently managed detector.
        waiter = '''import time, urllib.request
for attempt in range(40):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2) as r:
            if r.status == 200: break
    except OSError: time.sleep(1)
else: raise SystemExit('C++ detector health timed out')
'''
        write(APP / 'deploy/linux/wait-installed-detector.py', waiter)
        os.chown(APP / 'deploy/linux/wait-installed-detector.py', account.pw_uid, account.pw_gid)
        write('/etc/ld.so.conf.d/defect-detector-mvs.conf', '/opt/MVS/lib/64\n/opt/MVS/bin\n')
        write('/etc/profile.d/defect-detector-mvs.sh', '''export MVCAM_COMMON_RUNENV=/opt/MVS/lib
export MVCAM_SOFTWARE_LIBENV=/opt/MVS/lib
export MVCAM_SDK_PATH=/opt/MVS
export MVCAM_GENICAM_CLPROTOCOL=/opt/MVS/lib/CLProtocol
''')
        write('/etc/udev/rules.d/81-defect-detector-mvs.rules',
              'SUBSYSTEM=="tty", KERNEL=="ttyS[0-9]*", GROUP="dialout", MODE="0660"\n'
              'SUBSYSTEM=="tty", KERNEL=="ttyvirserial[0-9]*", GROUP="dialout", MODE="0660"\n'
              'SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ATTR{idVendor}=="2bdf", GROUP="plugdev", MODE="0660"\n')
        write('/usr/share/applications/MVS.desktop', '''[Desktop Entry]
Type=Application
Name=MVS 5.0.2
Exec=/opt/MVS/bin/MVS.sh
Icon=/opt/MVS/bin/MVS.ico
Terminal=false
''')
        write('/usr/share/applications/defect-detector.desktop', '''[Desktop Entry]
Type=Application
Name=Defect Detector
Exec=/opt/defect-detector-ui/node_modules/electron/dist/electron /opt/defect-detector-ui
Path=/opt/defect-detector-ui
Terminal=false
''')
        write('/etc/systemd/logind.conf.d/90-defect-detector-ipc.conf', '[Login]\nRemoveIPC=no\n')
        for name, content in units().items():
            write('/etc/systemd/system/' + name, content)
        run('ldconfig')
        run('udevadm', 'control', '--reload-rules')
        run('usermod', '-aG', 'dialout,plugdev', 'oleg')
        run('systemctl', 'daemon-reload')
        run('systemd-analyze', 'verify', *['/etc/systemd/system/' + n for n in UNITS])
        run('systemctl', 'reload', 'systemd-logind')
        run('powerprofilesctl', 'set', 'performance')
        run('systemctl', 'restart', 'cpu-performance.service')
        for name in UNITS:
            run('systemctl', 'enable', '--now', name)
        cpp_health = health('http://127.0.0.1:8000/health')
        java_health = health('http://127.0.0.1:8099/health', seconds=100)
        for name in UNITS:
            if run('systemctl', 'is-active', name, check=False) != 'active':
                raise RuntimeError('Installed service is not active: ' + name)
        pid = int(run('systemctl', 'show', 'cppAnalisSurface.service', '-p', 'MainPID', '--value'))
        nice = run('ps', '-p', str(pid), '-o', 'ni=', '--no-headers').strip()
        if nice != '-10':
            raise RuntimeError('C++ process did not receive nice=-10: ' + nice)
        if sha(Path('/proc') / str(pid) / 'exe') != receipt['critical_sha256']['cppAnalisSurface/build/cpp_analis_surface']:
            raise RuntimeError('Running C++ checksum mismatch')
        after_network = network_snapshot()
        (backup / 'network-after.json').write_text(json.dumps(after_network, indent=2))
        if before_network['files'] != after_network['files'] or before_network['sysctl'] != after_network['sysctl']:
            raise RuntimeError('Network files or sysctl changed during installation')
        result = {'cpp_health': cpp_health, 'java_health': java_health, 'cpp_pid': pid, 'cpp_nice': nice,
                  'network_files_and_sysctl_preserved': True, 'kernel': os.uname().release,
                  'backup': str(backup), 'source_head': receipt['source_head']}
        (backup / 'installed.json').write_text(json.dumps(result, indent=2) + '\n')
        (BASE / 'installed.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2), flush=True)
        print('Installed. MVS and the frontend can be opened from the applications menu. No reboot performed.')
    except BaseException:
        for name in UNITS:
            run('systemctl', 'disable', '--now', name, check=False)
        if moved_app:
            if APP.exists():
                os.rename(APP, backup / 'failed-application')
            os.rename(backup / 'application-before', APP)
        for path in created_dirs:
            os.rename(path, backup / ('failed-' + path.name))
        for name, data in original_files.items():
            path = Path(name)
            if path.is_relative_to(APP):
                continue
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(bytes.fromhex(data))
        run('ldconfig', check=False)
        run('systemctl', 'daemon-reload', check=False)
        run('systemctl', 'reload', 'systemd-logind', check=False)
        run('powerprofilesctl', 'set', before_power, check=False)
        for name, content in before_cpu.items():
            Path(name).write_text(content)
        print('Installation failed. Previous application/files restored; inspect backup:', backup, flush=True)
        raise
    finally:
        lock.rmdir()


if __name__ == '__main__':
    main()
