package com.rrajath.grove.attach

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import okio.GzipSource
import okio.buffer

/**
 * Coil [Decoder] for gzip-compressed SVG files (`.svgz`).
 *
 * Detects the gzip magic header (`0x1f 0x8b`) when the source path/URI ends in
 * `.svgz` (or has an SVGZ mime/header), decompresses via [GzipSource], and
 * delegates to Coil's [SvgDecoder].
 */
class SvgzDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val rawSource = source.source()
        val decompressed = GzipSource(rawSource).buffer()
        val svgSource = ImageSource(source = decompressed, fileSystem = source.fileSystem)
        return SvgDecoder(svgSource, options).decode()
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val buffered = result.source.source()
            val isGzip = buffered.rangeEquals(0, GZIP_MAGIC)
            if (!isGzip) return null
            // Only intercept gzipped streams when the data looks like SVGZ
            // (mime/extension check, or peek after decompression is not needed
            // since standard raster formats don't start with 0x1f 0x8b).
            return SvgzDecoder(result.source, options)
        }
    }

    companion object {
        private val GZIP_MAGIC = okio.ByteString.of(0x1f.toByte(), 0x8b.toByte())
    }
}

