#!/bin/bash
# Regenerate xcodeproj and patch objectVersion for Xcode 15.4 compatibility.
# XcodeGen 2.45+ writes objectVersion=77 which Xcode 15.4 cannot open.
set -e
xcodegen generate "$@"
sed -i '' 's/objectVersion = 77;/objectVersion = 56;/' iosApp.xcodeproj/project.pbxproj
# Remove preferredProjectObjectVersion — Xcode 15.4 doesn't recognise this key and crashes
sed -i '' '/preferredProjectObjectVersion/d' iosApp.xcodeproj/project.pbxproj
echo "Project regenerated (objectVersion patched to 56, preferredProjectObjectVersion removed)"
