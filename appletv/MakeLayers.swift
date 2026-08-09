import AppKit
import Foundation

// Renders one layer of the tvOS layered icon at an exact pixel size.
//
// The bitmap is allocated explicitly rather than using lockFocus: on a Retina
// Mac that path renders at 2x, so every "1x" asset came out double-size and
// the catalog was rejected.
let args = CommandLine.arguments
let source = args[1], kind = args[2], out = args[3]
let width = Int(args[4])!, height = Int(args[5])!

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .calibratedRGB, bytesPerRow: 0, bitsPerPixel: 0) else { exit(1) }
rep.size = NSSize(width: width, height: height)

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
if kind == "back" {
    NSColor(calibratedRed: 0.051, green: 0.090, blue: 0.188, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: width, height: height).fill()
} else if let globe = NSImage(contentsOfFile: source) {
    let side = CGFloat(min(width, height)) * 0.78
    globe.draw(in: NSRect(x: (CGFloat(width) - side) / 2,
                          y: (CGFloat(height) - side) / 2,
                          width: side, height: side),
               from: .zero, operation: .sourceOver, fraction: 1)
}
NSGraphicsContext.restoreGraphicsState()

guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: out))
