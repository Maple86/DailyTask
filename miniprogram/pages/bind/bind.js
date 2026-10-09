const cloud = require('../../utils/cloud')

Page({
  data: {
    code: '',
    expiresAt: '',
    loading: false
  },

  generateCode() {
    this.setData({ loading: true })
    cloud.call('mpCreatePairingCode')
      .then(data => {
        this.setData({
          code: data.code,
          expiresAt: data.expiresAt || ''
        })
      })
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  },

  copyCode() {
    if (!this.data.code) return
    wx.setClipboardData({ data: this.data.code })
  }
})
