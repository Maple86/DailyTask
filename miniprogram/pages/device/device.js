const cloud = require('../../utils/cloud')

Page({
  data: {
    device: null,
    config: {},
    configRevision: 0,
    attendance: [],
    commands: [],
    loading: false,
    commandLoading: false
  },

  onLoad(options) {
    this.deviceId = options.deviceId
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  load() {
    if (!this.deviceId) return Promise.resolve()
    this.setData({ loading: true })
    return cloud.call('mpGetDevice', { deviceId: this.deviceId })
      .then(data => {
        this.setData({
          device: data.device || null,
          config: data.config && data.config.payload ? data.config.payload : {},
          configRevision: data.config ? data.config.revision || 0 : 0,
          attendance: data.attendance || [],
          commands: data.commands || []
        })
      })
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  },

  createCommand(event) {
    const type = event.currentTarget.dataset.type
    const labels = {
      START_TASK: '启动任务',
      STOP_TASK: '停止任务',
      TRIGGER_CLOCK_IN: '立即打卡'
    }
    wx.showModal({
      title: labels[type] || '发送指令',
      content: '指令将在设备下次同步时执行，是否继续？',
      success: result => {
        if (!result.confirm) return
        this.setData({ commandLoading: true })
        cloud.call('mpCreateCommand', {
          deviceId: this.deviceId,
          type,
          idempotencyKey: `${type}_${Date.now()}_${Math.random()}`
        }).then(() => {
          wx.showToast({ title: '指令已创建', icon: 'success' })
          this.load()
        }).catch(cloud.showError).finally(() => this.setData({ commandLoading: false }))
      }
    })
  },

  toggleLoop() {
    const enabled = !this.data.config.autoRecycle
    cloud.call('mpUpdateConfig', {
      deviceId: this.deviceId,
      config: { autoRecycle: enabled }
    }).then(() => {
      wx.showToast({ title: enabled ? '已开启循环' : '已关闭循环', icon: 'success' })
      this.load()
    }).catch(cloud.showError)
  },

  openRecords() {
    wx.navigateTo({ url: `/pages/records/records?deviceId=${this.deviceId}` })
  },

  openConfig() {
    wx.navigateTo({ url: `/pages/config/config?deviceId=${this.deviceId}` })
  },

  unbindDevice() {
    wx.showModal({
      title: '解除绑定',
      content: '解绑后 Android 设备将无法再访问云端指令，是否继续？',
      success: result => {
        if (!result.confirm) return
        cloud.call('mpUnbindDevice', { deviceId: this.deviceId })
          .then(() => {
            wx.showToast({ title: '已解绑', icon: 'success' })
            setTimeout(() => wx.navigateBack(), 800)
          })
          .catch(cloud.showError)
      }
    })
  }
})

