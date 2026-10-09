const cloud = require('../../utils/cloud')

Page({
  data: {
    devices: [],
    loading: false
  },

  onShow() {
    this.loadDevices()
  },

  onPullDownRefresh() {
    this.loadDevices().finally(() => wx.stopPullDownRefresh())
  },

  loadDevices() {
    this.setData({ loading: true })
    return cloud.call('mpListDevices')
      .then(data => this.setData({ devices: data.devices || [] }))
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  },

  openDevice(event) {
    const deviceId = event.currentTarget.dataset.deviceId
    wx.navigateTo({ url: `/pages/device/device?deviceId=${deviceId}` })
  },

  addDevice() {
    wx.navigateTo({ url: '/pages/bind/bind' })
  }
})
