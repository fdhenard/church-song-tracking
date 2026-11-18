import Foundation
import Vision

// One JSON request and response per frame. Paths and OCR text remain on this Mac.
while let line = readLine() {
    autoreleasepool {
        do {
            guard let data = line.data(using: .utf8),
                  let input = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let path = input["path"] as? String,
                  let index = input["index"] as? Int else {
                throw NSError(domain: "LocalOCR", code: 1)
            }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["en-US"]
            request.usesLanguageCorrection = false
            request.minimumTextHeight = 0.005
            let handler = VNImageRequestHandler(url: URL(fileURLWithPath: path), options: [:])
            try handler.perform([request])
            let observations = (request.results ?? []).sorted {
                if abs($0.boundingBox.midY - $1.boundingBox.midY) > 0.015 {
                    return $0.boundingBox.midY > $1.boundingBox.midY
                }
                return $0.boundingBox.minX < $1.boundingBox.minX
            }
            let lines: [[String: Any]] = observations.compactMap { observation in
                guard let candidate = observation.topCandidates(1).first else { return nil }
                let box = observation.boundingBox
                return ["text": candidate.string, "confidence": Double(candidate.confidence),
                        "box": [Double(box.minX), Double(box.minY), Double(box.width), Double(box.height)]]
            }
            let result: [String: Any] = ["index": index, "lines": lines,
                                        "text": lines.compactMap { $0["text"] as? String }.joined(separator: "\n")]
            let output = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
            print(String(decoding: output, as: UTF8.self))
            fflush(stdout)
        } catch {
            // Do not silently skip a failed frame, which could hide a title or author slide.
            let output = try! JSONSerialization.data(withJSONObject: ["error": "Local OCR could not read a frame."])
            print(String(decoding: output, as: UTF8.self))
            fflush(stdout)
        }
    }
}
