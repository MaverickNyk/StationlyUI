#!/usr/bin/env swift
//
// Regenerates the staging app icon from the production one.
//
//   swift iosApp/Config/make-staging-icon.swift
//
// Run this whenever AppIcon1024.png changes, so the two icons stay the same
// artwork. Committing the OUTPUT (rather than generating at build time) keeps
// the asset catalog a plain checked-in resource — an icon that only exists
// after someone remembers to run a script is an icon that is missing in CI.
//
// ── The treatment: charcoal field + amber STAGING band ──
//
// The mark itself — geometry and the #E12724 brand red, which matches Android's
// icon to within PNG encoder rounding — is never repainted. Only the field
// behind it changes, and a labelled band is drawn over the bottom.
//
// This replaced a light-grey field on 2026-08-15. Grey failed for a reason
// worth keeping: it preserved the SILHOUETTE. At home-screen size both icons
// read as "red circle with an S", so the two builds were not tellable apart at
// a glance — and the muted grey looked less like a deliberate variant than like
// artwork that had failed to load. The replacement changes the thing the eye
// actually uses at 60pt (overall lightness: a dark tile among light ones) and
// then says the word outright for anyone who looks closer.
//
// iOS icons must be fully opaque with no alpha channel, which is why the
// output is written from an opaque bitmap. iOS masks the icon to a rounded
// superellipse, which clips the band's two bottom corners — intended.

import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()   // Config
    .deletingLastPathComponent()   // iosApp
let src = root.appendingPathComponent("iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon1024.png")
let dstDir = root.appendingPathComponent("iosApp/Assets.xcassets/AppIconStaging.appiconset")
let dst = dstDir.appendingPathComponent("AppIconStaging1024.png")

// Charcoal field, amber band, charcoal lettering on the amber.
let field:     (UInt8, UInt8, UInt8) = (0x1C, 0x1C, 0x1E)
let bandColor  = CGColor(red: 0.976, green: 0.698, blue: 0.102, alpha: 1)  // #F9B21A
let labelColor = CGColor(red: 0.110, green: 0.110, blue: 0.118, alpha: 1)  // #1C1C1E
let bandFraction: CGFloat = 0.21   // of icon height
let label = "STAGING"

guard let srcData = CGImageSourceCreateWithURL(src as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(srcData, 0, nil) else {
    FileHandle.standardError.write("error: cannot read \(src.path)\n".data(using: .utf8)!)
    exit(1)
}

let w = image.width, h = image.height
var px = [UInt8](repeating: 0, count: w * h * 4)
guard let ctx = CGContext(
    data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
    space: CGColorSpaceCreateDeviceRGB(),
    // noneSkipLast = opaque. App icons carrying an alpha channel are rejected
    // at App Store Connect upload time.
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { exit(1) }
ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

// ── Repaint the OUTER field only, by flood fill from the border ──
//
// Three approaches were tried before this one, and the two that failed are
// worth recording because both look correct until you zoom in:
//
//  1. A translucent wash over the whole canvas. Takes the Stationly red from
//     #E12724 to #B81F1E — visibly duller than the brand red and than
//     Android's icon (#E22623). The mark has to come through untouched.
//
//  2. "Recolour every light pixel." Wrong in general: it would catch any light
//     pixel inside the mark as well as outside it.
//
//  3. Clip to the mark's bounding ellipse and draw the source inside it. This
//     assumes the mark is an ellipse aligned to its own bounding box. It is
//     not — measured, the roundel is 691 x 670, and the artwork also carries a
//     soft drop shadow. The mismatch left a white crescent of un-repainted
//     source background along the lower-left arc.
//
// A flood fill needs no assumption about the mark's shape at all: every light
// pixel REACHABLE FROM THE BORDER is field and gets repainted.
//
// Note what that means for THIS artwork specifically. The white bar runs edge
// to edge across the full width, so the S counter is connected to the outside
// through it — counter, bar and surround are one region and all three take the
// field colour together. That is why the S reads as a cut-out of the field
// rather than as white ink, and it is the correct result: the mark stays a red
// disc with the field showing through it, exactly as in production.

/// Light enough to be background. The threshold is deliberately low (205, not
/// 235) so the roundel's soft drop shadow is consumed by the fill as well —
/// left behind, it reads as a dirty smudge against the flat field.
@inline(__always) func isLight(_ i: Int) -> Bool {
    px[i] > 205 && px[i + 1] > 205 && px[i + 2] > 205
}

var filled = [Bool](repeating: false, count: w * h)
var stack: [Int] = []

// Seed from every border pixel. Seeding from one corner would be enough for
// this artwork, but not for a mark that ever touches an edge.
for x in 0..<w { stack.append(x); stack.append((h - 1) * w + x) }
for y in 0..<h { stack.append(y * w); stack.append(y * w + w - 1) }

while let p = stack.popLast() {
    if filled[p] { continue }
    let i = p * 4
    if !isLight(i) { continue }
    filled[p] = true
    px[i] = field.0; px[i + 1] = field.1; px[i + 2] = field.2

    let x = p % w, y = p / w
    if x > 0     { stack.append(p - 1) }
    if x < w - 1 { stack.append(p + 1) }
    if y > 0     { stack.append(p - w) }
    if y < h - 1 { stack.append(p + w) }
}

// The fill stops at the anti-aliased rim, where pixels are part white and part
// ink and so fail `isLight`. Untouched, that leaves a pale halo one or two
// pixels wide between the field and the red — subtle against light grey, but
// glaring against charcoal, so this pass matters more than it used to. Any
// unfilled light-ish pixel ADJACENT to filled field is rim, and gets nudged the
// rest of the way.
for y in 1..<(h - 1) {
    for x in 1..<(w - 1) {
        let p = y * w + x
        if filled[p] { continue }
        let i = p * 4
        // Still clearly lighter than the red, i.e. a white/field blend.
        guard px[i] > 170 && px[i + 1] > 170 && px[i + 2] > 170 else { continue }
        guard filled[p - 1] || filled[p + 1] || filled[p - w] || filled[p + w] else { continue }
        filled[p] = true
        px[i] = field.0; px[i + 1] = field.1; px[i + 2] = field.2
    }
}

// ── The labelled band ──
//
// Drawn through the same context, so it lands on top of the repainted pixels.
let bandH = CGFloat(h) * bandFraction
ctx.setFillColor(bandColor)
ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(w), height: bandH))

// CoreText attribute keys, not NSAttributedString.Key — a bare `swift` script
// links neither AppKit nor UIKit, where those constants are defined.
let font = CTFontCreateWithName("HelveticaNeue-Bold" as CFString, CGFloat(h) * 0.105, nil)
let attrs: [CFString: Any] = [
    kCTFontAttributeName: font,
    kCTForegroundColorAttributeName: labelColor,
    kCTKernAttributeName: CGFloat(h) * 0.012,
]
let line = CTLineCreateWithAttributedString(
    CFAttributedStringCreate(nil, label as CFString, attrs as CFDictionary)!)
// Centre on the INKED bounds rather than the typographic ones, so the word sits
// optically centred in the band instead of being offset by ascender/descender
// space that "STAGING" — all caps, no descenders — does not actually use.
let bounds = CTLineGetImageBounds(line, ctx)
ctx.textPosition = CGPoint(x: (CGFloat(w) - bounds.width) / 2 - bounds.minX,
                           y: (bandH - bounds.height) / 2 - bounds.minY)
CTLineDraw(line, ctx)

guard let out = ctx.makeImage() else { exit(1) }
try? FileManager.default.createDirectory(at: dstDir, withIntermediateDirectories: true)
guard let dest = CGImageDestinationCreateWithURL(dst as CFURL, UTType.png.identifier as CFString, 1, nil) else { exit(1) }
CGImageDestinationAddImage(dest, out, nil)
guard CGImageDestinationFinalize(dest) else { exit(1) }

print("wrote \(dst.path)")
