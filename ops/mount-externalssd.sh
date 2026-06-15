#!/bin/zsh
set -u

VOLUME_UUID="4D8EB37F-11D2-4D69-A648-6B2B7C952342"
MOUNT_POINT="/Users/nazaria/Public/Mounts/ExternalSSD"

info="$(/usr/sbin/diskutil info "$VOLUME_UUID" 2>/dev/null || true)"
if [[ -z "$info" ]]; then
  exit 0
fi

if print -r -- "$info" | /usr/bin/grep -q "Mounted: *Yes"; then
  exit 0
fi

/bin/mkdir -p "$MOUNT_POINT"
/usr/sbin/diskutil mount -mountPoint "$MOUNT_POINT" "$VOLUME_UUID"
