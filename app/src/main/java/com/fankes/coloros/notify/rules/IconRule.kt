package com.fankes.coloros.notify.rules

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Base64

/** Immutable icon bytes whose bitmap is decoded only on first use. */
class IconAsset private constructor(
    private val bytes: ByteArray,
) {
    @Volatile
    private var cachedBitmap: Bitmap? = null

    val bitmap: Bitmap
        get() = cachedBitmap ?: synchronized(this) {
            cachedBitmap ?: decodeBitmap().also { cachedBitmap = it }
        }

    private fun decodeBitmap(): Bitmap {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IconDecodeException("The icon payload is not a supported bitmap")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is IconAsset && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun fromBase64(encoded: String): IconAsset {
            require(encoded.isNotBlank()) { "Icon payload is empty" }
            require(encoded.length <= MAX_ENCODED_LENGTH) { "Icon payload is too large" }
            // Validate bounds without allocating pixels. A malformed image rejects the whole
            // catalog before it can reach a notification callback.
            val bytes = decodeBase64(encoded)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth in 1..MAX_ICON_DIMENSION) {
                "Icon width must be between 1 and $MAX_ICON_DIMENSION pixels"
            }
            require(options.outHeight in 1..MAX_ICON_DIMENSION) {
                "Icon height must be between 1 and $MAX_ICON_DIMENSION pixels"
            }
            return IconAsset(bytes)
        }

        internal fun fromBytesForTest(bytes: ByteArray): IconAsset = IconAsset(bytes.copyOf())

        private fun decodeBase64(encoded: String): ByteArray = try {
            val compact = encoded.filterNot(Char::isWhitespace)
            Base64.getDecoder().decode(compact)
        } catch (exception: IllegalArgumentException) {
            throw IconDecodeException("Icon payload is not valid Base64", exception)
        }

        private const val MAX_ICON_DIMENSION = 512
        private const val MAX_ENCODED_LENGTH = 256 * 1024
    }
}

class IconDecodeException(
    message: String,
    cause: Exception? = null,
) : IllegalArgumentException(message, cause)

/** Definition supplied by the rule library, independent of user overrides. */
data class RuleDefinition(
    val appName: String,
    val packageName: String,
    val icon: IconAsset,
    val iconColor: Int,
    val contributorName: String,
    val enabledByDefault: Boolean,
    val enabledAllByDefault: Boolean,
)

/** A definition resolved with the current per-package overrides. */
data class IconRule(
    val definition: RuleDefinition,
    val isEnabled: Boolean = definition.enabledByDefault,
    val isEnabledAll: Boolean = definition.enabledAllByDefault,
) {
    val appName: String get() = definition.appName
    val packageName: String get() = definition.packageName
    val iconAsset: IconAsset get() = definition.icon
    val iconBitmap: Bitmap get() = definition.icon.bitmap
    val iconColor: Int get() = definition.iconColor
    val contributorName: String get() = definition.contributorName
}
