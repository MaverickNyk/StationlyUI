#!/usr/bin/env swift

// Re-encode a screen recording into something a help screen can afford to ship.
//
//   scripts/encode_demo.swift <source> <output.mp4> [maxWidth] [kbps] [fps]
//   scripts/encode_demo.swift ~/Downloads/stack.mov public/assets/stack.mp4 600 800 30
//
// ## Why this and not ffmpeg
// `scripts/demo_frames.py` needs ffmpeg, which is not on every machine that
// touches this repo (it was not on the one this was written on). AVFoundation
// ships with macOS, so this has no install step at all.
//
// ## Why not `avconvert`
// It ships with macOS too, but its presets are fixed: the smallest one turned a
// 15s recording into 9 MB, roughly 4.9 Mbps for a 480p clip, because a preset
// cannot be told this is a help-screen loop rather than footage worth keeping.
// AVAssetWriter takes an explicit bitrate, which is the one dial that matters
// here.
//
// Audio is dropped on purpose. A silent loop in a guide needs no soundtrack, it
// would play over whatever the reader is listening to, and it is free bytes.

import AVFoundation
import CoreImage
import Foundation

func die(_ message: String) -> Never {
    FileHandle.standardError.write(("error: " + message + "\n").data(using: .utf8)!)
    exit(1)
}

let args = CommandLine.arguments
guard args.count >= 3 else {
    die("usage: encode_demo.swift <source> <output.mp4> [maxWidth=600] [kbps=800] [fps=30]")
}
let sourceURL = URL(fileURLWithPath: args[1])
let outputURL = URL(fileURLWithPath: args[2])
let maxWidth = args.count > 3 ? Double(args[3])! : 600
let kbps = args.count > 4 ? Int(args[4])! : 800
let fps = args.count > 5 ? Int(args[5])! : 30

let asset = AVURLAsset(url: sourceURL)
guard let track = asset.tracks(withMediaType: .video).first else {
    die("no video track in \(sourceURL.lastPathComponent)")
}

// The transform matters: a phone recording carries its orientation in metadata
// rather than in the pixel buffer, so naturalSize can be the wrong way round.
let transformed = track.naturalSize.applying(track.preferredTransform)
let sourceW = abs(transformed.width)
let sourceH = abs(transformed.height)

// Even dimensions, because H.264 chroma subsampling cannot express odd ones and
// the writer silently rounds in a way that shifts the image half a pixel.
let scale = min(1.0, maxWidth / sourceW)
let outW = (sourceW * scale / 2).rounded() * 2
let outH = (sourceH * scale / 2).rounded() * 2

try? FileManager.default.removeItem(at: outputURL)

let reader = try AVAssetReader(asset: asset)
let readerOutput = AVAssetReaderTrackOutput(
    track: track,
    outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
)
readerOutput.alwaysCopiesSampleData = false
reader.add(readerOutput)

let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: Int(outW),
    AVVideoHeightKey: Int(outH),
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: kbps * 1000,
        AVVideoExpectedSourceFrameRateKey: fps,
        AVVideoMaxKeyFrameIntervalKey: fps * 2,
        // High profile, auto level. Every device this ships to decodes it in
        // hardware, and at this bitrate the gain over Baseline is the
        // difference between readable text in the recording and mush.
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
    ],
])
writerInput.expectsMediaDataInRealTime = false
writerInput.transform = track.preferredTransform

let adaptor = AVAssetWriterInputPixelBufferAdaptor(
    assetWriterInput: writerInput,
    sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: Int(outW),
        kCVPixelBufferHeightKey as String: Int(outH),
    ]
)
writer.add(writerInput)

// Frames are dropped by wall-clock spacing rather than by count, so a source
// recorded at an odd rate (48.8 fps here) still lands near the requested one.
let frameInterval = 1.0 / Double(fps)
var lastKept = -Double.infinity
var kept = 0

let context = CIContext()
guard reader.startReading(), writer.startWriting() else {
    die("could not start: \(reader.error?.localizedDescription ?? writer.error?.localizedDescription ?? "unknown")")
}
writer.startSession(atSourceTime: .zero)

let queue = DispatchQueue(label: "encode")
let done = DispatchSemaphore(value: 0)

writerInput.requestMediaDataWhenReady(on: queue) {
    while writerInput.isReadyForMoreMediaData {
        guard let sample = readerOutput.copyNextSampleBuffer() else {
            writerInput.markAsFinished()
            writer.finishWriting { done.signal() }
            return
        }
        let time = CMSampleBufferGetPresentationTimeStamp(sample)
        let seconds = CMTimeGetSeconds(time)
        guard seconds - lastKept >= frameInterval - 0.0005 else { continue }
        lastKept = seconds

        guard let src = CMSampleBufferGetImageBuffer(sample),
              let pool = adaptor.pixelBufferPool else { continue }
        var dst: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &dst)
        guard let target = dst else { continue }

        let image = CIImage(cvPixelBuffer: src)
        let sx = outW / image.extent.width
        let sy = outH / image.extent.height
        context.render(image.transformed(by: CGAffineTransform(scaleX: sx, y: sy)), to: target)
        adaptor.append(target, withPresentationTime: time)
        kept += 1
    }
}

done.wait()

if writer.status == .failed {
    die("write failed: \(writer.error?.localizedDescription ?? "unknown")")
}

let bytes = (try? FileManager.default.attributesOfItem(atPath: outputURL.path))
    .flatMap { $0[.size] as? Int } ?? 0
let mb = Double(bytes) / 1_048_576
print(String(format: "%@  %.0fx%.0f  %d frames  %.2f MB",
             outputURL.lastPathComponent, outW, outH, kept, mb))
print(String(format: "aspectRatio for the payload: %.4f", outW / outH))
if mb > 3 {
    FileHandle.standardError.write(
        "warning: over 3 MB. Lower the bitrate or the width; this loops on a help screen.\n"
            .data(using: .utf8)!)
}
