import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('configure-cpp-detector.py')


class CppProfileTests(unittest.TestCase):
    def test_creates_overlay_without_rewriting_machine_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'config.yaml'
            original = b'frame:\n  format: Mono8\ncameras:\n  - id: 9\n    serial: machine-specific\n'
            source.write_bytes(original)
            result = subprocess.run([sys.executable, str(SCRIPT), '--config', str(source), '--workers', '6'],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = source.with_name('cpp-linux.yaml')
            data = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(data['imports'], ['config.yaml'])
            self.assertNotIn('cameras', data)
            self.assertEqual(data['integration']['analis_surface_autostart']['inspect_workers'], 6)
            self.assertEqual(data['python_detector']['backend'], 'cpp')
            self.assertFalse(data['java_positioning']['enabled'])
            self.assertEqual(data['integration']['geometry_command_linux'], [])
            self.assertEqual(data['integration']['positioning_command_linux'], [])
            before = output.read_bytes()
            again = subprocess.run([sys.executable, str(SCRIPT), '--config', str(source)], capture_output=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
