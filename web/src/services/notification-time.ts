/** 后端LocalDateTime以Asia/Shanghai解释，不依赖浏览器所在时区。 */
export function notificationDate(value: unknown): Date | null {
  if (typeof value !== 'string') return null
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/.exec(value)
  if (!match) return null
  const [, year, month, day, hour, minute, second, fraction] = match
  const result = new Date(
    `${year}-${month}-${day}T${hour}:${minute}:${second}.${(fraction || '').padEnd(3, '0').slice(0, 3)}+08:00`,
  )
  if (!Number.isFinite(result.getTime())) return null
  // Date可能归一化2月30日等非法日期；不让这种响应成为已读事实。
  const shanghai = new Date(result.getTime() + 8 * 60 * 60 * 1000)
  if (
    shanghai.getUTCFullYear() !== Number(year) ||
    shanghai.getUTCMonth() + 1 !== Number(month) ||
    shanghai.getUTCDate() !== Number(day) ||
    shanghai.getUTCHours() !== Number(hour) ||
    shanghai.getUTCMinutes() !== Number(minute) ||
    shanghai.getUTCSeconds() !== Number(second)
  )
    return null
  return result
}

/** 无效时间明确占位，不展示浏览器误判的本地时间。 */
export function formatNotificationTime(value: unknown) {
  return notificationDate(value)?.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) || '时间未确认'
}
