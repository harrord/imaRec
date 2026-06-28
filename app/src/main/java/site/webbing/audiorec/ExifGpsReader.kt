package site.webbing.audiorec

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * 从照片 EXIF 读取 GPS 坐标。
 *
 * 国产 ROM 的文件选择器返回的 URI 可能是 MediaStore URI
 * （content://media/external/...）而非纯 document URI
 * （content://com.android.providers.media.documents/...）。
 * Android 10+ 对 MediaStore URI 默认会做位置脱敏，EXIF 的 GPS 字段会被擦除。
 * 要拿到带 GPS 的原图必须同时满足：
 * 1. Manifest 声明 ACCESS_MEDIA_LOCATION 权限
 * 2. 代码对 MediaStore URI 调用 MediaStore.setRequireOriginal(uri)
 *
 * 这里完全对齐 focus_mode_app 的 GpsUtils.extractGpsFromPhoto 实现。
 */
object ExifGpsReader {
    private const val TAG = "ExifGpsReader"

    /**
     * 从 [context] 的 [photoUri] 读取照片 EXIF GPS 坐标。
     *
     * @return 经纬度（[Double] 数组，[0]=纬度 [1]=经度）；无 GPS 信息或读取失败时返回 null
     */
    fun readGps(context: Context, photoUri: Uri): DoubleArray? {
        val uriString = photoUri.toString()

        // 判断 URI 类型：Photo Picker URI 与 Document URI 都已拥有完整访问权限，
        // 不需要 setRequireOriginal；只有 MediaStore URI（Android 10+）需要。
        val isPhotoPickerUri = uriString.contains("content://media/picker/")
        val isDocumentUri = uriString.contains("content://com.android.providers.media.documents/")
        val needsRequireOriginal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                   !isPhotoPickerUri &&
                                   !isDocumentUri

        val resolvedUri = if (needsRequireOriginal) {
            try {
                MediaStore.setRequireOriginal(photoUri)
            } catch (e: Exception) {
                Log.w(TAG, "setRequireOriginal failed, using original uri", e)
                photoUri
            }
        } else {
            photoUri
        }

        return runCatching {
            context.contentResolver.openInputStream(resolvedUri)?.use { input ->
                val exif = ExifInterface(input)
                val latLong = exif.latLong
                if (latLong == null) {
                    Log.d(TAG, "photo has no GPS exif: $photoUri")
                    return null
                }
                doubleArrayOf(latLong[0], latLong[1])
            }
        }.getOrElse { e ->
            Log.e(TAG, "read exif gps failed", e)
            null
        }
    }
}
