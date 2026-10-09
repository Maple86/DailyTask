const config = require('./config')

App({
  globalData: {
    selectedDeviceId: ''
  },

  onLaunch() {
    if (!wx.cloud) {
      wx.showModal({ title: '提示', content: '当前基础库不支持云开发，请升级微信。', showCancel: false })
      return
    }
    const options = { traceUser: true }
    if (config.cloudEnv && !config.cloudEnv.includes('请填写')) {
      options.env = config.cloudEnv
    }
    wx.cloud.init(options)

    wx.cloud.callFunction({
      name: config.relayFunction,
      data: { action: 'initCollections' }
    }).catch(() => {
      // 首次尚未部署云函数时忽略；部署后可在控制台手动初始化。
    })
  }
})
