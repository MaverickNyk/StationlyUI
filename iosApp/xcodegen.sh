#!/bin/bash
# Regenerate the Xcode project from project.yml.
#
# NOTE (2026-06): the toolchain is now Xcode 26, which reads XcodeGen's native
# objectVersion=77 format directly. The old objectVersion 77→56 downgrade (an
# Xcode-15.4 workaround) is REMOVED — on Xcode 26 it left SUPPORTED_PLATFORMS
# unresolved and broke simulator/device destination matching. If you ever build
# with Xcode 15.x again, re-add the sed downgrade below.
set -e
xcodegen generate "$@"
echo "Project regenerated (native objectVersion 77 for Xcode 16+/26)."
