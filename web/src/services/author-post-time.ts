import { notificationDate } from './notification-time'
/** 只按新接口声明解释无偏移DATETIME，不改原时间排序值或旧接口。 */
export function formatAuthorPostTime(value: unknown, zone: 'UTC' | 'Asia/Shanghai' | null) {
  const date = notificationDate(value)
  if (!date || !zone || !['UTC', 'Asia/Shanghai'].includes(zone)) return '时间未确认'
  const instant = zone === 'UTC' ? new Date(date.getTime() + 8 * 60 * 60 * 1000) : date
  return instant.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
