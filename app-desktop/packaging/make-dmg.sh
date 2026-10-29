#!/bin/sh
# Builds the DMG with hdiutil: the app next to a link to Applications, ready to be dragged in.
# jpackage lays out its own DMG by scripting the Finder, and without that permission the link
# is missing; this needs no permission at all.
#
#   make-dmg.sh <El Termino.app> <volume icon .icns> <output .dmg>
set -eu
app="$1"
icon="$2"
out="$3"

work=$(mktemp -d)
trap 'hdiutil detach -quiet "$work/mount" 2>/dev/null || true; rm -rf "$work"' EXIT
mkdir "$work/content" "$work/mount"
ditto "$app" "$work/content/$(basename "$app")"
ln -s /Applications "$work/content/Applications"
cp "$icon" "$work/content/.VolumeIcon.icns"

# The volume shows its icon only with the custom-icon flag on its root, which can only be set
# on the mounted image: build it writable, flag it, then compress it.
hdiutil create -quiet -volname "El Termino" -srcfolder "$work/content" -fs HFS+ -format UDRW "$work/rw.dmg"
hdiutil attach -quiet -nobrowse -mountpoint "$work/mount" "$work/rw.dmg"
SetFile -a C "$work/mount"
hdiutil detach -quiet "$work/mount"

mkdir -p "$(dirname "$out")"
rm -f "$out"
hdiutil convert -quiet "$work/rw.dmg" -format UDZO -o "$out"
