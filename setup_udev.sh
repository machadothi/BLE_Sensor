#!/bin/sh
# Grants non-root access to SEGGER J-Link probes (the debugger on the EFR32BG22 kit).
# Run once: sudo sh setup_udev.sh
set -e
cp "$(dirname "$0")/tools/commander-cli/99-jlink.rules" /etc/udev/rules.d/99-jlink.rules
udevadm control --reload-rules
udevadm trigger
echo "J-Link udev rule installed."
