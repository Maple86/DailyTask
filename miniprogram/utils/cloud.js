const config = require('../config')

function call(action, data) {
  const payload = Object.assign({ action }, data || {})
  return wx.cloud.callFunction({
    name: config.relayFunction,
    data: payload
  }).then(res => {
    const result = res.result || {}
    if (!result.ok) {
      const message = result.error && result.error.message ? result.error.message : '请求失败'
      throw new Error(message)
    }
    return result.data || {}
  })
}

function showError(error) {
  wx.showToast({
    title: error && error.message ? error.message : '操作失败',
    icon: 'none',
    duration: 2500
  })
}

module.exports = { call, showError }
