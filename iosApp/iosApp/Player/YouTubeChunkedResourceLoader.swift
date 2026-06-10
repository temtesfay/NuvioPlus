import AVFoundation
import Foundation

/// Intercepts AVFoundation's network requests for YouTube CDN (googlevideo.com) URLs
/// and re-fetches data in 10 MB chunks using YouTube's &range=start-end parameter.
///
/// YouTube's CDN throttles connections that try to download an adaptive stream in a
/// single large HTTP request. Breaking into 10 MB chunks with &range= makes each
/// sub-request look like an independent small fetch — the throttle never activates.
/// This is the iOS equivalent of Android's YoutubeChunkedDataSourceFactory.
///
/// Usage (handled automatically by HeroTrailerPreBufferCache):
///   let (asset, loader) = YouTubeChunkedResourceLoader.makeAsset(for: videoUrl)!
///   // retain `loader` — AVURLAsset holds only a weak reference to its delegate
///   let item = AVPlayerItem(asset: asset)
final class YouTubeChunkedResourceLoader: NSObject, AVAssetResourceLoaderDelegate {

    static let scheme = "ytchunked"
    private static let chunkSize: Int64 = 10 * 1_024 * 1_024  // 10 MB

    // MARK: - Factory

    /// Create an AVURLAsset with chunked loading for a YouTube CDN URL.
    /// Returns nil if urlString is not a googlevideo.com URL — caller uses a plain AVURLAsset.
    static func makeAsset(for urlString: String) -> (AVURLAsset, YouTubeChunkedResourceLoader)? {
        guard urlString.contains("googlevideo.com") else { return nil }
        guard var components = URLComponents(string: urlString) else { return nil }
        components.scheme = scheme
        guard let wrappedURL = components.url else { return nil }
        let loader = YouTubeChunkedResourceLoader()
        let asset = AVURLAsset(url: wrappedURL)
        asset.resourceLoader.setDelegate(
            loader,
            queue: DispatchQueue(label: "com.nuvio.ytchunked", qos: .userInitiated)
        )
        return (asset, loader)
    }

    // MARK: - URLSession

    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()

    // MARK: - AVAssetResourceLoadingDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let requestURL = loadingRequest.request.url,
              let realURL = httpsURL(from: requestURL) else {
            loadingRequest.finishLoading(with: URLError(.badURL))
            return false
        }

        if let infoReq = loadingRequest.contentInformationRequest {
            fetchContentInfo(realURL: realURL, infoRequest: infoReq, loadingRequest: loadingRequest)
        } else if let dataReq = loadingRequest.dataRequest {
            let start = dataReq.requestedOffset
            // Int64.max signals "stream until server stops" (requestsAllDataToEndOfResource).
            let end = dataReq.requestsAllDataToEndOfResource
                ? Int64.max
                : start + Int64(dataReq.requestedLength) - 1
            fetchChunked(
                realURL: realURL, start: start, end: end,
                dataRequest: dataReq, loadingRequest: loadingRequest
            )
        } else {
            loadingRequest.finishLoading()
        }
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        // URLSession tasks are not tracked per-request here; they complete and the
        // isFinished guard prevents double-finish. Tasks time out within 30s if AVFoundation
        // no longer consumes the data.
    }

    // MARK: - Content info (MIME type + file length)

    private func userAgentForURL(_ url: URL) -> String? {
        let urlString = url.absoluteString.lowercased()
        if urlString.contains("c=android_vr") {
            return "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip"
        } else if urlString.contains("c=android") {
            return "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip"
        } else if urlString.contains("c=ios") {
            return "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)"
        }
        return "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    }

    private func fetchContentInfo(
        realURL: URL,
        infoRequest: AVAssetResourceLoadingContentInformationRequest,
        loadingRequest: AVAssetResourceLoadingRequest
    ) {
        var req = URLRequest(url: realURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        // Standard HTTP Range header (not YouTube's &range= param) to get Content-Range with total length.
        req.setValue("bytes=0-1", forHTTPHeaderField: "Range")
        if let ua = userAgentForURL(realURL) {
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
        }

        session.dataTask(with: req) { [weak self] _, response, error in
            guard let self = self, !loadingRequest.isFinished else { return }
            guard error == nil,
                  let http = response as? HTTPURLResponse,
                  http.statusCode == 200 || http.statusCode == 206 else {
                loadingRequest.finishLoading(with: error ?? URLError(.badServerResponse))
                return
            }

            var contentLength: Int64 = http.expectedContentLength
            // Content-Range: bytes 0-1/TOTAL → parse TOTAL
            if let raw = http.value(forHTTPHeaderField: "Content-Range"),
               let slash = raw.lastIndex(of: "/"),
               let total = Int64(raw[raw.index(after: slash)...].trimmingCharacters(in: .whitespaces)) {
                contentLength = total
            }

            if contentLength > 0 {
                infoRequest.contentLength = contentLength
            }
            infoRequest.isByteRangeAccessSupported = true
            infoRequest.contentType = self.utiForMIME(http.mimeType)
            loadingRequest.finishLoading()
        }.resume()
    }

    // MARK: - Chunked data fetch

    private func fetchChunked(
        realURL: URL,
        start: Int64,
        end: Int64,
        dataRequest: AVAssetResourceLoadingDataRequest,
        loadingRequest: AVAssetResourceLoadingRequest
    ) {
        guard !loadingRequest.isFinished else { return }

        let chunkEnd = end == Int64.max
            ? start + Self.chunkSize - 1
            : min(start + Self.chunkSize - 1, end)

        guard let chunkURL = rangedURL(realURL, start: start, end: chunkEnd) else {
            loadingRequest.finishLoading(with: URLError(.badURL))
            return
        }

        var req = URLRequest(url: chunkURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        if let ua = userAgentForURL(realURL) {
            req.setValue(ua, forHTTPHeaderField: "User-Agent")
        }

        session.dataTask(with: req) { [weak self] data, response, error in
            guard let self = self, !loadingRequest.isFinished else { return }

            guard error == nil,
                  let http = response as? HTTPURLResponse,
                  http.statusCode == 200 || http.statusCode == 206,
                  let data = data else {
                loadingRequest.finishLoading(with: error ?? URLError(.badServerResponse))
                return
            }

            if data.isEmpty {
                loadingRequest.finishLoading()
                return
            }

            dataRequest.respond(with: data)

            let nextStart = start + Int64(data.count)
            // Done when: we've reached the requested end, or the server returned less than
            // a full chunk (meaning we hit EOF on an open-ended request).
            let hitEnd = end != Int64.max && nextStart > end
            let serverEOF = end == Int64.max && Int64(data.count) < Self.chunkSize

            if hitEnd || serverEOF {
                loadingRequest.finishLoading()
            } else {
                self.fetchChunked(
                    realURL: realURL, start: nextStart, end: end,
                    dataRequest: dataRequest, loadingRequest: loadingRequest
                )
            }
        }.resume()
    }

    // MARK: - Helpers

    private func httpsURL(from ytchunkedURL: URL) -> URL? {
        guard var c = URLComponents(url: ytchunkedURL, resolvingAgainstBaseURL: false) else { return nil }
        c.scheme = "https"
        return c.url
    }

    /// Remove any existing &range= param and add the new chunk range.
    private func rangedURL(_ base: URL, start: Int64, end: Int64) -> URL? {
        guard var c = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        var items = c.queryItems?.filter { $0.name != "range" } ?? []
        items.append(URLQueryItem(name: "range", value: "\(start)-\(end)"))
        c.queryItems = items
        return c.url
    }

    private func utiForMIME(_ mime: String?) -> String {
        let base = mime?.components(separatedBy: ";").first?
            .trimmingCharacters(in: .whitespaces).lowercased()
        switch base {
        case "video/mp4":              return "public.mpeg-4"
        case "video/webm":             return "org.webmproject.webm"
        case "audio/mp4", "audio/aac": return "public.aac-audio"
        case "audio/webm", "audio/ogg": return "org.webmproject.webm"
        default:                        return "public.mpeg-4"
        }
    }
}
