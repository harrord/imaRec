package site.webbing.audiorec.segment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 步数传感器封装，基于 Sensor.TYPE_STEP_COUNTER（设备开机起算的累计步数）。
 *
 * 提供当前累计步数的读取，供 [site.webbing.audiorec.segment.conditions.StepCountStartCondition]
 * 计算步数变化。
 *
 * 注意：
 * - 需要 ACTIVITY_RECOGNITION 权限（API 29+），由调用方在录音前申请
 * - STEP_COUNTER 是设备级累计值，非 App 内步数；条件取"进入间隔期时的差值"
 * - 设备无步数传感器时 [isAvailable] 为 false，[currentSteps] 始终返回 -1，
 *   依赖该传感器的条件将无法满足
 */
class StepSensorProvider private constructor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepCounterSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val latestSteps = AtomicLong(-1L)

    /** 设备是否支持步数计数器。 */
    val isAvailable: Boolean get() = stepCounterSensor != null

    /** 当前累计步数；未收到数据或无传感器时返回 -1。 */
    fun currentSteps(): Long = latestSteps.get()

    /** 注册监听，开始接收步数更新。幂等。 */
    fun start() {
        val sensor = stepCounterSensor ?: run {
            Log.w(TAG, "Step counter sensor not available")
            return
        }
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /** 注销监听。幂等。 */
    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && values.isNotEmpty()) {
            latestSteps.set(values[0].toLong())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "StepSensorProvider"

        @Volatile
        private var instance: StepSensorProvider? = null

        fun get(context: Context): StepSensorProvider =
            instance ?: synchronized(this) {
                instance ?: StepSensorProvider(context.applicationContext).also { instance = it }
            }
    }
}
