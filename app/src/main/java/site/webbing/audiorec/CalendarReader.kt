package site.webbing.audiorec

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/**
 * 日历日程条目（从系统日历读取的精简结构）。
 *
 * @param id 事件 _ID，用于去重
 * @param title 事件标题（TITLE 列），可能为空
 * @param description 事件描述（DESCRIPTION 列），可能为空
 * @param dtStart 事件开始时间（DTSTART 列，Unix 毫秒）
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val dtStart: Long,
)

/**
 * 日历读取与匹配。
 *
 * 不查 CREATED 列（部分 ROM 不支持，会抛 IllegalArgumentException: Invalid column created），
 * 只查 _ID / TITLE / DESCRIPTION / DTSTART 四列（所有 ROM 都支持）。
 * selection 只按 DTSTART 过滤未来 7 天内的日程。
 *
 * "最近 5 分钟内创建"这个条件不再用 CREATED 列，改为靠 [CalendarCapsuleSettings.processedEventIds]
 * 判重 + 扫描间隔 ≤ 5 分钟保证不漏不重。
 */
object CalendarReader {
    private const val TAG = "CalendarReader"
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 查询未来 7 天内的日历日程。
     *
     * projection 只查 _ID / TITLE / DESCRIPTION / DTSTART 四列，
     * selection 只按 DTSTART >= now AND DTSTART <= now+7d 过滤，
     * 不使用 CREATED 列。
     *
     * @return 查询到的日程列表；无 READ_CALENDAR 权限或查询失败时返回空列表
     */
    fun queryUpcomingEvents(context: Context): List<CalendarEvent> {
        if (!hasReadCalendarPermission(context)) {
            Log.w(TAG, "queryUpcomingEvents: no READ_CALENDAR permission")
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val sevenDaysLater = now + SEVEN_DAYS_MS
        val resolver: ContentResolver = context.contentResolver
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
        )
        val selection =
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), sevenDaysLater.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val result = mutableListOf<CalendarEvent>()
        runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                val dtStartIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    val title = cursor.getString(titleIdx) ?: ""
                    val description = cursor.getString(descIdx) ?: ""
                    val dtStart = cursor.getLong(dtStartIdx)
                    if (dtStart <= 0) continue
                    result.add(CalendarEvent(id, title, description, dtStart))
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "queryUpcomingEvents failed", e)
        }
        Log.d(TAG, "queryUpcomingEvents: found ${result.size} events in next 7 days")
        return result
    }

    /**
     * 从日程列表中筛出匹配的日程（条件 B + 条件 C）。
     *
     * - 条件 B：DTSTART 的本地小时 = [anchorHour]
     * - 条件 C：事件 ID 不在 [processedEventIds] 中（去重）
     *
     * 条件 A（创建时间在最近 5 分钟内）由扫描间隔 ≤ 5 分钟 + 判重机制隐式保证：
     * 已处理的事件在 processedEventIds 中，新创建的事件不在其中，会被匹配到。
     *
     * @return 匹配的日程列表
     */
    fun filterMatching(
        events: List<CalendarEvent>,
        anchorHour: Int,
        processedEventIds: Map<String, Long>,
    ): List<CalendarEvent> {
        val local = TimeZone.getDefault()
        return events.filter { event ->
            // 条件 C：去重
            if (processedEventIds.containsKey(event.id)) {
                Log.d(TAG, "skip event ${event.id}: already processed")
                return@filter false
            }
            // 条件 B：DTSTART 本地小时 = 锚点小时
            val cal = Calendar.getInstance(local)
            cal.timeInMillis = event.dtStart
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour != anchorHour) {
                Log.d(TAG, "skip event ${event.id}: hour=$hour != anchor=$anchorHour")
                return@filter false
            }
            Log.d(TAG, "match event ${event.id}: hour=$hour title=\"${event.title}\"")
            true
        }
    }

    /** 检查是否已授予 READ_CALENDAR 权限。 */
    fun hasReadCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
}
