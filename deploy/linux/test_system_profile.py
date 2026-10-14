import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

profile = sys.modules.get('system_profile')
if profile is None:
    spec = importlib.util.spec_from_file_location('system_profile', Path(__file__).with_name('system-profile.py'))
    profile = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(profile)


class ProfileTests(unittest.TestCase):
    def test_gdm_edit_preserves_other_sections_and_is_repeatable(self):
        text = '[daemon]\nAutomaticLoginEnable=False\n# keep this\n[security]\nDisallowTCP=true\n'
        values = {'AutomaticLoginEnable': 'True', 'AutomaticLogin': 'kiosk'}
        edited = profile.edit_daemon(text, values)
        self.assertEqual(profile.edit_daemon(edited, values), edited)
        self.assertIn('[security]\nDisallowTCP=true', edited)
        self.assertIn('# keep this', edited)
        self.assertEqual(edited.count('AutomaticLoginEnable='), 1)

    def test_gdm_edit_adds_missing_daemon_section(self):
        edited = profile.edit_daemon('[security]\nDisallowTCP=true\n', {'AutomaticLogin': 'kiosk'})
        self.assertIn('[daemon]\nAutomaticLogin=kiosk', edited)

    def test_path_allowlist_excludes_credentials_and_traversal(self):
        self.assertTrue(profile.allowed('/etc/systemd/system/tailscaled.service.d/cpu.conf', ['o', 'kiosk']))
        for path in ['/etc/shadow', '/var/lib/tailscale/tailscaled.state',
                     '/etc/systemd/system/tailscaled.service.d/../../shadow']:
            self.assertFalse(profile.allowed(path, ['o', 'kiosk']))

    def test_bundle_checksums_and_permissions(self):
        path = '/etc/systemd/system/defect-detector.service'
        data = b'[Service]\nUser=o\n'
        manifest = {'version': 1, 'admin': 'o', 'files': {
            path: {'sha256': hashlib.sha256(data).hexdigest(), 'mode': 0o644}}}
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp) / 'profile.zip'
            def create(payload):
                with zipfile.ZipFile(bundle, 'w') as archive:
                    archive.writestr('manifest.json', json.dumps(manifest))
                    archive.writestr('files' + path, payload)
            create(data)
            self.assertEqual(profile.load_profile(bundle)[1][path], data)
            create(data + b'changed')
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                profile.load_profile(bundle)
            manifest['files'][path]['mode'] = 0o4777
            create(data)
            with self.assertRaisesRegex(ValueError, 'Invalid file permissions'):
                profile.load_profile(bundle)


if __name__ == '__main__':
    unittest.main()
