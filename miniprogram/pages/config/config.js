const cloud = require('../../utils/cloud')

const weekdayOptions = [
  { label: '周一', value: 1 },
  { label: '周二', value: 2 },
  { label: '周三', value: 3 },
  { label: '周四', value: 4 },
  { label: '周五', value: 5 },
  { label: '周六', value: 6 },
  { label: '周日', value: 7 }
]
const targetApps = ['钉钉', '企业微信', '飞书', '移动办公M3']
const resetHours = Array.from({ length: 24 }, (_, index) => `${String(index).padStart(2, '0')}:00`)
const workdayModes = ['每周固定', '大小周']
const weekTypes = ['本周小周', '本周大周']

function parseNumberSet(raw) {
  return String(raw || '').split(',').map(value => Number(value)).filter(value => value >= 1 && value <= 7)
}

function currentMonday() {
  const date = new Date()
  const day = date.getDay() || 7
  date.setHours(0, 0, 0, 0)
  date.setDate(date.getDate() - day + 1)
  return date
}

function formatDate(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

Page({
  data: {
    weekdayOptions,
    targetApps,
    resetHours,
    workdayModes,
    weekTypes,
    loading: false,
    revision: 0,
    resetHourIndex: 0,
    targetAppIndex: 0,
    autoRecycle: true,
    randomTime: true,
    skipHoliday: true,
    powerSave: false,
    workdayModeIndex: 0,
    weekTypeIndex: 0,
    selectedWeekdays: [1, 2, 3, 4, 5],
    tasksText: ''
  },

  onLoad(options) {
    this.deviceId = options.deviceId
    this.load()
  },

  load() {
    this.setData({ loading: true })
    return cloud.call('mpGetDevice', { deviceId: this.deviceId })
      .then(data => {
        const payload = data.config && data.config.payload ? data.config.payload : {}
        const resetHour = Number(payload.resetTime || 0)
        const parsedWeekdays = parseNumberSet(payload.fixedWorkdays)
        const selectedWeekdays = parsedWeekdays.length ? parsedWeekdays : [1, 2, 3, 4, 5]
        const tasks = Array.isArray(payload.tasks)
          ? payload.tasks.map(task => task.time).join('\n')
          : ''
        const weekdayOptionsWithState = weekdayOptions.map(item => Object.assign({}, item, {
          checked: selectedWeekdays.includes(item.value)
        }))
        this.setData({
          weekdayOptions: weekdayOptionsWithState,
          revision: data.config ? data.config.revision || 0 : 0,
          resetHourIndex: Math.max(0, Math.min(23, resetHour)),
          targetAppIndex: Math.max(0, Math.min(3, Number(payload.targetApp || 0))),
          autoRecycle: payload.autoRecycle !== false,
          randomTime: payload.randomTime !== false,
          skipHoliday: payload.skipHoliday !== false,
          powerSave: payload.powerSave === true,
          workdayModeIndex: payload.workdayMode === 'ALTERNATING' ? 1 : 0,
          selectedWeekdays,
          tasksText: tasks
        })
      })
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  },

  onResetHourChange(event) {
    this.setData({ resetHourIndex: Number(event.detail.value) })
  },

  onTargetAppChange(event) {
    this.setData({ targetAppIndex: Number(event.detail.value) })
  },

  onWorkdayModeChange(event) {
    this.setData({ workdayModeIndex: Number(event.detail.value) })
  },

  onWeekTypeChange(event) {
    this.setData({ weekTypeIndex: Number(event.detail.value) })
  },

  onAutoRecycleChange(event) {
    this.setData({ autoRecycle: event.detail.value })
  },

  onRandomTimeChange(event) {
    this.setData({ randomTime: event.detail.value })
  },

  onSkipHolidayChange(event) {
    this.setData({ skipHoliday: event.detail.value })
  },

  onPowerSaveChange(event) {
    this.setData({ powerSave: event.detail.value })
  },

  onWeekdayChange(event) {
    const selectedWeekdays = event.detail.value.map(Number)
    this.setData({
      selectedWeekdays,
      weekdayOptions: weekdayOptions.map(item => Object.assign({}, item, {
        checked: selectedWeekdays.includes(item.value)
      }))
    })
  },

  onTasksInput(event) {
    this.setData({ tasksText: event.detail.value })
  },

  save() {
    const tasks = String(this.data.tasksText || '')
      .split(/[\n,，;；\s]+/)
      .map(value => value.trim())
      .filter(Boolean)
    const valid = tasks.every(time => /^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$/.test(time))
    if (!valid) {
      wx.showToast({ title: '任务时间格式应为 HH:mm:ss', icon: 'none' })
      return
    }
    if (!this.data.selectedWeekdays.length) {
      wx.showToast({ title: '至少选择一天工作日', icon: 'none' })
      return
    }

    const alternating = this.data.workdayModeIndex === 1
    const fixedWorkdays = this.data.selectedWeekdays.slice().sort((a, b) => a - b).join(',')
    const bigSet = this.data.selectedWeekdays.slice()
    if (!bigSet.includes(6)) bigSet.push(6)
    const anchor = currentMonday()
    if (alternating && this.data.weekTypeIndex === 1) {
      anchor.setDate(anchor.getDate() - 7)
    }

    const config = {
      resetTime: this.data.resetHourIndex,
      targetApp: this.data.targetAppIndex,
      autoRecycle: this.data.autoRecycle,
      randomTime: this.data.randomTime,
      skipHoliday: this.data.skipHoliday,
      powerSave: this.data.powerSave,
      workdayMode: alternating ? 'ALTERNATING' : 'FIXED',
      fixedWorkdays,
      smallWeekWorkdays: fixedWorkdays,
      bigWeekWorkdays: bigSet.sort((a, b) => a - b).join(','),
      bigWeekAnchorMonday: formatDate(anchor),
      tasks: tasks.map(time => ({ time }))
    }

    this.setData({ loading: true })
    cloud.call('mpUpdateConfig', { deviceId: this.deviceId, config })
      .then(() => {
        wx.showToast({ title: '配置已提交', icon: 'success' })
        this.load()
      })
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  }
})
