// 每天 10:00 生成并发布一篇暖屿文章。
// systemd timer 调用；--dry-run 只生成不发布。
import fs from 'node:fs'
import path from 'node:path'
import matter from 'gray-matter'

const DRY = process.argv.includes('--dry-run')
const DATA_DIR = process.env.DATA_DIR || '/opt/warm-isle-blog/data'
const ARTICLES_DIR = path.join(DATA_DIR, 'articles')
const API = process.env.BLOG_API || 'http://127.0.0.1:3300/blog/api/upload'
const PASSWORD = process.env.BLOG_PASSWORD || process.env.APP_PASSWORD || ''
const KEY = (process.env.DEEPSEEK_API_KEY || '').trim()
const BASE = (process.env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com').replace(/\/+$/, '')
const MODEL = process.env.DAILY_MODEL || 'deepseek-chat'
const SITE = (process.env.BASE_URL || 'https://web.example.com').replace(/\/+$/, '')
const BLOG = SITE + '/blog'
const INDEXNOW_KEY = (process.env.INDEXNOW_KEY || '').trim()
const HERE = path.dirname(new URL(import.meta.url).pathname)
const STATE = path.join(HERE, 'used.json')
const LOG = path.join(HERE, 'daily.log')

const FOOTER = `

---

**暖屿 Warm Isle** 是一个会记住你的 QQ AI 陪伴机器人。

- 官网：<https://web.example.com>
- 个人主页：<https://example.com>
- Gitee：<https://gitee.com/menran/warm_isle>
- GitHub：<https://github.com/Anran3366/Warm-Isle>
- QQ 交流群：**601921002**
- 插件商店：<https://store.example.com>
`

const FACTS = `
项目：暖屿 Warm Isle（QQ AI 陪伴机器人，机器人叫小满）
技术栈：Java 21 / Spring Boot 3 / SQLite / Vue 3；QQ 侧 NapCat 或 SnowLuma（OneBot v11）；DeepSeek 对话；MiMo TTS；本地 imageguard（CLIP/NSFW/OCR）；Docker Compose + Caddy。
能力：长期记忆（事实卡、强度、遗忘曲线、关键词+语义召回）、记忆星图、人设与表达、语音/音色克隆、表情包、看图、知识库 RAG、主动消息、承诺跟进、多机器人账号后台。
插件商店 v2.3：官方商店集中审核；data/prompt/hosted/local 四类 runtime；Ed25519 验签与密钥轮换；已安装可升级、changelog、hosted/local 服务版本巡检。
协议端：NapCat 与 SnowLuma 同级，按账号任选，没有默认主协议端。
数据：SQLite 三层（global.db / shared.db / 每个 bot 的 bots/<id>.db），本地自托管，聊天数据不上传第三方。
开源：AGPL-3.0，Gitee menran/warm_isle，GitHub Anran3366/Warm-Isle。
官网：https://web.example.com ；QQ 群：601921002。
`

function today() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
}
function norm(s) {
  return String(s || '').toLowerCase().replace(/[\s\p{P}]/gu, '')
}
function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}\n`
  fs.appendFileSync(LOG, line)
  console.log(line.trim())
}
function loadPosts() {
  const out = []
  for (const name of fs.readdirSync(ARTICLES_DIR)) {
    if (!/\.md$/i.test(name)) continue
    try {
      const raw = fs.readFileSync(path.join(ARTICLES_DIR, name), 'utf8')
      const p = matter(raw)
      out.push({ file: name, title: p.data.title || '', slug: p.data.slug || name.replace(/\.md$/i, ''), summary: p.data.summary || '' })
    } catch { /* skip bad */ }
  }
  return out
}
function loadUsed() {
  try { return JSON.parse(fs.readFileSync(STATE, 'utf8')) } catch { return [] }
}
function saveUsed(ids) {
  fs.writeFileSync(STATE, JSON.stringify([...new Set(ids)], null, 2))
}
function pickTopic(used) {
  const topics = JSON.parse(fs.readFileSync(path.join(HERE, 'topics.json'), 'utf8'))
  const free = topics.filter(t => !used.includes(t.id))
  if (free.length) return free[Math.floor(Math.random() * free.length)]
  // 池子用完后继续从全量里挑，但要求 LLM 给新角度
  return topics[Math.floor(Math.random() * topics.length)]
}
function stripFence(s) {
  return String(s || '').replace(/^```(?:markdown|md)?\s*/i, '').replace(/```\s*$/i, '').trim()
}
async function chat(messages, maxTokens = 4096) {
  if (!KEY) throw new Error('DEEPSEEK_API_KEY 未配置')
  const res = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    body: JSON.stringify({ model: MODEL, messages, temperature: 0.85, max_tokens: maxTokens }),
  })
  if (!res.ok) throw new Error(`DeepSeek ${res.status}: ${(await res.text()).slice(0, 200)}`)
  const data = await res.json()
  return data.choices?.[0]?.message?.content || ''
}
function fallbackFaq(title, summary) {
  return [
    { q: `${title}是什么？`, a: summary },
    { q: '暖屿需要自己部署吗？', a: '是自托管项目，聊天数据保存在自己的机器上；部署方式见官网文档。' },
    { q: '在哪里可以看到源码和更新？', a: 'Gitee 和 GitHub 的 warm_isle 仓库，官网 web.example.com 有文章与手册。' },
  ]
}
function validate(text, posts, topic) {
  const parsed = matter(text)
  const fm = parsed.data || {}
  if (!fm.title || !fm.slug || !fm.summary) throw new Error('front-matter 缺 title/slug/summary')
  if (!/^[a-z0-9-]{3,60}$/.test(fm.slug)) throw new Error('slug 不是英文短横线格式')
  if (posts.some(p => norm(p.title) === norm(fm.title))) throw new Error('标题重复')
  if (posts.some(p => p.slug === fm.slug)) {
    // slug 撞车时自动加日期后缀，不因此丢掉一整篇；标题仍严格查重
    fm.slug = `${fm.slug}-${today().replace(/-/g, '')}`
  }
  if (posts.some(p => p.slug === fm.slug)) throw new Error('slug 重复')
  if (parsed.content.replace(/\s/g, '').length < 1200) throw new Error('正文太短')
  const headings = [...parsed.content.matchAll(/^#{2,3}\s+(.+)$/gm)].map(m => m[1].trim())
  if (headings.length < 4) throw new Error('二级标题少于 4 个')
  for (const h of headings) {
    if (h.length > 24) throw new Error('标题过长，像一句话而不是标题：' + h)
    if (/[。，,；;！？!?]/.test(h)) throw new Error('标题里不能有句读：' + h)
    if (/https?:\/\//i.test(h)) throw new Error('标题里不能放链接：' + h)
  }
  // 正文只允许站内链接；官网/Gitee/GitHub/QQ 群统一由系统页脚追加
  if (/gitee\.com|github\.com|web\.mziran\.cn|store\.mziran\.cn|601921002/i.test(parsed.content)) {
    throw new Error('正文包含外链或群号，应由系统页脚统一追加')
  }
  return { fm, body: parsed.content.trim() }
}
async function generate(topic, posts) {
  const recent = posts.slice(0, 50).map(p => `- ${p.title}`).join('\n')
  const used = loadUsed()
  const prompt = `你是暖屿 Warm Isle 项目作者，写一篇中文技术博客。今天日期 ${today()}。

主题：${topic.title}
角度：${topic.angle}
${used.includes(topic.id) ? '这个主题以前写过，必须换一个全新的切入点，标题和论点不能和以前重复。' : ''}

项目事实（只能使用这些，不要编造数字）：
${FACTS}

最近已发布标题（不能重复，也不要换汤不换药）：
${recent}

写作要求：
1. 1500-2500 个中文字符，读者是开发者和自部署用户；
2. 开头 2-3 句直接回答主题，再展开；至少 4 个 ## 二级标题；
3. 有具体实现、取舍、踩坑，不写空泛营销；
4. 至少一个表格或有序列表；代码只在准确时出现；
5. 加 3 个读者真正会问的 FAQ，放在 front-matter 的 faq 里（q/a）；
6. front-matter 必填：title、slug（英文小写短横线）、date（${today()}）、tags（数组）、summary（50-90字）、faq；
7. 每个 ## 标题不超过 20 个汉字，必须是短语，不能是完整句子，不能带句号/逗号/问号，不能放链接；
8. 正文不要写官网、Gitee、GitHub、商店地址或 QQ 群号，系统会统一追加页脚；
9. 末尾用「## 小结」加一段自然总结，不要把总结写成标题；
10. 不要写“本文由 AI 生成”。

只输出 Markdown，从 --- 开始，不要代码围栏。`;
  const text = stripFence(await chat([
    { role: 'system', content: '你是严谨的技术作者，只写可验证的事实，不编造指标。' },
    { role: 'user', content: prompt },
  ]))
  const checked = validate(text, posts, topic)
  let { fm, body } = checked
  fm.date = today()
  fm.updated = today()
  fm.keywords = fm.keywords || (Array.isArray(fm.tags) ? fm.tags.join(',') : '')
  if (!Array.isArray(fm.faq) || fm.faq.length < 3) fm.faq = fallbackFaq(fm.title, fm.summary)
  // 去掉模型自己写的结尾分隔线：它紧贴段落时会被 Markdown 当成 setext 标题下划线
  body = body.replace(/\n+---\s*$/, '').trim()
  body += FOOTER
  return matter.stringify(body, fm)
}
async function publish(content, slug) {
  const res = await fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PASSWORD, filename: `${slug}.md`, content }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`发布失败 ${res.status}: ${data.error || ''}`)
  return data
}
async function indexNow(url) {
  if (!INDEXNOW_KEY) return
  const body = {
    host: new URL(SITE).host,
    key: INDEXNOW_KEY,
    keyLocation: `${BLOG}/indexnow.txt`,
    urlList: [url, `${BLOG}/`, `${BLOG}/sitemap.xml`],
  }
  try {
    const res = await fetch('https://api.indexnow.org/indexnow', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    log(`IndexNow ${res.status}`)
  } catch (e) { log(`IndexNow 失败: ${e.message}`) }
}

async function main() {
  if (!PASSWORD) throw new Error('BLOG_PASSWORD/APP_PASSWORD 未配置')
  const posts = loadPosts()
  const used = loadUsed()
  const topic = pickTopic(used)
  log(`选题 ${topic.id} / ${topic.title}`)
  let content
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      content = await generate(topic, posts)
      break
    } catch (e) {
      log(`第 ${attempt} 次生成校验失败: ${e.message}`)
      if (attempt === 2) throw e
    }
  }
  const parsed = matter(content)
  if (DRY) {
    console.log(content)
    log('dry-run，不发布')
    return
  }
  const data = await publish(content, parsed.data.slug)
  saveUsed([...used, topic.id])
  log(`已发布 ${data.title} ${data.url}`)
  await indexNow(data.url)
}

main().catch(e => { log('失败: ' + e.message); process.exit(1) })
