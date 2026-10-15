/** 隐藏详情页仍属于其导航分区，不能将公开主页/房间显示成无选中项。 */
export function navigationSection(name: unknown) {
  return name === 'voice-room' ? 'voice' : name === 'public-creator' ? 'creators' : name
}
