package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter

/** Follow the host's stagger/ResManager file lookup, then apply bounded decoding on our worker. */
internal class BrandSplashBitmapDecoder(private val environment: HookEnvironment, point: HookPoint? = null) {
    private class Capture(var path: String? = null)
    private val captures = BrandSplashScopes<Capture>()
    private val loader = requireNotNull(environment.classLoader)
    private val info = requireNotNull(KavaMemberLookup.classOrNull(loader, BrandSplashSkipFeatureInstaller.NATIVE_INFO))
    private val createInfo = KavaMemberLookup.constructorOrNull(info)
        ?: error("missing-brand-info-constructor")
    private val setHash = requireNotNull(KavaMemberLookup.methodOrNull(info, "setThumbHash", classOf<String>()))
    private val setThumb = requireNotNull(KavaMemberLookup.methodOrNull(info, "setThumb", classOf<String>()))
    private val setDefault = requireNotNull(KavaMemberLookup.methodOrNull(info, "setDefault", Boolean::class.javaPrimitiveType!!))
    private val decoder = BrandSplashDecoderAdapter.direct(loader) ?: BrandSplashDecoderAdapter.resolve(loader, point)
        ?: error("missing-or-ambiguous-brand-bitmap-decoder")

    fun install() {
        val parameters = listOf(arrayOf(classOf<String>()), arrayOf(classOf<String>(), classOf<BitmapFactory.Options>()))
        parameters.forEachIndexed { index, types ->
            environment.registrar.exact("brand.custom.decode.capture.$index", classOf<BitmapFactory>(), "decodeFile", *types) {
                before {
                    val capture = captures.current() ?: return@before
                    // This scope is entered only by our worker. No disk work happens in the callback.
                    capture.path = argOrNull(0) as? String
                    result = null
                }
            }
        }
    }

    fun decode(image: BrandSplashModelAccess.Image): Bitmap? {
        val value = createInfo.newInstance()
        setHash.invoke(value, image.hash)
        setThumb.invoke(value, image.url)
        setDefault.invoke(value, false)
        val capture = Capture()
        captures.enter(capture)
        val returned = try { decoder.invoke(null, value) as? Bitmap } finally { captures.leave() }
        val path = capture.path
        if (path == null) {
            // A runtime may inline the one-argument factory. Never enlarge or retain an oversized result.
            return returned?.takeIf { it.width.toLong() * it.height <= MAX_PIXELS }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = brandSplashBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        internal const val MAX_PIXELS = 4_194_304L
    }
}

/** Rounded-up dimensions keep a narrow image's short side from disappearing from the budget. */
internal fun brandSplashBitmapSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (((width.toLong() + sample - 1) / sample) *
        ((height.toLong() + sample - 1) / sample) > BrandSplashBitmapDecoder.MAX_PIXELS
    ) sample *= 2
    return sample
}
