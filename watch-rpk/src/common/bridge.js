// 表端 ↔ 手机互联桥（持续链路状态机 · ping 握手/心跳 · send 双形态 · 严格对齐 Vela 4）
//
// ── 官方权威（iot.mi.com/vela/quickapp/zh/features/network/interconnect.html）──
//   · 「通信连接会自动建立，应用内不用关心连接的创建和销毁」——instance() 是单例，
//     接口里【没有 close()】，物理连接由【系统】建连/重连；onopen 回调带 isReconnected。
//   · 因此本层【绝不重建 instance、绝不主动断链】，只负责：持续验证、断线检测、自动恢复。
//     试图自己 close+instance 是臆断，会与系统建连打架（历史教训）。
//   · send({data:Object, success, fail})；data 必须是 Object（不是 string）。
//     失败码：204 链接超时 / 1006 连接断开。
//   · onerror：1000 未知 / 1001 手机 APP 未安装 / 1006 断开；onclose 带 code/data；
//     onopen 带 isReconnected（系统重连）。
//   · getReadyState success → {status}：1 连接成功 / 2 连接断开（只作辅助事实，不当判据）。
//   · 通信前提：快应用 package 与手机 App 包名一致，且两者签名一致。
//   · 官方 dev demo 表明 send/getReadyState 同时支持「回调」与「Promise」两种形态，
//     fail 回调参数既有 (res, code) 两参，也有单参 res={data, code} —— 必须全兼容。
//   · diagnosis() 在 Vela 4 真机上不是函数（0.1.5 TypeError），永不调用。
//
// ── 0.1.13 连接稳定性专项（真机「有时连不上、有时能连上」的代码根因）──
//   旧版 = 点一次「连接手机」→ 60s 窗口发 ping → 收到 pong 就 clearTimers 停掉全部探测
//   → 连上后【零心跳、零断线检测】；BLE 抖动/锁屏后表端仍显示「已连接」，直到发指令
//   失败才发现；且 onclose / send 失败只提示「手动重点连接」，没有自动恢复。而手机端是
//   【纯被动等 ping】，表端 ping 必须恰好撞上手机端 listener 注册好的那个窗口，撞不上就
//   60s 判死。命中时机全靠运气 = 「有时连不上、有时能连上」。
//
//   本版改为【长期状态机】（系统管物理连接，App 管验证与恢复）：
//   1) 连上后不停 ping，改低频心跳(9s)刷新 lastSeen；>30s 无任何手机回执 → 判掉线；
//   2) 掉线不判死、不清回调，自动切回高频探测(2.5s)并显示「自动重连中」，
//      永不 settleFail（唯一例外 1001 手机端未装 App 属不可恢复）；
//   3) onopen(isReconnected) / onclose / onerror 都触发一次立即探测(kickPing)，
//      系统重连那一刻就能最快重新握手，不必等下一个 tick；
//   4) 手机端 listener 一就绪就主动发 cmd='ready' 帧（0.1.7 companion），表端收到
//      立即回一次 ping 完成双向验证 —— 打破「必须撞窗口」的时序竞态；
//   5) 断线期间发的指令入队、连上后 flush 补发（保留原 id，模块侧按 id 幂等去重，
//      不会让 WorkBuddy 执行两次）；已发出但回执丢失的指令不静默重复执行；
//   6) 控制帧(pong / ready)不进结果行，不再出现「✓ pong」污染结果展示；
//   7) 每次 open 幂等重新绑定四个回调（赋值非累加），修复长期运行回调失效的隐患。
//
// 手机端配合：CompanionService.handleWatchFrame 对 cmd='ping' 本地直回 pong（带 t），
//             XmsInterconnectLink 就绪/重连时主动发 cmd='ready'。
//
// ── 0.1.16 回归纯指令状态机 ──
//   0.1.15 曾把「自定义指令的语音识别」放到手机端（新增跨端语音帧）。
//   真机结论：手机端 SpeechRecognizer 在 :relay 服务里被 ROM 拦成「识别中断」；且把识别
//   放在手机、再回传手表发送，主次颠倒（手表才是发起端）。Vela 三方 rpk 也没有可向它
//   弹出的系统输入法/ASR。因此 0.1.16 彻底删除全部语音帧、语音等待表与语音请求出口：
//   自定义指令改为【表端内置输入面板】本地拼字 → 核对 → 原 send() 发送，全程不经过手机。
//   本文件自此只负责指令链路（ping/pong/ready/指令回执/排队补发），不再有任何输入相关帧。
//
// ── 0.1.19 健壮性专项（dev-expert 复审，纯逻辑/显示层，零帧协议改动）──
//   A1 异步 send 失败入队补发时，撤销该帧 pending 超时裁决，补发成为唯一路径
//      （消除表端「先 ✗ 超时、连上后补发又 ✓ 成功」的抖动与文案自相矛盾）。
//   A2 rawSend 同步抛异常不再触发 extraFail 入队，统一交给 dispatch 的 !sendRaw 分支
//      （消除同一帧被 unshift + push 双重入队导致的队列/诊断计数失真）。
//   A6 diagnose 精简为两行各 3 项、单行可读；删除随之失效的 openedBy 死变量。
//   W1 指令超时按命令分档：慢命令(wb.prompt 两次 su / dsh.prompt 多次 HTTP)预算 30s，
//      覆盖手机端最坏执行时间，避免表端在手机仍执行时提前宣布超时；快命令仍 12s。
//   W4 trimQueue 队列满静默丢最老指令 → 明确提示丢弃条数与是否需手动补发。

const FAST_MS = 2500            // 未连上 / 重连中的探测间隔
const HEARTBEAT_MS = 9000       // 连上后的心跳间隔
const DEAD_AFTER = 30000        // 连上后超过该时长无任何手机回执 → 判掉线
const KICK_MIN_GAP = 1000       // 「立即探测」最小间隔，防 onopen/onerror 密集触发刷帧
const PROGRESS_EVERY = 4        // 每 N 次探测给一次进度提示（避免 UI 像卡死）
const MAX_QUEUE = 20
// 指令回执要跨「companion→内置引擎(可能拉起 WorkBuddy / 请求 dsh-go)→结果→BLE」，
// 冷启动首次可能较慢。0.1.19 起按命令分档（W1 超时预算对齐）：
//   · 快命令(本地/单次查询) 12s；
//   · 慢命令(wb.prompt 要跑 2 次 su，各 8s 上限；dsh.prompt 可能串 login+多次 POST，
//     每次 connect/read 各 8s)理论上限远超 12s → 给 30s 覆盖手机端最坏执行时间。
// 目的：表端绝不在「手机端仍在执行并可能已产生副作用」时提前宣布超时；
// 正常路径回执秒回，回执一到即 clearTimeout，分档不会拖慢正常显示。
const SEND_TIMEOUT = 12000
const SLOW_TIMEOUT = 30000
const SLOW_CMDS = ['wb.prompt', 'dsh.prompt', 'dsh.new', 'dsh.cancel', 'dsh.list', 'status']

function budgetFor(cmd) {
  return SLOW_CMDS.indexOf(cmd) >= 0 ? SLOW_TIMEOUT : SEND_TIMEOUT
}

// 诊断用的中文状态词（表端 status/hint 是单行小字，直接给中文比英文 state 可读）
const STATE_CN = {
  online: '已连接', offline: '未连接', connecting: '连接中', relink: '重连中', error: '出错',
}

// 可选诊断增强 API：只探测存在性，只采集 getApkStatus/getReadyState 的事实；
// diagnosis 仅用于能力清单展示，绝不调用。
const OPTIONAL_APIS = ['getApkStatus', 'getReadyState', 'diagnosis']

function safeStr(v) {
  if (v === undefined) return 'undefined'
  if (v === null) return 'null'
  if (typeof v === 'string') return v.length > 40 ? v.slice(0, 40) + '…' : v
  try {
    const s = JSON.stringify(v)
    return s && s.length > 40 ? s.slice(0, 40) + '…' : (s || String(v))
  } catch (e) { return String(v) }
}

// 官方错误码 → 可执行的排查结论（0.1.13：1006/204 归入「系统自动重连」，不再判死）
function errText(code) {
  const c = Number(code)
  if (c === 1001) return '1001 手机端 App 未安装（装 companion，同包名同签名）'
  if (c === 1006) return '1006 通道暂断（系统自动重连中）'
  if (c === 204) return '204 链接超时（自动重连中）'
  if (c === 1000) return '1000 未知连接错误（自动重连中）'
  if (!c) return '无错误码'
  return 'code=' + code
}

// fail 回调兼容两参 (res, code) 与单参 res={data,code}；也兼容 res 本身就是数字/字符串。
function pickErr(res, code) {
  let c = code
  if ((c === undefined || c === null) && res && typeof res === 'object') c = res.code
  if ((c === undefined || c === null) && (typeof res === 'number' || typeof res === 'string')) c = res
  let msg = ''
  if (res && typeof res === 'object' && res.data !== undefined) msg = safeStr(res.data)
  else if (typeof res === 'string') msg = safeStr(res)
  return { code: (c === undefined || c === null || c === '') ? null : c, msg: msg }
}

function freshFacts() {
  return {
    onopen: false, onclose: false, onerror: '', oncloseCode: '', onmessage: false,
    errorHinted: false,
    sendNoFn: false, sendCalled: 0, sendOk: 0, sendFail: 0, sendThrew: 0,
    lastCode: null, lastErr: '',
    apk: '?', ready: '?', readyStatus: null,
  }
}

function freshStats() {
  return {
    reconnects: 0,      // 掉线 → 自动重连 次数
    recovered: 0,       // 重连后恢复成功 次数
    sysReconnects: 0,   // 系统 onopen(isReconnected) 次数
    beats: 0,           // 心跳 ping 次数
    probes: 0,          // 探测 ping 次数
    readyFrames: 0,     // 收到手机端 ready 主动通告 次数
    lastSeen: 0,        // 最后一次收到任意手机帧的时间戳
  }
}

export function createBridge({ onMessage, onState, onDiag, onHint } = {}) {
  let connect = null
  let state = 'offline'          // offline | connecting | online | relink | error
  let running = false            // 状态机是否在运行（用户主动断开才置 false）
  let everStarted = false        // 是否曾启动过（决定 facts 是否重置）
  let everConnected = false      // 是否曾连上过（区分「首次连接」与「重连」）
  let queue = []                 // 因未连接而未发出的指令（连上后 flush）
  let pending = new Map()        // 已发出、等待回执的指令
  let seq = 0
  let pings = 0
  let lastPingAt = 0
  let loopTimer = null
  let facts = freshFacts()
  let stats = freshStats()
  let caps = { instance: false, send: false }

  function setState(next) {
    if (state === next) return
    state = next
    try { onState && onState(next) } catch (e) { console.error('[watchctl] onState', e) }
  }
  function diag(t) { try { onDiag && onDiag(t) } catch (e) {} }
  function hint(t) { try { onHint && onHint(t) } catch (e) {} }

  function hasFn(name) {
    try { return !!(connect && typeof connect[name] === 'function') }
    catch (e) { return false }
  }

  function probeCaps() {
    caps.instance = !!connect
    caps.send = hasFn('send')
    for (const a of OPTIONAL_APIS) caps[a] = hasFn(a)
  }

  function clearTimers() {
    if (loopTimer) { clearTimeout(loopTimer); loopTimer = null }
  }
  function scheduleNext(delay) {
    if (loopTimer) clearTimeout(loopTimer)
    loopTimer = setTimeout(tick, delay)
  }

  function trimQueue() {
    if (queue.length <= MAX_QUEUE) return
    let dropped = 0
    while (queue.length > MAX_QUEUE) { queue.shift(); dropped++ }
    // W4：队列满会静默丢最老指令，断线连点会无声丢单 → 明确告知丢了几个、要不要手动补。
    diag('待发队列已满(' + MAX_QUEUE + ') · 丢弃最老 ' + dropped + ' 条未发指令')
    hint('连上后只补发队列内的指令；被丢弃的请手动再发一次')
  }

  // 原始发送，不检查 state。mode: 'callback' 传 success/fail；'promise' 只传 data 靠返回 Promise。
  // 无论哪种 mode，只要返回 thenable 就接上 .then/.catch（settled 去重，避免重复计数）。
  function rawSend(frame, mode, extraFail) {
    if (!connect || !hasFn('send')) { facts.sendNoFn = true; return false }
    let settled = false
    const ok = () => {
      if (settled) return
      settled = true
      facts.sendOk++
    }
    const bad = (res, code) => {
      if (settled) return
      settled = true
      facts.sendFail++
      const e = pickErr(res, code)
      if (e.code !== null) facts.lastCode = e.code
      if (e.msg) facts.lastErr = e.msg
      try { extraFail && extraFail(res, code) } catch (err) {}
    }
    try {
      const arg = { data: frame }              // 官方：data 必须是 Object
      if (mode !== 'promise') {
        arg.success = ok
        arg.fail = bad
      }
      const r = connect.send(arg)
      facts.sendCalled++
      if (r && typeof r.then === 'function') {
        r.then(ok).catch((res) => bad(res, res && res.code))
      }
      return true
    } catch (e) {
      facts.sendThrew++
      // A2：同步抛异常时【不】触发 extraFail 入队 —— 返回 false 交给 dispatch 的
      // !sendRaw 分支统一 queue.push，避免 extraFail.unshift + dispatch.push 双重入队
      // （幂等去重能兜住重复执行，但队列与 sendFail/诊断计数会失真）。
      if (!settled) {
        settled = true
        facts.sendFail++
        facts.lastErr = safeStr(String(e))
      }
      return false
    }
  }

  // 发一帧 ping（探测/心跳共用）。交替 callback / Promise 双形态覆盖不同固件实现。
  function firePing() {
    lastPingAt = Date.now()
    pings++
    const mode = (pings % 2 === 1) ? 'callback' : 'promise'
    if (state === 'online') stats.beats++
    else stats.probes++
    rawSend({ id: 'ping-' + Date.now() + '-' + pings, cmd: 'ping' }, mode)
  }

  // 「立即探测」：系统刚建连/重连、手机端刚就绪、onclose/onerror 时调用，带节流。
  // 不改变主循环节奏，只是抢在下一个 tick 之前多验一次。
  function kickPing() {
    if (!running || !connect) return
    if (Date.now() - lastPingAt < KICK_MIN_GAP) return
    firePing()
  }

  // 主循环：一个可变间隔的递归 setTimeout。系统管物理连接，这里只负责持续验证。
  function tick() {
    if (!running) return
    const now = Date.now()
    // 连上后超过 DEAD_AFTER 没有任何手机回执 → 判掉线，自动切回探测
    if (state === 'online' && stats.lastSeen > 0 && now - stats.lastSeen > DEAD_AFTER) {
      onLinkLost('心跳超时（' + Math.round((now - stats.lastSeen) / 1000) + 's 无回执）')
    }
    firePing()
    if (state !== 'online' && stats.probes % PROGRESS_EVERY === 0) {
      const tag = everConnected ? '自动重连中' : '等待手机端就绪'
      diag(tag + '…已探测 ' + stats.probes + ' 次')
      hint('保持手机端 companion 在前台；本版自动重连，无需手动重点')
    }
    scheduleNext(state === 'online' ? HEARTBEAT_MS : FAST_MS)
  }

  function flush() {
    const waiting = queue
    queue = []
    for (const frame of waiting) dispatch(frame, null)
  }

  function markConnected(how) {
    // P1 修复：用户主动 close() 后 running=false，但物理层 connect.onmessage 未解绑，
    // 残留回执到达会把状态假复活成 online——此时 send() 因 !running 拒绝，
    // UI 显示「已连接」却发不出指令。加 running 守卫，断开后一律忽略残留回执。
    if (!running) return
    if (state === 'online') return
    const wasRelink = (state === 'relink')
    everConnected = true
    stats.lastSeen = Date.now()
    setState('online')
    if (wasRelink) {
      stats.recovered++
      diag('已恢复连接 · ' + how)
    } else {
      diag('已连接 · 双向通道已验证')
    }
    hint('心跳维护中；点「查看连接诊断」看链路健康')
    flush()
    scheduleNext(HEARTBEAT_MS)
  }

  // 掉线：不判死、不清回调、不 settleFail，切回高频探测等系统重连 + 手机端就绪。
  function onLinkLost(why) {
    if (!running || state === 'error' || state === 'relink') return
    stats.reconnects++
    setState('relink')
    diag('连接中断 · ' + why + ' → 自动重连中')
    hint('系统会自动重建连接；也可点「连接手机」立即探测')
    kickPing()
    scheduleNext(FAST_MS)
  }

  // 真正不可恢复的终态（目前只有 1001 与接口缺失）：才停状态机。
  function settleFail(text, advice) {
    if (state === 'online') return
    running = false
    clearTimers()
    setState('error')
    diag(text)
    hint(advice)
  }

  // 控制帧(pong/ready/hello)不进结果行，避免「✓ pong」污染指令结果展示。
  function deliver(message) {
    if (!message || typeof message !== 'object') return
    if (message.cmd === 'ping' || message.cmd === 'ready' || message.cmd === 'hello') return
    if (message.id && pending.has(message.id)) {
      const entry = pending.get(message.id)
      clearTimeout(entry.timer)
      pending.delete(message.id)
    }
    try { onMessage && onMessage(message) } catch (e) { console.error('[watchctl] onMessage', e) }
  }

  // 可选 API：只采集事实。同步返回 / 回调 / Promise 三种形态全兼容，原样记录。
  function collect(name, key) {
    if (!hasFn(name)) { facts[key] = '无此能力'; return }
    let got = false
    const ok = (res) => {
      if (got) return
      got = true
      facts[key] = safeStr(res)
      if (key === 'ready') {
        let st = (res && res.status !== undefined) ? res.status
          : (res && res.data && res.data.status !== undefined) ? res.data.status : null
        if (st !== null) facts.readyStatus = st
      }
    }
    const bad = (res, code) => {
      if (got) return
      got = true
      const e = pickErr(res, code)
      facts[key] = 'fail' + (e.code !== null ? '(' + e.code + ')' : '')
    }
    try {
      const r = connect[name]({ success: ok, fail: bad })
      if (r && typeof r.then === 'function') r.then(ok).catch((res) => bad(res, res && res.code))
      else if (r !== undefined && r !== null && !got) ok(r)   // 同步返回值（getApkStatus）
    } catch (e) { if (!got) facts[key] = 'throw' }
  }

  function collectFacts() {
    probeCaps()
    collect('getApkStatus', 'apk')
    collect('getReadyState', 'ready')
  }

  // 单例只创建一次；但四个回调每次 open 都幂等重新绑定（赋值非累加，安全且可修复回调失效）。
  function bindCallbacks() {
    connect.onopen = (d) => {
      facts.onopen = true
      if (d && d.isReconnected) {
        stats.sysReconnects++
        diag('系统已重连(isReconnected) · 立即验证')
      }
      if (running) kickPing()
    }
    connect.onclose = (d) => {
      facts.onclose = true
      if (d && d.code !== undefined) facts.oncloseCode = String(d.code)
      if (state === 'online') onLinkLost('onclose(' + (d && d.code !== undefined ? d.code : '?') + ')')
      else if (running) kickPing()
    }
    connect.onerror = (d) => {
      const e = pickErr(d, d && d.code)
      facts.onerror = e.code === null ? '?' : String(e.code)
      // 1001 = 手机端 App 未安装，确属不可恢复，快速给结论并停状态机。
      if (Number(e.code) === 1001) {
        settleFail('连接错误 onerror · ' + errText(e.code),
          '手机端装 companion 并打开一次，同包名同签名；装好再点「连接手机」')
        return
      }
      // 1006/204/1000 只是「通道暂时不可用」：系统会自动重连，绝不 settleFail 判死。
      if (e.code !== null) facts.lastCode = e.code
      if (state === 'online') onLinkLost('onerror ' + e.code)
      if (!facts.errorHinted) {
        facts.errorHinted = true
        diag('通道暂未就绪 · 继续自动探测')
        hint('手机端授权/就绪中，保持前台；本版会自动重连')
      }
      kickPing()
    }
    connect.onmessage = (msg) => {
      facts.onmessage = true
      stats.lastSeen = Date.now()        // 任何手机来的帧都是「链路活着」的证据
      let data = msg && msg.data
      if (typeof data === 'string') {
        try { data = JSON.parse(data) } catch (err) { data = { ok: true, text: data } }
      }
      if (!data || typeof data !== 'object') return
      const cmd = data.cmd
      // 手机端就绪主动通告：打破「表端 ping 必须撞上手机端就绪窗口」的时序竞态。
      if (cmd === 'ready' || cmd === 'hello') {
        stats.readyFrames++
        if (state !== 'online') kickPing()
        return
      }
      // pong 回执：双向已验证，但不进结果行。
      if (cmd === 'ping') {
        if (state !== 'online') markConnected('收到 pong 回执')
        return
      }
      // 指令回执：能收到就说明双向通；非 online 时顺带恢复状态。
      if (state !== 'online') markConnected('收到手机回执')
      deliver(data)
    }
  }

  function ensureConnect() {
    if (!connect) {
      try {
        const interconnect = require('@system.interconnect')
        if (!interconnect || typeof interconnect.instance !== 'function') {
          settleFail('互联模块接口异常', '本固件可能未提供 @system.interconnect')
          return
        }
        connect = interconnect.instance()
      } catch (error) {
        console.error('[watchctl] 加载互联模块失败', error)
        settleFail('无法加载 @system.interconnect', '确认 manifest features 已声明 system.interconnect')
        return
      }
    }
    try {
      bindCallbacks()
    } catch (e) {
      console.error('[watchctl] 绑定回调失败', e)
      settleFail('回调绑定失败', '请卸载旧包后重装 rpk')
      return
    }
    probeCaps()
  }

  function open() {
    if (state === 'online') { kickPing(); diag('已连接 · 立即验证一次'); return }
    if (running) { kickPing(); return }   // 已在 connecting / relink，不重复起循环

    running = true
    if (!everStarted) { facts = freshFacts(); stats = freshStats(); everStarted = true }
    facts.lastCode = null                 // 清掉上一轮终态遗留的错误码
    facts.errorHinted = false
    stats.lastSeen = 0
    setState('connecting')
    diag('正在连接…（持续验证 · 断线自动重连）')
    hint('手机端就绪约需 30s，保持 companion 在前台')

    ensureConnect()
    if (state === 'error') return        // 加载失败已 settleFail
    collectFacts()
    scheduleNext(0)                      // 立即发第一帧，之后按状态自适应间隔
  }

  function sendRaw(frame) {
    if (!connect || state !== 'online' || !hasFn('send')) return false
    return rawSend(frame, 'callback', (res, code) => {
      const e = pickErr(res, code)
      // A1：异步 fail 时 rawSend 已返回 true，dispatch 不会走 !sendRaw 分支，
      // 该帧的 pending 超时裁决仍挂着。既然决定入队补发，就先撤销这次超时裁决，
      // 让「补发」成为唯一路径 —— 避免表端先弹 ✗ 超时、连上后补发又 ✓ 成功的抖动，
      // 也避免「未自动重发」文案与「已入队补发」事实自相矛盾。
      const entry = pending.get(frame.id)
      if (entry) { clearTimeout(entry.timer); pending.delete(frame.id) }
      // send 失败：保留原 id 放回队列，连上后补发。模块侧按 id 幂等去重，
      // 即使帧其实已送达也不会让 WorkBuddy 执行两次。
      queue.unshift(frame)
      trimQueue()
      onLinkLost('发送失败 · ' + errText(e.code))
    })
  }

  function dispatch(frame, opts) {
    // W1：慢命令用更长预算，覆盖手机端最坏执行时间，避免表端提前判超时。
    const timeoutMs = (opts && opts.timeoutMs) || budgetFor(frame.cmd)
    const old = pending.get(frame.id)
    if (old) clearTimeout(old.timer)
    pending.set(frame.id, {
      timer: setTimeout(() => {
        pending.delete(frame.id)
        // 如实：超时不自动重发，避免指令被重复执行；用户可手动再发一次。
        // 预算已放宽，超时多因手机端仍在执行 / 回执丢失，文案不再暗示「已失败」。
        deliver({ id: frame.id, ok: false, code: 'timeout',
          text: '超时·未收到回执（手机端可能仍在执行，未自动重发）' })
      }, timeoutMs),
    })
    if (!sendRaw(frame)) {
      const entry = pending.get(frame.id)
      if (entry) { clearTimeout(entry.timer); pending.delete(frame.id) }
      queue.push(frame)
      trimQueue()
      onLinkLost('指令暂未发出')
    }
    return frame.id
  }

  function send(command, opts) {
    if (!running) {
      diag('尚未连接，请先点「连接手机」')
      return null
    }
    const id = 'w-' + Date.now() + '-' + (++seq)
    const frame = Object.assign({ id: id }, command)
    if (state !== 'online') {
      // 正在重连：指令入队，连上后 flush 自动补发，不必等用户重新点。
      queue.push(frame)
      trimQueue()
      diag('自动重连中 · 指令已排队，连上后自动发送')
      kickPing()
      return id
    }
    return dispatch(frame, opts)
  }

  // 「查看连接诊断」= 链路健康速览。
  // A6：表端 status(14px)/hint(13px) 都是单行小字，旧版把 12+ 字段塞一行根本读不全。
  // 精简为两行各 3 项：状态行给「连接状态 / 最近回执 / 待发」，提示行给
  // 「send / 失败码 / 可选能力数」。心率级细节(beats/ready/recovered/apk/openedBy)
  // 仍在 health getter 与 logcat，真要排障看日志，不再挤占表端单行。
  function diagnose() {
    collectFacts()
    const sup = OPTIONAL_APIS.filter((a) => caps[a])
    const ago = stats.lastSeen ? Math.round((Date.now() - stats.lastSeen) / 1000) + 's前' : '无'
    diag((STATE_CN[state] || state) + ' · 回执 ' + ago + ' · 待发 ' + queue.length)
    hint('send ' + (caps.send ? '✓' : '✗')
      + ' · 失败码 ' + (facts.lastCode === null ? '无' : facts.lastCode)
      + ' · 可选能力 ' + sup.length + '/' + OPTIONAL_APIS.length)
  }

  // 用户主动断开：停状态机（省电）。不碰 connect 单例——物理连接归系统管。
  function close() {
    running = false
    clearTimers()
    for (const entry of pending.values()) clearTimeout(entry.timer)
    pending.clear()
    queue = []
    setState('offline')
  }

  return {
    open, send, close, diagnose,
    get state() { return state },
    get queued() { return queue.length },
    get started() { return running },
    get health() {
      return {
        state: state,
        lastSeenAgo: stats.lastSeen ? Math.round((Date.now() - stats.lastSeen) / 1000) : -1,
        reconnects: stats.reconnects,
        recovered: stats.recovered,
        beats: stats.beats,
        readyFrames: stats.readyFrames,
      }
    },
  }
}
