#!/usr/bin/env python3
"""Explode a screen recording into the frames an SDUI `demo` block plays.

Record the gesture on device however you like: QuickTime, a GIF, iOS's own
screen recorder, then point this at the file. It writes numbered WebP frames and
prints the JSON block to paste into the widget-guide payload.

    scripts/demo_frames.py add_widget.gif --out web/static/guide/add --fps 8

Why frames and not the GIF itself: Coil decodes animated GIFs on Android only,
so a `.gif` URL renders on iOS as a frozen first frame. The full reasoning is on
`SduiAppComponent.Demo`; the short version is that the alternative was bridging
ImageIO from Kotlin/Native to build an animated UIImage, for a container format
that compresses worse than the frames inside it.

Needs ffmpeg on PATH. Everything else is stdlib. For a long recording, or on
a machine without ffmpeg, use `scripts/encode_demo.swift` instead: a video is
the right answer past a few seconds, and it needs nothing installed.
"""

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys


def die(message: str) -> "typing.NoReturn":  # noqa: F821 - message only
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def probe_dimensions(source: pathlib.Path) -> "tuple[int, int]":
    """First frame's width and height, for the aspectRatio the payload declares.

    The client reserves the media box at that ratio BEFORE the first image
    arrives, so a guessed ratio makes the whole screen jump when it loads. It is
    measured here rather than typed by hand for exactly that reason.
    """
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", str(source)],
        capture_output=True, text=True, check=True,
    ).stdout.strip().splitlines()[0]
    match = re.match(r"^(\d+)x(\d+)$", out)
    if not match:
        die(f"could not read dimensions from ffprobe: {out!r}")
    return int(match.group(1)), int(match.group(2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=pathlib.Path, help="GIF, mp4, mov, anything ffmpeg reads")
    parser.add_argument("--out", type=pathlib.Path, required=True,
                        help="directory the frames are written to (created if absent)")
    parser.add_argument("--base-url", default="",
                        help="URL prefix the frames will be served from, e.g. "
                             "https://stationly.app/guide/add")
    parser.add_argument("--fps", type=int, default=8,
                        help="frames per second to sample (default 8). Every frame is a "
                             "request and a cached bitmap, so this is the size dial")
    parser.add_argument("--width", type=int, default=640,
                        help="output width in px (default 640). A help-screen demo is "
                             "never shown wider than a phone")
    parser.add_argument("--quality", type=int, default=72, help="WebP quality (default 72)")
    args = parser.parse_args()

    if not args.source.is_file():
        die(f"no such file: {args.source}")
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            die(f"{tool} is not on PATH. Try: brew install ffmpeg")

    args.out.mkdir(parents=True, exist_ok=True)
    # Clear old frames first. Leaving them means a shorter re-record silently
    # keeps the tail of the previous one, and the JSON below would list frames
    # that belong to a demo nobody is looking at any more.
    for stale in args.out.glob("frame_*.webp"):
        stale.unlink()

    subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(args.source),
         "-vf", f"fps={args.fps},scale={args.width}:-2:flags=lanczos",
         "-quality", str(args.quality),
         str(args.out / "frame_%03d.webp")],
        check=True,
    )

    frames = sorted(args.out.glob("frame_*.webp"))
    if not frames:
        die("ffmpeg produced no frames")

    width, height = probe_dimensions(args.source)
    prefix = args.base_url.rstrip("/")
    block = {
        "type": "demo",
        "id": args.out.name,
        "frames": [f"{prefix}/{f.name}" if prefix else f.name for f in frames],
        "frameMs": round(1000 / args.fps),
        "loop": True,
        "aspectRatio": round(width / height, 4),
        "caption": "",
    }

    total_kb = sum(f.stat().st_size for f in frames) / 1024
    print(json.dumps(block, indent=2))
    print(f"\n{len(frames)} frames, {total_kb:.0f} KB total, in {args.out}", file=sys.stderr)
    if total_kb > 1500:
        print("warning: over 1.5 MB. Drop --fps or --width; this is a help screen, "
              "not a video player.", file=sys.stderr)


if __name__ == "__main__":
    main()
