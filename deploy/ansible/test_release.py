#!/usr/bin/env python3
"""Exercise real Ansible file installation/recovery in /tmp, without a machine."""
import getpass
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml


HERE = Path(__file__).resolve().parent


class DeploymentHeadChecks(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("ci_deploy", HERE / "ci-deploy.py")
        self.deploy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.deploy)
        self.env = {
            "CI_COMMIT_BRANCH": "boobs+pasha", "CI_DEFAULT_BRANCH": "boobs+pasha", "CI_COMMIT_REF_PROTECTED": "true",
            "CI_REPOSITORY_URL": "https://gitlab-ci-token:TEST_SECRET@gitlab.example/project.git",
            "CI_COMMIT_SHA": "a" * 40,
        }

    def test_only_protected_default_branch_is_accepted(self):
        for changes in ({"CI_COMMIT_BRANCH": "work"}, {"CI_COMMIT_REF_PROTECTED": "false"}, {"CI_DEFAULT_BRANCH": ""}):
            with self.assertRaises(SystemExit):
                self.deploy.verify_release_head(dict(self.env, **changes), HERE)

    def test_current_head_is_accepted(self):
        result = subprocess.CompletedProcess([], 0, "a" * 40 + "\trefs/heads/boobs+pasha\n", "")
        with patch.object(self.deploy.subprocess, "run", return_value=result) as run:
            self.deploy.verify_release_head(self.env, HERE)
            self.assertEqual(run.call_args.args[0][-1], "refs/heads/boobs+pasha")

    def test_outdated_release_is_rejected(self):
        result = subprocess.CompletedProcess([], 0, "b" * 40 + "\trefs/heads/boobs+pasha\n", "")
        with patch.object(self.deploy.subprocess, "run", return_value=result):
            with self.assertRaises(SystemExit):
                self.deploy.verify_release_head(self.env, HERE)

    def test_git_failure_does_not_expose_repository_token(self):
        for result in (subprocess.CompletedProcess([], 1, "", self.env["CI_REPOSITORY_URL"]),
                       subprocess.TimeoutExpired([self.env["CI_REPOSITORY_URL"]], 30)):
            kwargs = {"side_effect": result} if isinstance(result, Exception) else {"return_value": result}
            with patch.object(self.deploy.subprocess, "run", **kwargs):
                with self.assertRaises(SystemExit) as error:
                    self.deploy.verify_release_head(self.env, HERE)
                self.assertNotIn("TEST_SECRET", str(error.exception))


class SharedMemoryChecks(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("check_shm", HERE / "check-shm.py")
        self.check = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.check)

    def test_readable_buffers(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for camera in (0, 9):
                (folder / ("iml_cam_" + str(camera) + "_frame")).write_bytes(b"frame")
            self.assertEqual(self.check.check_buffers([0, 9], folder), [0, 9])

    def test_unlinked_buffer_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            path = folder / "iml_cam_0_frame"
            path.write_bytes(b"frame")
            with path.open("rb") as producer:
                path.unlink()
                self.assertEqual(producer.read(), b"frame")
                with self.assertRaises(FileNotFoundError):
                    self.check.check_buffers([0], folder)

    def test_empty_buffer_and_invalid_camera_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "iml_cam_0_frame").touch()
            for cameras in ([0], [], ["../file"], [True]):
                with self.assertRaises(ValueError):
                    self.check.check_buffers(cameras, folder)


class ReleaseRehearsal(unittest.TestCase):
    def test_damaged_backup_does_not_delete_installed_files(self):
        with tempfile.TemporaryDirectory(prefix="kal-ansible-backup-check-") as tmp:
            base = Path(tmp)
            sentinel = base / "installed.txt"
            sentinel.write_text("keep this installed file")
            backup = base / "backup"
            backup.mkdir()
            subprocess.run(["tar", "-cf", str(backup / "before.tar"), "-C", str(base), "installed.txt"], check=True)
            (backup / "before.tar.sha256").write_text("0" * 64 + "  before.tar\n")
            play = [{"hosts": "localhost", "gather_facts": False,
                     "vars": {"backup_ready": True, "backup_dir": {"path": str(backup)},
                              "backup_paths": [str(sentinel)]},
                     "tasks": [{"ansible.builtin.import_tasks": str(HERE / "tasks/restore.yml")}]}]
            playfile = base / "reject-backup.yml"
            playfile.write_text(yaml.safe_dump(play))
            env = dict(os.environ, ANSIBLE_LOCAL_TEMP=str(base / "local-tmp"),
                       ANSIBLE_REMOTE_TEMP=str(base / "remote-tmp"), ANSIBLE_NOCOLOR="1")
            result = subprocess.run(["ansible-playbook", "-i", "localhost,", "-c", "local", str(playfile)],
                                    env=env, text=True, capture_output=True, timeout=60)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("sha256sum", result.stdout)
            self.assertEqual(sentinel.read_text(), "keep this installed file")

    def test_install_failure_restore_and_kiosk_identity(self):
        with tempfile.TemporaryDirectory(prefix="kal-ansible-rehearsal-") as tmp:
            base = Path(tmp)
            app, ui, stage = base / "app", base / "ui", base / "stage"
            backup_root = base / "backups"
            backup_root.mkdir()
            values = yaml.safe_load((HERE / "release-vars.yml").read_text())
            values.update(app_root=str(app), ui_root=str(ui), stage_dir=str(stage),
                          app_user=getpass.getuser(), backup_root=str(backup_root))
            for item in values["install_items"]:
                item["owner"] = "{{ app_user }}"

            # Use the production process recognizer and a harmless shell loop.
            scripts = app / "deploy/linux"
            scripts.mkdir(parents=True)
            shutil.copyfile(HERE.parent / "linux/stack_processes.py", scripts / "stack_processes.py")
            kiosk = app / "deploy/kiosk/session.sh"
            kiosk.parent.mkdir()
            kiosk.write_text("while true; do sleep 1; done\n")
            loop = subprocess.Popen(["bash", str(kiosk)], cwd=app, start_new_session=True)
            env = dict(os.environ, ANSIBLE_LOCAL_TEMP=str(base / "ansible-tmp"),
                       ANSIBLE_REMOTE_TEMP=str(base / "remote-tmp"),
                       ANSIBLE_NOCOLOR="1", PYTHONDONTWRITEBYTECODE="1")
            try:
                subprocess.run(["python3", str(HERE / "check-kiosk.py"), str(app), "running"],
                               check=True, env=env, capture_output=True)
                os.kill(loop.pid, signal.SIGSTOP)
                # waitpid confirms the stop, avoiding a signal delivery race.
                os.waitpid(loop.pid, os.WUNTRACED)
                spec = importlib.util.spec_from_file_location("fixture_processes", scripts / "stack_processes.py")
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                starttime = module.processes()[loop.pid]["starttime"]
                marker = app / ".kiosk-paused.json"
                marker.write_text(json.dumps([dict(pid=loop.pid, starttime=starttime)]))
                subprocess.run(["python3", str(HERE / "check-kiosk.py"), str(app), "stopped"],
                               check=True, env=env, capture_output=True)

                # Wrong process identity must never count as a stopped kiosk.
                marker.write_text(json.dumps([dict(pid=loop.pid, starttime=starttime + 1)]))
                mismatch = subprocess.run(["python3", str(HERE / "check-kiosk.py"), str(app), "stopped"],
                                          env=env, capture_output=True)
                self.assertNotEqual(mismatch.returncode, 0)
                marker.write_text(json.dumps([dict(pid=loop.pid, starttime=starttime)]))
                shutil.copyfile(HERE / "check-kiosk.py", base / "check-kiosk.py")

                old = {
                    "app/config/config.yaml": "old-config",
                    "app/config/blocks/old.yaml": "old-block",
                    "app/config/ips.yaml": "private-ip",
                    "app/config/data/runtime.json": "old-runtime",
                    "app/cppAnalisSurface/data/settings.json": "old-learning",
                    "app/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar": "old-jar",
                    "app/cppAnalisSurface/build/cpp_analis_surface": "old-cpp",
                    "app/camera-worker/build/libcamera_jni.so": "old-jni",
                    "ui/dist/old.js": "old-ui",
                    "ui/electron/main.cjs": "old-electron",
                    "ui/package.json": "old-package",
                    "ui/node_modules/keep.txt": "electron-runtime",
                }
                for name, text in old.items():
                    path = base / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text)
                (app / "config/ips.yaml").chmod(0o600)
                for item in values["install_items"]:
                    src = stage / item["src"]
                    if item["src"].endswith("/"):
                        src.mkdir(parents=True)
                        (src / "new.txt").write_text("new-content")
                    else:
                        src.parent.mkdir(parents=True, exist_ok=True)
                        src.write_text("new-content")
                (base / "vars.yml").write_text(yaml.safe_dump(values, allow_unicode=True))
                play = [{"hosts": "localhost", "gather_facts": False, "vars_files": [str(base / "vars.yml")],
                    "tasks": [
                        {"ansible.builtin.import_tasks": str(HERE / "tasks/backup.yml")},
                        {"block": [
                            {"ansible.builtin.import_tasks": str(HERE / "tasks/install.yml")},
                            {"ansible.builtin.slurp": {"src": "{{ app_root }}/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar"}, "register": "new_jar"},
                            {"ansible.builtin.assert": {"that": ["(new_jar.content | b64decode) == 'new-content'"]}},
                            {"ansible.builtin.slurp": {"src": "{{ app_root }}/config/ips.yaml"}, "register": "ips"},
                            {"ansible.builtin.assert": {"that": ["(ips.content | b64decode) == 'private-ip'"]}},
                            {"ansible.builtin.copy": {"content": "changed-on-start", "dest": "{{ app_root }}/cppAnalisSurface/data/settings.json"}},
                            {"ansible.builtin.fail": {"msg": "intentional failure after installation"}},
                        ], "rescue": [
                            {"ansible.builtin.import_tasks": str(HERE / "tasks/restore.yml")},
                            {"ansible.builtin.set_fact": {"rollback_exercised": True}},
                        ]},
                        {"ansible.builtin.assert": {"that": ["rollback_exercised | default(false)"]}},
                    ]}]
                playfile = base / "rehearse.yml"
                playfile.write_text(yaml.safe_dump(play, allow_unicode=True, sort_keys=False))
                result = subprocess.run(["ansible-playbook", "-i", "localhost,", "-c", "local", str(playfile)],
                                        env=env, text=True, capture_output=True, timeout=120)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for name, text in old.items():
                    self.assertEqual((base / name).read_text(), text, name)
                self.assertEqual((app / "config/ips.yaml").stat().st_mode & 0o777, 0o600)
                self.assertFalse((app / "deployed-version.txt").exists())
                self.assertFalse((ui / "package-lock.json").exists())
                self.assertFalse((ui / "dist/new.txt").exists())
                self.assertTrue(list(backup_root.glob("*/before.tar")))
            finally:
                os.killpg(loop.pid, signal.SIGCONT)
                os.killpg(loop.pid, signal.SIGTERM)
                loop.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
