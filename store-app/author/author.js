const $ = (s, el = document) => el.querySelector(s)

let token = localStorage.getItem('storeAuthorToken') || ''

/* --------------------------------------------------------------- 入口切换 */

const gate = $('#gate')
const bench = $('#bench')

function show() {
  gate.hidden = !!token
  bench.hidden = !token
  if (token) loadMine()
}

$('#tabLogin').addEventListener('click', () => switchPane(true))
$('#tabRegister').addEventListener('click', () => switchPane(false))
function switchPane(login) {
  $('#tabLogin').classList.toggle('active', login)
  $('#tabRegister').classList.toggle('active', !login)
  $('#loginPane').hidden = !login
  $('#registerPane').hidden = login
}

$('#doLogin').addEventListener('click', async () => {
  const t = $('#loginToken').value.trim()
  if (!t) { alert('把 token 贴上'); return }
  token = t
  try {
    await api('/api/v1/author/me')
    localStorage.setItem('storeAuthorToken', token)
    show()
  } catch (e) {
    token = ''
    alert(e.message)
  }
})

$('#doRegister').addEventListener('click', async () => {
  const body = {
    inviteCode: $('#inviteCode').value.trim(),
    namespace: $('#namespace').value.trim(),
    name: $('#authorName').value.trim(),
    email: $('#email').value.trim() || undefined,
  }
  try {
    const res = await fetch('/api/v1/author/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error((data.errors || []).map((e) => `${e.field}: ${e.message}`).join('；'))
    $('#tokenResult').hidden = false
    $('#newToken').textContent = data.authorToken
    token = data.authorToken
    localStorage.setItem('storeAuthorToken', token)
  } catch (e) {
    alert(e.message)
  }
})

$('#copyToken').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(token) } catch { /* 手动复制也行 */ }
  show()
})

$('#logout').addEventListener('click', () => {
  token = ''
  localStorage.removeItem('storeAuthorToken')
  show()
})

$('#rotate').addEventListener('click', async () => {
  if (!confirm('轮换后旧 token 立即失效，确定？')) return
  try {
    const data = await api('/api/v1/author/token/rotate', { method: 'POST' })
    token = data.authorToken
    localStorage.setItem('storeAuthorToken', token)
    $('#tokenResult').hidden = false
    $('#newToken').textContent = token
    gate.hidden = false
    bench.hidden = true
    switchPane(true)
  } catch (e) {
    alert(e.message)
  }
})

/* --------------------------------------------------------------- 请求封装 */

async function api(path, init = {}) {
  const res = await fetch(path, {
    ...init,
    headers: { 'X-Author-Token': token, ...(init.headers || {}) },
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    const msg = (data.errors || []).map((e) => `${e.field}: ${e.message}`).join('；')
    throw new Error(msg || `请求失败 ${res.status}`)
  }
  return data
}

/* --------------------------------------------------------------- 上传 */

$('#doUpload').addEventListener('click', async () => {
  const f = $('#file').files[0]
  if (!f) { alert('先选 zip 包'); return }
  const box = $('#uploadResult')
  box.hidden = false
  box.className = 'upload-result'
  box.textContent = '上传中…'
  $('#doUpload').disabled = true
  try {
    const fd = new FormData()
    fd.append('file', f)
    const data = await api('/api/v1/author/upload', { method: 'POST', body: fd })
    box.classList.add('ok')
    box.textContent = `已通过体检，进入待审队列：${data.pluginId} ${data.version}（sha256: ${data.sha256.slice(0, 16)}…）`
    $('#file').value = ''
    loadMine()
  } catch (e) {
    box.classList.add('bad')
    box.textContent = '体检没过：' + e.message
  } finally {
    $('#doUpload').disabled = false
  }
})

/* --------------------------------------------------------------- 我的插件 */

const STATUS_TEXT = {
  pending: '待审核',
  approved: '已上架',
  rejected: '已驳回',
  yanked: '已撤回',
  blocked: '已拉黑',
}

async function loadMine() {
  const mine = $('#mine')
  try {
    const data = await api('/api/v1/author/me')
    $('#meName').textContent = data.author.name
    $('#meNs').textContent = `命名空间 ${data.author.namespace}.*`
    mine.innerHTML = ''
    if (!data.plugins.length) {
      mine.innerHTML = '<p class="empty">还没有插件，传一个试试</p>'
      return
    }
    for (const p of data.plugins) mine.appendChild(renderPlugin(p))
  } catch (e) {
    mine.innerHTML = ''
    const p = document.createElement('p')
    p.className = 'empty'
    p.textContent = e.message
    mine.appendChild(p)
  }
}

function renderPlugin(p) {
  const node = $('#pluginTpl').content.cloneNode(true)
  $('.title', node).textContent = `${p.name}（${p.id}）`
  $('.meta', node).textContent = `下载 ${p.downloads || 0} 次`
  const st = $('.status', node)
  st.textContent = STATUS_TEXT[p.status] || p.status
  st.dataset.status = p.status
  const tbody = $('tbody', node)
  for (const v of p.versions) {
    const tr = document.createElement('tr')
    const td = (text) => {
      const c = document.createElement('td')
      c.textContent = text ?? ''
      return c
    }
    tr.append(
      td(v.version),
      td(STATUS_TEXT[v.status] || v.status),
      td(v.review_note || ''),
      td(v.submitted_at ? new Date(v.submitted_at).toLocaleString('zh-CN') : ''),
    )
    const act = document.createElement('td')
    if (v.status === 'approved') {
      const btn = document.createElement('button')
      btn.className = 'btn ghost small'
      btn.textContent = '撤回'
      btn.addEventListener('click', async () => {
        if (!confirm(`撤回 ${p.id} ${v.version}？部署者将看不到这个版本（已装的不受影响）`)) return
        try {
          await api(`/api/v1/author/plugins/${encodeURIComponent(p.id)}/yank`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ version: v.version }),
          })
          loadMine()
        } catch (e) {
          alert(e.message)
        }
      })
      act.appendChild(btn)
    }
    tr.appendChild(act)
    tbody.appendChild(tr)
  }
  return node
}

show()
