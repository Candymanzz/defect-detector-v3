#!/usr/bin/env bash
set -eu
cd /opt/defect-detector-ui
export IML_KIOSK=1
export IML_ELECTRON_LOG_PATH="$HOME/.local/state/defect-detector/electron-runtime.log"
unset ELECTRON_RENDERER_URL
mkdir -p "$HOME/.local/state/defect-detector"
exec >>"$HOME/.local/state/defect-detector/session.log" 2>&1
# This session runs no desktop shell or general-purpose window manager.
xset s off || true
xset -dpms || true
trap 'exit 0' TERM INT HUP
while true; do
    # Autologin cannot unlock a password-protected GNOME keyring. This dedicated
    # operator UI does not use the keyring for Chromium password storage.
    /opt/defect-detector-ui/node_modules/electron/dist/electron --ozone-platform=x11 --password-store=basic . || true
    sleep 5
done
