package site.webbing.audiorec

import androidx.annotation.RequiresApi
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.coroutines.resume

/**
 * 原生 LocationManager 单次定位工具。
 *
 * 复刻自 focus_mode_app 的 requestFreshLocation，弃用 Google Play 的
 * FusedLocationProviderClient（国产 ROM 普遍缺失 GMS，会导致回调不触发、永久挂起）。
 *
 * 工作方式：
 * - API 30+：并行请求 GPS + Network provider，先返回者用之
 * - 5 秒后仍无结果时用 getLastKnownLocation 缓存兜底
 * - 最多等待 10 秒（[withTimeoutOrNull]），超时返回 null
 * - 主动检查 [LocationManager.isProviderEnabled]，只请求已启用的 provider
 *
 * 调用方需已持有 ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION 权限。
 */
object LocationHelper {
    private const val TAG = "LocationHelper"
    /** 诊断日志统一 TAG，与 LocationTriggerService 一致，方便一并过滤。 */
    private const val DEBUG_TAG = "GeoTriggerDebug"
    private const val TIMEOUT_MS = 10_000L
    private const val FALLBACK_DELAY_MS = 5_000L
    /** 缓存位置可接受的最大时效（2 分钟），超过视为太旧不可用。 */
    private const val MAX_CACHE_AGE_MS = 2 * 60 * 1000L

    /**
     * 请求一次当前位置。失败、超时或无可用 provider 时返回 null。
     *
     * 在 [Dispatchers.IO] 上执行，调用方可直接在主线程协程中调用。
     */
    suspend fun requestFreshLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        Log.i(DEBUG_TAG, "requestFreshLocation: start, apiLevel=${Build.VERSION.SDK_INT}")
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE)
                    as? LocationManager
                    ?: run {
                        Log.e(DEBUG_TAG, "requestFreshLocation: LocationManager unavailable")
                        return@withTimeoutOrNull null
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestParallel(locationManager, ContextCompat.getMainExecutor(context))
                } else {
                    // API < 30：直接用缓存位置（getCurrentLocation 需 API 30+）
                    try {
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    } catch (e: SecurityException) {
                        Log.e(DEBUG_TAG, "requestFreshLocation: permission missing", e)
                        null
                    }?.takeIf { isFresh(it.time) }
                }
            } catch (e: SecurityException) {
                Log.e(DEBUG_TAG, "requestFreshLocation: permission missing", e)
                null
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "requestFreshLocation: error", e)
                null
            }
        }
        if (result == null) {
            Log.w(DEBUG_TAG, "requestFreshLocation: returned null (timeout or no location)")
        } else {
            Log.i(
                DEBUG_TAG,
                "requestFreshLocation: returned lat=${result.latitude}, lng=${result.longitude}, " +
                    "accuracy=${result.accuracy}m, age=${System.currentTimeMillis() - result.time}ms, " +
                    "provider=${result.provider}",
            )
        }
        result
    }

    /**
     * API 30+：并行请求 GPS 和 Network provider，先返回者用之。
     * 5 秒后仍无结果时用缓存兜底。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestParallel(
        locationManager: LocationManager,
        mainExecutor: Executor,
    ): Location? = suspendCancellableCoroutine { cont ->
        val cancellationSignal = CancellationSignal()
        cont.invokeOnCancellation { cancellationSignal.cancel() }

        var resumed = false

        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        val isNetworkEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
        Log.i(DEBUG_TAG, "requestParallel: GPS enabled=$isGpsEnabled, Network enabled=$isNetworkEnabled")

        if (isGpsEnabled) {
            try {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    cancellationSignal,
                    mainExecutor,
                    Consumer { gpsLocation ->
                        if (!resumed && gpsLocation != null) {
                            resumed = true
                            Log.i(DEBUG_TAG, "requestParallel: GPS returned accuracy=${gpsLocation.accuracy}m")
                            if (cont.isActive) cont.resume(gpsLocation)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(DEBUG_TAG, "requestParallel: GPS request failed: ${e.message}")
            }
        }

        if (isNetworkEnabled) {
            try {
                locationManager.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    cancellationSignal,
                    mainExecutor,
                    Consumer { netLocation ->
                        if (!resumed && netLocation != null) {
                            resumed = true
                            Log.i(DEBUG_TAG, "requestParallel: Network returned accuracy=${netLocation.accuracy}m")
                            if (cont.isActive) cont.resume(netLocation)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(DEBUG_TAG, "requestParallel: Network request failed: ${e.message}")
            }
        }

        // 两个 provider 都不可用：立即返回 null
        if (!isGpsEnabled && !isNetworkEnabled) {
            resumed = true
            Log.w(DEBUG_TAG, "requestParallel: no provider enabled, returning null")
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 5 秒后若仍无实时结果，尝试缓存兜底（拒绝过期位置）
        GlobalScope.launch {
            delay(FALLBACK_DELAY_MS)
            if (!resumed) {
                Log.i(DEBUG_TAG, "requestParallel: 5s elapsed, trying cached location fallback")
                val lastKnown = try {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (e: SecurityException) {
                    null
                }?.takeIf { isFresh(it.time) }
                if (lastKnown != null) {
                    resumed = true
                    Log.i(DEBUG_TAG, "requestParallel: using cached location, age=${System.currentTimeMillis() - lastKnown.time}ms")
                    if (cont.isActive) cont.resume(lastKnown)
                } else if (!resumed) {
                    resumed = true
                    Log.w(DEBUG_TAG, "requestParallel: no fresh cached location, returning null")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** 判断位置时间戳是否在可接受的时效内。 */
    private fun isFresh(locationTimeMs: Long): Boolean =
        System.currentTimeMillis() - locationTimeMs <= MAX_CACHE_AGE_MS
}
