const cloud = require('wx-server-sdk')
const crypto = require('crypto')

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV })
const db = cloud.database()
const _ = db.command
const COLLECTIONS = {
  users: 'users',
  devices: 'devices',
  pairingCodes: 'pairing_codes',
  configs: 'device_configs',
  commands: 'commands',
  attendance: 'attendance_records',
  audit: 'audit_logs'
}
const COMMAND_TYPES = new Set([
  'START_TASK',
  'STOP_TASK',
  'TRIGGER_CLOCK_IN',
  'QUERY_STATUS'
])
const CONFIG_FIELDS = new Set([
  'resetTime',
  'overtime',
  'timeRange',
  'targetApp',
  'autoRecycle',
  'randomTime',
  'skipHoliday',
  'powerSave',
  'workdayMode',
  'fixedWorkdays',
  'smallWeekWorkdays',
  'bigWeekWorkdays',
  'bigWeekAnchorMonday',
  'tasks'
])

function ok(data) {
  return { ok: true, data: data || {}, serverTime: new Date().toISOString() }
}

function fail(code, message) {
  return {
    ok: false,
    error: { code: code || 'UNKNOWN_ERROR', message: message || '请求失败' },
    serverTime: new Date().toISOString()
  }
}

function parseEvent(event) {
  let payload = Object.assign({}, event || {})
  const rawBody = event && event.body
  if (rawBody) {
    try {
      const bodyText = event.isBase64Encoded
        ? Buffer.from(rawBody, 'base64').toString('utf8')
        : rawBody
      const parsed = typeof bodyText === 'string' ? JSON.parse(bodyText) : bodyText
      payload = Object.assign(payload, parsed || {})
    } catch (error) {
      payload.bodyParseError = error.message
    }
  }
  if (event && event.queryStringParameters) {
    payload = Object.assign(payload, event.queryStringParameters)
  }
  return payload
}

function getOpenId() {
  const context = cloud.getWXContext()
  return context.OPENID || ''
}

function getToken(event) {
  const headers = event.headers || {}
  const authorization = headers.authorization || headers.Authorization || ''
  if (authorization.toLowerCase().startsWith('bearer ')) {
    return authorization.substring(7).trim()
  }
  return event.deviceToken || event.token || ''
}

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex')
}

function randomToken() {
  return crypto.randomBytes(32).toString('hex')
}

function normalizeDate(value) {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function iso(value) {
  const date = normalizeDate(value)
  return date ? date.toISOString() : null
}

function sanitizeDevice(device) {
  if (!device) return null
  const copy = Object.assign({}, device)
  delete copy.tokenHash
  return copy
}

async function findDeviceByToken(event) {
  const token = getToken(event)
  if (!token) throw new Error('DEVICE_TOKEN_MISSING|缺少设备 Token')
  const result = await db.collection(COLLECTIONS.devices).where({
    tokenHash: hashToken(token),
    status: 'ACTIVE'
  }).limit(1).get()
  if (!result.data.length) throw new Error('DEVICE_TOKEN_INVALID|设备 Token 无效')
  return result.data[0]
}

function assertOwner(device, openid) {
  if (!openid || device.ownerOpenId !== openid) {
    throw new Error('DEVICE_FORBIDDEN|无权操作该设备')
  }
}

async function findDeviceById(deviceId, openid) {
  if (!deviceId) throw new Error('DEVICE_ID_MISSING|缺少设备 ID')
  const result = await db.collection(COLLECTIONS.devices).where({
    deviceId,
    status: 'ACTIVE'
  }).limit(1).get()
  const device = result.data[0]
  if (!device) {
    throw new Error('DEVICE_NOT_FOUND|设备不存在或已停用')
  }
  assertOwner(device, openid)
  return device
}

async function getLatestConfig(deviceId) {
  const result = await db.collection(COLLECTIONS.configs)
    .where({ deviceId })
    .orderBy('revision', 'desc')
    .limit(1)
    .get()
  return result.data[0] || null
}

async function pairDevice(event) {
  const code = String(event.pairingCode || '').trim()
  if (!/^\d{6}$/.test(code)) throw new Error('PAIRING_CODE_INVALID|配对码格式错误')

  const result = await db.collection(COLLECTIONS.pairingCodes)
    .where({ code, used: false })
    .orderBy('createdAt', 'desc')
    .limit(1)
    .get()
  const pairing = result.data[0]
  if (!pairing) throw new Error('PAIRING_CODE_NOT_FOUND|配对码不存在或已使用')
  if (!pairing.expiresAt || new Date(pairing.expiresAt).getTime() < Date.now()) {
    throw new Error('PAIRING_CODE_EXPIRED|配对码已过期')
  }

  const deviceToken = randomToken()
  const deviceId = `dt_${crypto.randomBytes(8).toString('hex')}`
  const now = new Date()
  const device = {
    ownerOpenId: pairing.ownerOpenId,
    deviceId,
    deviceName: event.deviceName || event.deviceModel || 'Android 设备',
    deviceModel: event.deviceModel || '',
    androidVersion: Number(event.androidVersion || 0),
    appVersion: event.appVersion || '',
    tokenHash: hashToken(deviceToken),
    tokenVersion: 1,
    lastSeenAt: now,
    battery: -1,
    networkType: 'UNKNOWN',
    taskRunning: false,
    notificationListenerAlive: false,
    projectionAlive: false,
    appliedConfigRevision: 1,
    status: 'ACTIVE',
    createdAt: now,
    updatedAt: now
  }
  const addDevice = await db.collection(COLLECTIONS.devices).add({ data: device })
  const initialConfig = event.initialConfig && typeof event.initialConfig === 'object'
    ? event.initialConfig
    : {}
  await db.collection(COLLECTIONS.configs).add({
    data: {
      deviceId: addDevice._id,
      revision: 1,
      payload: initialConfig,
      createdBy: pairing.ownerOpenId,
      createdAt: now
    }
  })
  await db.collection(COLLECTIONS.pairingCodes).doc(pairing._id).update({
    data: { used: true, usedDeviceId: addDevice._id, usedAt: now }
  })
  await db.collection(COLLECTIONS.audit).add({
    data: {
      actorOpenId: pairing.ownerOpenId,
      deviceId: addDevice._id,
      action: 'PAIR_DEVICE',
      payload: { deviceName: device.deviceName },
      createdAt: now
    }
  })

  return ok({ deviceId, deviceToken, serverTime: now.toISOString() })
}

async function deviceSync(event) {
  const device = await findDeviceByToken(event)
  const now = new Date()
  const appliedConfigRevision = Number(event.appliedConfigRevision || 0)
  const latestConfig = await getLatestConfig(device._id)
  const configChanged = latestConfig && Number(latestConfig.revision) > appliedConfigRevision

  await db.collection(COLLECTIONS.devices).doc(device._id).update({
    data: {
      deviceName: event.deviceName || device.deviceName,
      appVersion: event.appVersion || device.appVersion,
      androidVersion: Number(event.androidVersion || device.androidVersion || 0),
      battery: Number(event.battery == null ? -1 : event.battery),
      networkType: event.networkType || 'UNKNOWN',
      taskRunning: Boolean(event.taskRunning),
      notificationListenerAlive: Boolean(event.notificationListenerAlive),
      projectionAlive: Boolean(event.projectionAlive),
      appliedConfigRevision: Number(appliedConfigRevision || 0),
      lastSeenAt: now,
      updatedAt: now
    }
  })

  const commands = await db.collection(COLLECTIONS.commands).where({
    deviceId: device._id,
    status: 'PENDING',
    expiresAt: _.gt(now)
  }).orderBy('createdAt', 'asc').limit(20).get()

  return ok({
    serverTime: now.toISOString(),
    nextPollAfterSeconds: 60,
    configRevision: latestConfig ? Number(latestConfig.revision) : 0,
    config: configChanged ? latestConfig.payload : null,
    commands: commands.data.map(command => ({
      id: command._id,
      seq: command.seq,
      type: command.type,
      payload: command.payload || {},
      expiresAt: iso(command.expiresAt),
      createdAt: iso(command.createdAt)
    }))
  })
}

async function deviceAck(event) {
  const device = await findDeviceByToken(event)
  const commandId = event.commandId
  const status = String(event.status || '').toUpperCase()
  if (!commandId) throw new Error('COMMAND_ID_MISSING|缺少指令 ID')
  if (!['SUCCEEDED', 'FAILED', 'RETRY'].includes(status)) {
    throw new Error('COMMAND_STATUS_INVALID|指令状态不合法')
  }
  const result = await db.collection(COLLECTIONS.commands).doc(commandId).get()
  const command = result.data
  if (!command || command.deviceId !== device._id) {
    throw new Error('COMMAND_NOT_FOUND|指令不存在')
  }
  await db.collection(COLLECTIONS.commands).doc(commandId).update({
    data: {
      status: status === 'RETRY' ? 'PENDING' : status,
      result: event.message || '',
      completedAt: status === 'RETRY' ? null : new Date(),
      updatedAt: new Date()
    }
  })
  return ok({ commandId, status })
}

async function deviceAttendance(event) {
  const device = await findDeviceByToken(event)
  const records = Array.isArray(event.records) ? event.records.slice(0, 50) : []
  let accepted = 0
  let duplicated = 0
  const now = new Date()

  for (const record of records) {
    if (!record || typeof record !== 'object') continue
    const dedupeKey = record.dedupeKey || crypto.createHash('sha256').update(JSON.stringify({
      deviceId: device._id,
      packageName: record.targetPackage,
      occurredAt: record.occurredAt,
      message: record.message
    })).digest('hex')
    const existing = await db.collection(COLLECTIONS.attendance).where({
      deviceId: device._id,
      dedupeKey
    }).limit(1).get()
    if (existing.data.length) {
      duplicated += 1
      continue
    }
    await db.collection(COLLECTIONS.attendance).add({
      data: {
        deviceId: device._id,
        businessDate: record.businessDate || new Date().toISOString().substring(0, 10),
        targetPackage: record.targetPackage || '',
        result: record.result || 'UNKNOWN',
        recordType: record.recordType || 'AUTO',
        occurredAt: record.occurredAt || now.toISOString(),
        title: record.title || '',
        message: record.message || '',
        screenshotFileId: record.screenshotFileId || '',
        dedupeKey,
        createdAt: now
      }
    })
    accepted += 1
  }

  return ok({ accepted, duplicated })
}

async function mpCreatePairingCode(event) {
  const openid = getOpenId()
  if (!openid) throw new Error('OPENID_MISSING|无法识别微信用户')
  let code = ''
  for (let i = 0; i < 8; i += 1) {
    code = String(Math.floor(100000 + Math.random() * 900000))
    const existing = await db.collection(COLLECTIONS.pairingCodes).where({
      code,
      used: false,
      expiresAt: _.gt(new Date())
    }).limit(1).get()
    if (!existing.data.length) break
    code = ''
  }
  if (!code) throw new Error('PAIRING_CODE_CREATE_FAILED|生成配对码失败，请重试')
  const now = new Date()
  const expiresAt = new Date(now.getTime() + 10 * 60 * 1000)
  await db.collection(COLLECTIONS.pairingCodes).add({
    data: { code, ownerOpenId: openid, used: false, createdAt: now, expiresAt }
  })
  return ok({ code, expiresAt: expiresAt.toISOString() })
}

async function mpListDevices() {
  const openid = getOpenId()
  if (!openid) throw new Error('OPENID_MISSING|无法识别微信用户')
  const result = await db.collection(COLLECTIONS.devices)
    .where({ ownerOpenId: openid, status: 'ACTIVE' })
    .orderBy('updatedAt', 'desc')
    .get()
  return ok({ devices: result.data.map(sanitizeDevice) })
}

async function mpGetDevice(event) {
  const openid = getOpenId()
  const device = await findDeviceById(event.deviceId, openid)
  const records = await db.collection(COLLECTIONS.attendance).where({
    deviceId: device._id
  }).orderBy('occurredAt', 'desc').limit(20).get()
  const commands = await db.collection(COLLECTIONS.commands).where({
    deviceId: device._id
  }).orderBy('createdAt', 'desc').limit(10).get()
  const config = await getLatestConfig(device._id)
  return ok({
    device: sanitizeDevice(device),
    attendance: records.data,
    commands: commands.data,
    config: config ? { revision: config.revision, payload: config.payload } : null
  })
}

async function mpCreateCommand(event) {
  const openid = getOpenId()
  const device = await findDeviceById(event.deviceId, openid)
  const type = String(event.type || '').toUpperCase()
  if (!COMMAND_TYPES.has(type)) throw new Error('COMMAND_TYPE_INVALID|不支持的指令类型')

  const idempotencyKey = event.idempotencyKey || `mp_${Date.now()}_${Math.random()}`
  const existing = await db.collection(COLLECTIONS.commands).where({
    deviceId: device._id,
    idempotencyKey
  }).limit(1).get()
  if (existing.data.length) return ok({ commandId: existing.data[0]._id, duplicated: true })

  const now = new Date()
  const ttlMs = ['TRIGGER_CLOCK_IN', 'SCREENSHOT'].includes(type)
    ? 10 * 60 * 1000
    : 60 * 60 * 1000
  const addResult = await db.collection(COLLECTIONS.commands).add({
    data: {
      deviceId: device._id,
      seq: Date.now(),
      type,
      payload: event.payload || {},
      status: 'PENDING',
      createdBy: openid,
      idempotencyKey,
      createdAt: now,
      expiresAt: new Date(now.getTime() + ttlMs),
      updatedAt: now
    }
  })
  await db.collection(COLLECTIONS.audit).add({
    data: {
      actorOpenId: openid,
      deviceId: device._id,
      action: 'CREATE_COMMAND',
      payload: { type, commandId: addResult._id },
      createdAt: now
    }
  })
  return ok({ commandId: addResult._id })
}

async function mpUpdateConfig(event) {
  const openid = getOpenId()
  const device = await findDeviceById(event.deviceId, openid)
  const input = event.config && typeof event.config === 'object' ? event.config : {}
  const patch = {}
  Object.keys(input).forEach(key => {
    if (CONFIG_FIELDS.has(key)) patch[key] = input[key]
  })
  if (!Object.keys(patch).length) throw new Error('CONFIG_EMPTY|没有可更新的配置')

  const latest = await getLatestConfig(device._id)
  const revision = latest ? Number(latest.revision) + 1 : 1
  const payload = Object.assign({}, latest ? latest.payload : {}, patch)
  const now = new Date()
  await db.collection(COLLECTIONS.configs).add({
    data: { deviceId: device._id, revision, payload, createdBy: openid, createdAt: now }
  })
  await db.collection(COLLECTIONS.devices).doc(device._id).update({
    data: { updatedAt: now }
  })
  await db.collection(COLLECTIONS.audit).add({
    data: {
      actorOpenId: openid,
      deviceId: device._id,
      action: 'UPDATE_CONFIG',
      payload: patch,
      createdAt: now
    }
  })
  return ok({ revision, config: payload })
}

async function mpGetRecords(event) {
  const openid = getOpenId()
  const device = await findDeviceById(event.deviceId, openid)
  const condition = event.date
    ? { deviceId: device._id, businessDate: event.date }
    : { deviceId: device._id }
  const result = await db.collection(COLLECTIONS.attendance)
    .where(condition)
    .orderBy('occurredAt', 'desc')
    .limit(50)
    .get()
  return ok({ records: result.data })
}

async function mpUnbindDevice(event) {
  const openid = getOpenId()
  const device = await findDeviceById(event.deviceId, openid)
  const now = new Date()
  await db.collection(COLLECTIONS.devices).doc(device._id).update({
    data: { status: 'REVOKED', updatedAt: now }
  })
  await db.collection(COLLECTIONS.audit).add({
    data: {
      actorOpenId: openid,
      deviceId: device._id,
      action: 'UNBIND_DEVICE',
      payload: {},
      createdAt: now
    }
  })
  return ok({ deviceId: event.deviceId })
}

async function initCollections() {
  for (const name of Object.values(COLLECTIONS)) {
    try {
      await db.createCollection(name)
    } catch (error) {
      // 已存在的集合会返回错误，忽略即可。
    }
  }
  return ok({ collections: Object.values(COLLECTIONS) })
}

exports.main = async (event) => {
  const payload = parseEvent(event)
  const action = payload.action || payload.path && payload.path.split('/').filter(Boolean).pop()
  try {
    switch (action) {
      case 'initCollections':
        return await initCollections()
      case 'pairDevice':
        return await pairDevice(payload)
      case 'deviceSync':
        return await deviceSync(Object.assign(payload, { headers: (event || {}).headers || payload.headers }))
      case 'deviceAck':
        return await deviceAck(Object.assign(payload, { headers: (event || {}).headers || payload.headers }))
      case 'deviceAttendance':
        return await deviceAttendance(Object.assign(payload, { headers: (event || {}).headers || payload.headers }))
      case 'mpCreatePairingCode':
        return await mpCreatePairingCode(payload)
      case 'mpListDevices':
        return await mpListDevices(payload)
      case 'mpGetDevice':
        return await mpGetDevice(payload)
      case 'mpCreateCommand':
        return await mpCreateCommand(payload)
      case 'mpUpdateConfig':
        return await mpUpdateConfig(payload)
      case 'mpGetRecords':
        return await mpGetRecords(payload)
      case 'mpUnbindDevice':
        return await mpUnbindDevice(payload)
      default:
        return fail('ACTION_NOT_FOUND', `未知操作：${action || 'empty'}`)
    }
  } catch (error) {
    const [code, message] = String(error.message || '').split('|')
    return fail(code || 'INTERNAL_ERROR', message || '服务端处理失败')
  }
}
