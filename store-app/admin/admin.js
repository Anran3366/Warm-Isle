const $ = (s, el = document) => el.querySelector(s)
const listEl = $('#list')
const statsEl = $('#stats')
const loginBox = $('#loginBox')
const sessionBox = $('#sessionBox')

// 会话令牌只存 sessionStorage：关标签页即失效（§6.9 2FA 登录）
let session = sessionStorage.getItem('storeAdminSession') || ''

function showAuth() {
  loginBox.hidden = !!session
  sessionBox.hidden = !session
}

$('#login').addEventListener('click', doLogin)
$('#code').addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin() })
$('#password').addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin() })
$('#logout').addEventListener('click', async () => {
  try { await api('/api/v1/admin/logout', { method: 'POST' }) } catch { /* 忽略 */ }
  session = ''
  sessionStorage.removeItem('storeAdminSession')
  showAuth()
  listEl.innerHTML = '<p class="empty">已退出</p>'
  statsEl.innerHTML = ''
})
$('#refresh').addEventListener('click', load)

async function doLogin() {
  const password = $('#password').value
  const code = $('#code').value.trim()
  if (!password) { alert('填管理密码'); return }
  try {
    const res = await fetch('/api/v1/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password, code }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error((data.errors || []).map((e) => e.message).join('；') || '登录失败')
    session = data.sessionToken
    sessionStorage.setItem('storeAdminSession', session)
    $('#password').value = ''
    $('#code').value = ''
    showAuth()
    load()
  } catch (e) {
    alert(e.message)
  }
}

async function api(path, init = {}) {
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + session,
      ...(init.headers || {}),
    },
  })
  const data = await res.json().catch(() => ({}))
  if (res.status === 401) {
    session = ''
    sessionStorage.removeItem('storeAdminSession')
    showAuth()
  }
  if (!res.ok) {
    const msg = (data.errors || []).map((e) => `${e.field}: ${e.message}`).join('；')
    throw new Error(msg || `请求失败 ${res.status}`)
  }
  return data
}

function fmtSize(n) {
  if (!n) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

async function load() {
  if (!session) {
    listEl.innerHTML = '<p class="empty">先登录（管理密码 + 认证器验证码）</p>'
    return
  }
  listEl.innerHTML = '<p class="empty">加载中…</p>'
  try {
    const [stats, pending] = await Promise.all([
      api('/api/v1/admin/stats'),
      api('/api/v1/admin/pending'),
    ])
    renderStats(stats)
    renderList(pending.items)
  } catch (e) {
    listEl.innerHTML = ''
    const p = document.createElement('p')
    p.className = 'empty'
    p.textContent = e.message
    listEl.appendChild(p)
  }
}

function renderStats(s) {
  statsEl.innerHTML = ''
  const items = [
    ['已上架', s.plugins],
    ['待审', s.pending],
    ['已驳回', s.rejected],
    ['作者', s.authors],
    ['下载次数', s.downloads],
    ['吊销', s.revocations],
  ]
  for (const [label, value] of items) {
    const d = document.createElement('div')
    d.className = 'stat'
    const b = document.createElement('b')
    b.textContent = String(value ?? 0)
    const sp = document.createElement('span')
    sp.textContent = label
    d.append(b, sp)
    statsEl.appendChild(d)
  }
}

function renderList(items) {
  listEl.innerHTML = ''
  if (!items.length) {
    listEl.innerHTML = '<p class="empty">没有待审的包</p>'
    return
  }
  for (const it of items) {
    listEl.appendChild(renderCard(it))
  }
}

function renderCard(it) {
  const node = $('#cardTpl').content.cloneNode(true)
  const set = (sel, text) => {
    const el = $(sel, node)
    if (el) el.textContent = text ?? '-'
  }

  set('.title', `${it.name} ${it.version}`)
  set('.meta', it.summary)
  set('.id', it.pluginId)
  set('.version', it.version)
  set('.runtime', it.runtime)
  set('.minhost', it.minHostVersion)
  set('.author', `${it.author.name}（${it.author.namespace}）`)
  set('.submitted', new Date(it.submittedAt).toLocaleString('zh-CN'))
  set('.size', fmtSize(it.size))
  set('.sha', it.sha256)

  const perms = it.manifest?.permissions || {}
  $(node).querySelector('.perm').textContent = JSON.stringify(perms, null, 2)
  $(node).querySelector('.readme').textContent = it.readme || '（没有 README）'
  $(node).querySelector('.manifest').textContent = JSON.stringify(it.manifest, null, 2)

  const noteEl = $(node).querySelector('.note')
  const okBtn = $(node).querySelector('.ok')
  const noBtn = $(node).querySelector('.danger')
  const rvBtn = $(node).querySelector('.revoke')

  okBtn.addEventListener('click', async () => {
    okBtn.disabled = noBtn.disabled = true
    try {
      await api(`/api/v1/admin/review/${it.submissionId}`, {
        method: 'POST',
        body: JSON.stringify({ action: 'approve', note: noteEl.value }),
      })
      await load()
    } catch (e) {
      alert(e.message)
      okBtn.disabled = noBtn.disabled = false
    }
  })

  noBtn.addEventListener('click', async () => {
    if (!noteEl.value.trim()) {
      alert('驳回要写理由，作者才知道改什么')
      return
    }
    okBtn.disabled = noBtn.disabled = true
    try {
      await api(`/api/v1/admin/review/${it.submissionId}`, {
        method: 'POST',
        body: JSON.stringify({ action: 'reject', note: noteEl.value }),
      })
      await load()
    } catch (e) {
      alert(e.message)
      okBtn.disabled = noBtn.disabled = false
    }
  })

  rvBtn.addEventListener('click', async () => {
    const reason = noteEl.value.trim() || prompt('吊销理由（会记进审计日志）') || ''
    if (!reason) return
    if (!confirm(`把 ${it.pluginId} ${it.version} 加入吊销名单？已装实例只会收到提示`)) return
    try {
      await api('/api/v1/admin/revoke', {
        method: 'POST',
        body: JSON.stringify({ pluginId: it.pluginId, version: it.version, reason }),
      })
      alert('已加入吊销名单')
      await load()
    } catch (e) {
      alert(e.message)
    }
  })

  return node
}

// 有会话就直接进队列，没有就停在登录框
showAuth()
if (session) load()
