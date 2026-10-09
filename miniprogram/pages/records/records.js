const cloud = require('../../utils/cloud')

Page({
  data: {
    records: [],
    date: '',
    loading: false
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
    this.setData({ loading: true })
    return cloud.call('mpGetRecords', {
      deviceId: this.deviceId,
      date: this.data.date
    }).then(data => this.setData({ records: data.records || [] }))
      .catch(cloud.showError)
      .finally(() => this.setData({ loading: false }))
  },

  onDateChange(event) {
    this.setData({ date: event.detail.value }, () => this.load())
  },

  clearDate() {
    this.setData({ date: '' }, () => this.load())
  }
})
