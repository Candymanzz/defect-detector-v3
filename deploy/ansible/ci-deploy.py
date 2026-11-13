#!/usr/bin/env python3
"""Deploy a current protected default-branch commit using its pipeline's artifacts."""
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile


def verify_release_head(env, workspace):
    branch = env.get("CI_DEFAULT_BRANCH")
    if not branch or env.get("CI_COMMIT_BRANCH") != branch or env.get("CI_COMMIT_REF_PROTECTED") != "true":
        raise SystemExit("Deployment requires the protected default branch")
    repository = env.get("CI_REPOSITORY_URL")
    commit = env.get("CI_COMMIT_SHA", "")
    if not repository or not re.fullmatch(r"[a-f0-9]{40,64}", commit):
        raise SystemExit("Missing GitLab repository or commit information")
    try:
        result = subprocess.run(
            ["git", "ls-remote", "--exit-code", repository, "refs/heads/" + branch],
            cwd=workspace, text=True, capture_output=True, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise SystemExit("Cannot verify current default branch") from None
    # Do not print git stderr: the repository URL can contain CI credentials.
    if result.returncode or not result.stdout.strip():
        raise SystemExit("Cannot verify current default branch")
    if result.stdout.split()[0] != commit:
        raise SystemExit("A newer commit exists on the default branch; refusing an outdated deployment")


def main():
    workspace = Path(os.environ["CI_PROJECT_DIR"]).resolve()
    verify_release_head(os.environ, workspace)
    release = "defect-detector-{}-{}".format(os.environ["CI_PIPELINE_IID"], os.environ["CI_COMMIT_SHORT_SHA"])
    for suffix in (".tar.gz", ".tar.gz.sha256"):
        if not (workspace / (release + suffix)).is_file():
            raise SystemExit("Missing artifact: " + release + suffix)
    # Only the first audited workstation is initially enabled.
    target = os.environ.get("DEPLOY_TARGET", "femboy36")
    if target != "femboy36":
        raise SystemExit("Only femboy36 is enabled until femboy15 has been audited")
    if not re.fullmatch(r"defect-detector-\d+-[a-f0-9]{8,40}", release):
        raise SystemExit("Invalid release identifier")
    with tempfile.TemporaryDirectory(prefix="kal-ci-deploy-") as tmp:
        folder = Path(tmp)
        files = {}
        for key in ("SSH_PRIVATE_KEY", "SSH_KNOWN_HOSTS", "DEPLOY_INVENTORY"):
            source = Path(os.environ.get(key, ""))
            if not source.is_file():
                raise SystemExit("Configure GitLab FILE variable " + key)
            dest = folder / key.lower()
            shutil.copyfile(source, dest)
            dest.chmod(0o600)
            files[key] = dest
        variables = {"release_name": release, "local_release_dir": str(workspace)}
        password = os.environ.get("ANSIBLE_BECOME_PASSWORD")
        if password:
            variables["ansible_become_password"] = password
        extra = folder / "variables.json"
        extra.write_text(json.dumps(variables))
        extra.chmod(0o600)
        env = dict(os.environ)
        env.pop("ANSIBLE_BECOME_PASSWORD", None)
        env["ANSIBLE_SSH_COMMON_ARGS"] = shlex.join([
            "-F", "/dev/null",
            "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + str(files["SSH_KNOWN_HOSTS"]),
            "-i", str(files["SSH_PRIVATE_KEY"]),
        ])
        env["ANSIBLE_HOST_KEY_CHECKING"] = "True"
        subprocess.run([
            "ansible-playbook", "-i", str(files["DEPLOY_INVENTORY"]),
            str(workspace / "deploy/ansible/deploy-release.yml"),
            "--limit", target, "--extra-vars", "@" + str(extra),
        ], env=env, cwd=workspace, check=True)


if __name__ == "__main__":
    main()
