#!/usr/bin/env bash
# One-time setup: writable UI assets and a narrowly scoped kiosk restart command.
set -euo pipefail

if ((EUID != 0)); then
  echo "Run once: sudo bash deploy/linux/setup-frontend-updates.sh o" >&2
  exit 1
fi
if (($# > 1)); then
  echo "Usage: sudo bash deploy/linux/setup-frontend-updates.sh [deployment-user]" >&2
  exit 64
fi
deploy_user="${1:-${SUDO_USER:-o}}"
deploy_group="$(id -gn -- "$deploy_user")"
id kiosk >/dev/null
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install_dir=/opt/defect-detector-ui
helper=/usr/local/sbin/defect-detector-restart-ui
sudoers_file=/etc/sudoers.d/defect-detector-frontend

[[ "$(readlink -f "$install_dir")" == "$install_dir" ]] \
  || { echo "Expected a real directory: $install_dir" >&2; exit 1; }
for component in dist electron package.json; do
  target="$install_dir/$component"
  [[ -e "$target" && ! -L "$target" ]] \
    || { echo "Expected an installed, non-symlink UI component: $target" >&2; exit 1; }
done
[[ -x "$install_dir/node_modules/electron/dist/electron" ]] \
  || { echo "Kiosk Electron is not installed" >&2; exit 1; }
[[ -f "$script_dir/defect-detector-restart-ui" ]] \
  || { echo "Restart helper missing next to setup script" >&2; exit 1; }
command -v visudo >/dev/null

temporary_helper="$(mktemp)"
temporary_sudoers="$(mktemp)"
trap 'rm -f -- "$temporary_helper" "$temporary_sudoers"' EXIT
cp "$script_dir/defect-detector-restart-ui" "$temporary_helper"
sh -n "$temporary_helper"
# Empty quoted arguments mean the user may run this exact command with no arguments only.
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/defect-detector-restart-ui ""\n' "$deploy_user" > "$temporary_sudoers"
visudo -cf "$temporary_sudoers"

backup_dir="$(mktemp -d /var/backups/defect-detector-frontend.XXXXXX)"
stat -c '%U:%G %a %n' "$install_dir" "$install_dir/dist" "$install_dir/electron" \
  "$install_dir/package.json" > "$backup_dir/permissions-before.txt"
for installed in "$helper" "$sudoers_file"; do
  if [[ -e "$installed" ]]; then cp -p "$installed" "$backup_dir/$(basename "$installed")"; fi
done
stat -c '%u:%g:%a' "$install_dir/node_modules/electron/dist/chrome-sandbox" > "$backup_dir/sandbox-before.txt"

# Keep the application root, Electron runtime, and setuid sandbox outside the writable assets.
chown root:root "$install_dir"
chmod 0755 "$install_dir"
chown -hR -- "$deploy_user:$deploy_group" "$install_dir/dist" "$install_dir/electron"
chmod -R u+rwX,go+rX -- "$install_dir/dist" "$install_dir/electron"
chown -- "$deploy_user:$deploy_group" "$install_dir/package.json"
chmod 0644 "$install_dir/package.json"
install -o root -g root -m 0755 "$temporary_helper" "$helper"
install -o root -g root -m 0440 "$temporary_sudoers" "$sudoers_file"
visudo -cf "$sudoers_file"
stat -c '%u:%g:%a' "$install_dir/node_modules/electron/dist/chrome-sandbox" | cmp - "$backup_dir/sandbox-before.txt"

printf 'Configured frontend updates for %s; backup: %s\n' "$deploy_user" "$backup_dir"
printf '%s\n' 'Update and restart UI: bash ./rebuild-and-run.sh -c -o --restart-frontend'
