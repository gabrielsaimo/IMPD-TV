import AppKit
import Foundation

// Top shelf artwork: the wide banner tvOS shows behind the home row while the
// app is the focused one on the top row.
let args = CommandLine.arguments
let source = args[1], out = args[2]
let width = Int(args[3])!, height = Int(args[4])!

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .calibratedRGB, bytesPerRow: 0, bitsPerPixel: 0) else { exit(1) }
rep.size = NSSize(width: width, height: height)

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let full = NSRect(x: 0, y: 0, width: width, height: height)
let scale = CGFloat(height) / 720

NSGradient(colors: [
    NSColor(calibratedRed: 0.027, green: 0.055, blue: 0.130, alpha: 1),
    NSColor(calibratedRed: 0.075, green: 0.145, blue: 0.310, alpha: 1),
])?.draw(in: full, angle: 12)

// Soft glow behind the globe.
// tvOS crops the top shelf at the edges — the wide asset is shown 400pt
// narrower than it is — so everything sits inside a 10% safe margin.
let margin = CGFloat(width) * 0.10
let globeSide = CGFloat(height) * 0.68
let globeRect = NSRect(x: margin, y: (CGFloat(height) - globeSide) / 2,
                       width: globeSide, height: globeSide)
// Clipped to an oval, otherwise the gradient's bounding box leaves a seam.
NSGraphicsContext.current?.saveGraphicsState()
let halo = globeRect.insetBy(dx: -globeSide * 0.24, dy: -globeSide * 0.24)
NSBezierPath(ovalIn: halo).addClip()
NSGradient(colors: [
    NSColor(calibratedRed: 0.29, green: 0.68, blue: 0.95, alpha: 0.30),
    NSColor(calibratedWhite: 0, alpha: 0),
])?.draw(in: halo, relativeCenterPosition: .zero)
NSGraphicsContext.current?.restoreGraphicsState()

if let globe = NSImage(contentsOfFile: source) {
    globe.draw(in: globeRect, from: .zero, operation: .sourceOver, fraction: 1)
}

func rounded(_ size: CGFloat, _ weight: NSFont.Weight) -> NSFont {
    let base = NSFont.systemFont(ofSize: size, weight: weight)
    if let d = base.fontDescriptor.withDesign(.rounded), let f = NSFont(descriptor: d, size: size) {
        return f
    }
    return base
}

let textX = globeRect.maxX + 80 * scale
let title = NSAttributedString(string: "IMPD TV", attributes: [
    .font: rounded(150 * scale, .heavy),
    .foregroundColor: NSColor.white,
])
title.draw(at: NSPoint(x: textX, y: CGFloat(height) * 0.40))

let subtitle = NSAttributedString(string: "Programação ao vivo, o dia inteiro", attributes: [
    .font: rounded(58 * scale, .medium),
    .foregroundColor: NSColor(calibratedWhite: 1, alpha: 0.72),
])
subtitle.draw(at: NSPoint(x: textX, y: CGFloat(height) * 0.24))

// Live pill
let pill = NSRect(x: textX, y: CGFloat(height) * 0.72, width: 250 * scale, height: 72 * scale)
NSColor(calibratedRed: 0.85, green: 0.18, blue: 0.16, alpha: 1).setFill()
NSBezierPath(roundedRect: pill, xRadius: pill.height / 2, yRadius: pill.height / 2).fill()
let live = NSAttributedString(string: "AO VIVO", attributes: [
    .font: rounded(38 * scale, .black),
    .foregroundColor: NSColor.white,
])
live.draw(at: NSPoint(x: pill.minX + (pill.width - live.size().width) / 2,
                      y: pill.minY + (pill.height - live.size().height) / 2))

NSGraphicsContext.restoreGraphicsState()
guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: out))
