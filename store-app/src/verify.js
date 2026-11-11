import path from 'node:path'
import crypto from 'node:crypto'
import semver from 'semver'
import unzipper from 'unzipper'

/** 包体硬限制（文档 §6.9 / §10.2） */
export const LIMITS = {
  maxZipBytes: 50 * 1024 * 1024,
  maxUnzippedBytes: 120 * 1024 * 1024,
  maxFileBytes: 10 * 1024 * 1024,
  maxEntries: 300,
  maxRatio: 200,
}

/** 包内允许的文件类型：全是静态素材，一个可执行文件都不放行 */
const ALLOWED_EXT = new Set([
  '.json', '.md', '.txt', '.csv', '.yaml', '.yml',
  '.wav', '.mp3', '.ogg', '.m4a', '.flac',
  '.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg',
  '.ttf', '.otf', '.woff', '.woff2',
])

const RUNTIMES = new Set(['data', 'prompt', 'hosted', 'local'])
const VOICE = new Set(['audio', 'lyrics'])

const issue = (field, code, message) => ({ field, code, message })

function isSafeEntryPath(p) {
  if (!p || p.includes('\\') || p.includes('\0')) return false
  if (p.startsWith('/') || /^[a-zA-Z]:/.test(p)) return false
  const parts = p.split('/')
  if (parts.some((s) => s === '..' || s === '.' || s === '')) return false
  return true
}

/**
 * 结构校验：zip 安全（路径穿越 / 炸弹 / 可执行文件）+ 必备文件。
 * 只读中央目录，不解压全部内容。
 */
export async function inspectZip(buffer) {
  const issues = []
  if (!buffer || buffer.length === 0) {
    return { ok: false, issues: [issue('file', 'EMPTY', '包是空的')] }
  }
  if (buffer.length > LIMITS.maxZipBytes) {
    return {
      ok: false,
      issues: [issue('file', 'TOO_LARGE', `包体超过 ${LIMITS.maxZipBytes / 1024 / 1024}MB`)],
    }
  }

  let zip
  try {
    zip = await unzipper.Open.buffer(buffer)
  } catch {
    return { ok: false, issues: [issue('file', 'NOT_ZIP', '不是合法的 zip 文件')] }
  }

  const files = zip.files.filter((f) => f.type === 'File')
  if (files.length === 0) issues.push(issue('file', 'NO_FILES', '包里没有文件'))
  if (files.length > LIMITS.maxEntries) {
    issues.push(issue('file', 'TOO_MANY_FILES', `文件数超过 ${LIMITS.maxEntries}`))
  }

  // 不能只信 zip 中央目录声明的大小：unzipper 真正解压时会展开实际内容。
  // 逐个流式读出，边读边计数，超过单文件/总上限立刻停，防止声明 1KB、实际 30MB 的炸弹。
  let total = 0
  const actualSizes = new Map()
  for (const f of files) {
    if (!isSafeEntryPath(f.path)) {
      issues.push(issue(f.path, 'UNSAFE_PATH', '路径不合法（绝对路径 / 上跳 / 反斜杠）'))
      continue
    }
    const ext = path.extname(f.path).toLowerCase()
    if (!ALLOWED_EXT.has(ext)) {
      issues.push(issue(f.path, 'FORBIDDEN_FILE', `不允许的文件类型 ${ext || '(无扩展名)'}`))
    }
    const declared = f.uncompressedSize || 0
    let actual = 0
    try {
      const stream = f.stream()
      for await (const chunk of stream) {
        actual += chunk.length
        if (actual > LIMITS.maxFileBytes || total + actual > LIMITS.maxUnzippedBytes) {
          stream.destroy()
          break
        }
      }
    } catch (e) {
      issues.push(issue(f.path, 'ENTRY_UNREADABLE', '条目无法解压: ' + e.message))
      continue
    }
    actualSizes.set(f.path, actual)
    total += actual
    if (actual > LIMITS.maxFileBytes) {
      issues.push(issue(f.path, 'FILE_TOO_LARGE', '单文件解压后超过 10MB'))
    }
    if (actual > declared + 1024) {
      issues.push(issue(f.path, 'SIZE_MISMATCH', '条目声明大小与实际解压大小不一致'))
    }
    const ratio = actual / Math.max(f.compressedSize || 1, 1)
    if (actual > 1024 * 1024 && ratio > LIMITS.maxRatio) {
      issues.push(issue(f.path, 'SUSPICIOUS_RATIO', '压缩比异常，疑似 zip 炸弹'))
    }
    if (total > LIMITS.maxUnzippedBytes) {
      issues.push(issue('file', 'UNZIPPED_TOO_LARGE', '解压后总大小超过 120MB'))
      break
    }
  }

  const byName = new Map(files.map((f) => [f.path, f]))
  const manifestEntry = byName.get('manifest.json')
  if (!manifestEntry) {
    issues.push(issue('manifest.json', 'MISSING_MANIFEST', '根目录必须有 manifest.json'))
    return { ok: false, issues }
  }

  const hasReadme = [...byName.keys()].some((p) => /^readme(\.md)?$/i.test(p))
  if (!hasReadme) {
    issues.push(issue('README.md', 'MISSING_README', '根目录必须有 README.md'))
  }

  let manifest
  try {
    const raw = (await manifestEntry.buffer()).toString('utf8').replace(/^\uFEFF/, '')
    manifest = JSON.parse(raw)
  } catch {
    issues.push(issue('manifest.json', 'BAD_JSON', 'manifest.json 不是合法 JSON'))
    return { ok: false, issues }
  }

  const readmeEntry = byName.get('README.md') || byName.get('readme.md')
  const readme = readmeEntry ? (await readmeEntry.buffer()).toString('utf8') : null
  const changelogEntry = [...byName.entries()].find(([p]) => /^changelog\.md$/i.test(p))?.[1]
  const changelog = changelogEntry ? (await changelogEntry.buffer()).toString('utf8') : null

  return {
    ok: issues.length === 0,
    issues,
    manifest,
    readme,
    changelog,
    files: files.map((f) => ({ path: f.path, size: actualSizes.get(f.path) || 0 })),
    sha256: crypto.createHash('sha256').update(buffer).digest('hex'),
    size: buffer.length,
  }
}

/** 语义校验：字段规则（文档 §5.3），逐条报 field/code/message */
export function validateManifest(m) {
  const issues = []
  const need = (cond, field, code, msg) => {
    if (!cond) issues.push(issue(field, code, msg))
    return cond
  }

  if (!m || typeof m !== 'object') {
    return [issue('manifest', 'NOT_OBJECT', 'manifest 必须是对象')]
  }

  need(m.schemaVersion === 2, 'manifest.schemaVersion', 'BAD_SCHEMA', 'schemaVersion 必须为 2')
  need(
    typeof m.id === 'string' && /^[a-z0-9][a-z0-9._-]{2,48}$/.test(m.id),
    'manifest.id',
    'BAD_ID',
    'id 只能是小写字母数字与 . _ -，长度 3–49',
  )
  const nameLen = typeof m.name === 'string' ? [...m.name].length : 0
  need(nameLen >= 2 && nameLen <= 20, 'manifest.name', 'BAD_NAME', '名称 2–20 字')
  need(
    typeof m.version === 'string' && semver.valid(m.version) === m.version,
    'manifest.version',
    'BAD_VERSION',
    '版本必须是严格 semver（x.y.z）',
  )
  need(m.apiVersion === 1, 'manifest.apiVersion', 'BAD_API', 'apiVersion 当前只支持 1')
  need(RUNTIMES.has(m.runtime), 'manifest.runtime', 'BAD_RUNTIME', 'runtime 必须是 data/prompt/hosted/local')
  const descLen = typeof m.description === 'string' ? [...m.description].length : 0
  need(descLen > 0 && descLen <= 80, 'manifest.description', 'BAD_DESC', '描述必填且不超过 80 字')
  need(!!m.author?.name, 'manifest.author.name', 'MISSING_AUTHOR', '必须署名作者')
  need(!!m.license, 'manifest.license', 'MISSING_LICENSE', '必须声明许可证（SPDX）')
  need(
    Array.isArray(m.tags) && m.tags.length >= 1 && m.tags.length <= 6,
    'manifest.tags',
    'BAD_TAGS',
    '标签 1–6 个',
  )
  need(
    typeof m.compat?.minHostVersion === 'string' &&
      semver.valid(m.compat.minHostVersion) === m.compat.minHostVersion,
    'manifest.compat.minHostVersion',
    'BAD_COMPAT',
    'minHostVersion 必须是 semver',
  )

  need(!!m.intent?.id, 'manifest.intent.id', 'MISSING_INTENT', '必须声明 intent.id')
  const ex = m.intent?.examples
  if (!Array.isArray(ex) || ex.length < 5 || ex.length > 10) {
    issues.push(issue('manifest.intent.examples', 'BAD_EXAMPLES', '示例句 5–10 条'))
  } else {
    ex.forEach((s, i) => {
      const len = typeof s === 'string' ? [...s.trim()].length : 0
      if (len < 3) {
        issues.push(
          issue(`manifest.intent.examples[${i}]`, 'EXAMPLE_TOO_SHORT', '示例句至少 3 个字'),
        )
      }
    })
  }

  if (!Array.isArray(m.actions) || m.actions.length === 0) {
    issues.push(issue('manifest.actions', 'MISSING_ACTIONS', '至少声明 1 个 action'))
  } else {
    m.actions.forEach((a, i) => {
      need(!!a?.name, `manifest.actions[${i}].name`, 'BAD_ACTION', 'action 缺少 name')
      need(
        Array.isArray(a?.returns) && a.returns.length > 0,
        `manifest.actions[${i}].returns`,
        'BAD_ACTION',
        'action 必须声明 returns',
      )
    })
    const returnsAudio = m.actions.some((a) => (a?.returns || []).includes('audio'))
    if (returnsAudio) {
      need(
        VOICE.has(m.voice?.strategy),
        'manifest.voice.strategy',
        'MISSING_VOICE',
        '返回音频时必须声明 voice.strategy（audio / lyrics）',
      )
    }
  }

  const outbound = m.permissions?.network?.outbound
  if (outbound !== undefined && !Array.isArray(outbound)) {
    issues.push(issue('manifest.permissions.network.outbound', 'BAD_OUTBOUND', 'outbound 必须是数组'))
  }

  if (m.runtime === 'hosted') {
    const ep = m.hosted?.endpoint
    if (!need(!!ep, 'manifest.hosted.endpoint', 'MISSING_ENDPOINT', 'hosted 必须声明 endpoint')) {
      // 已经报了缺失，不再判断格式
    } else {
      let url = null
      try {
        url = new URL(ep)
      } catch {
        issues.push(issue('manifest.hosted.endpoint', 'BAD_ENDPOINT', 'endpoint 不是合法 URL'))
      }
      if (url) {
        if (url.protocol !== 'https:') {
          issues.push(issue('manifest.hosted.endpoint', 'INSECURE_ENDPOINT', 'endpoint 必须用 HTTPS'))
        }
        const hosts = Array.isArray(outbound) ? outbound : []
        if (!hosts.some((h) => h === url.hostname)) {
          issues.push(
            issue(
              'manifest.permissions.network.outbound',
              'DOMAIN_NOT_DECLARED',
              `endpoint 域名 ${url.hostname} 未在 permissions 中声明`,
            ),
          )
        }
      }
    }
  }

  if (m.runtime === 'local') {
    need(
      !!m.local && typeof m.local === 'object',
      'manifest.local',
      'MISSING_LOCAL',
      'local 型必须声明 local 块（endpointHint / invoke）',
    )
    const localPerm = m.permissions?.localService
    need(
      localPerm === true || localPerm?.allow === true,
      'manifest.permissions.localService',
      'LOCAL_NOT_DECLARED',
      'local 型必须声明 permissions.localService（true 或 {allow:true}）',
    )
  }

  if (m.runtime === 'prompt') {
    const sys = m.prompt?.system
    if (!sys || typeof sys !== 'string') {
      issues.push(issue('manifest.prompt.system', 'MISSING_TEMPLATE', 'prompt 型必须声明 prompt.system（包内模板路径）'))
    } else if (!isSafeEntryPath(sys) || !/\.(md|txt)$/i.test(sys)) {
      issues.push(issue('manifest.prompt.system', 'BAD_TEMPLATE', '模板必须是包内相对的 .md / .txt 路径'))
    }
    const mt = m.prompt?.maxTokens
    if (mt !== undefined && mt !== null && (mt < 100 || mt > 1000)) {
      issues.push(issue('manifest.prompt.maxTokens', 'BAD_MAX_TOKENS', 'maxTokens 需在 100–1000 之间'))
    }
    const tp = m.prompt?.temperature
    if (tp !== undefined && tp !== null && (tp < 0 || tp > 2)) {
      issues.push(issue('manifest.prompt.temperature', 'BAD_TEMPERATURE', 'temperature 需在 0–2 之间'))
    }
    const allowed = new Set(['date', 'profile'])
    for (const a of m.actions || []) {
      for (const k of Object.keys(a?.params || {})) allowed.add(k)
    }
    if (m.data && typeof m.data === 'object') {
      for (const [k, v] of Object.entries(m.data)) {
        if (!/^[a-z][a-z0-9_]{0,30}$/.test(k)) {
          issues.push(issue(`manifest.data.${k}`, 'BAD_DATA_KEY', 'data 键需匹配 ^[a-z][a-z0-9_]{0,30}$'))
          continue
        }
        if (typeof v !== 'string' || !isSafeEntryPath(v) || !/\.(json|md|txt|csv)$/i.test(v)) {
          issues.push(issue(`manifest.data.${k}`, 'BAD_DATA_PATH', 'data 素材必须是包内相对的 .json/.md/.txt/.csv 路径'))
        }
        allowed.add(k)
      }
    }
    for (const v of m.variables || []) {
      if (typeof v !== 'string' || !/^[a-z][a-z0-9_]{0,30}$/.test(v)) {
        issues.push(issue('manifest.variables', 'BAD_VARIABLE', `变量名不合法: ${v}`))
      } else if (!allowed.has(v)) {
        issues.push(issue('manifest.variables', 'VARIABLE_NOT_ALLOWED', `变量不在白名单（date/profile/action 参数/data 键）: ${v}`))
      }
    }
  }

  const policy = m.memory?.policy
  if (policy && policy !== 'none' && m.permissions?.memory?.write !== true) {
    issues.push(
      issue(
        'manifest.memory.policy',
        'MEMORY_INCONSISTENT',
        '写记忆必须同时声明 permissions.memory.write = true',
      ),
    )
  }

  const quota = m.quota?.perUserPerDay
  if (quota !== undefined && quota !== null && (quota < 1 || quota > 50)) {
    issues.push(issue('manifest.quota.perUserPerDay', 'BAD_QUOTA', '每人每天额度必须在 1–50 之间'))
  }

  return issues
}

/** prompt 注入关键词（与宿主 PromptTemplateScanner 保持一致） */
const INJECTION_KEYWORDS = [
  'ignore previous', 'ignore all previous', 'ignore the above',
  'disregard previous', 'disregard all', 'forget your instructions',
  'forget all instructions', 'you are now', 'act as if',
  'new instructions', 'system prompt', 'reveal your prompt',
  'print your instructions', 'api key', 'apikey', 'secret key',
  '[inst]', '<</', '<<sys', '</system',
  '忽略以上', '忽略之前', '忽略所有', '忘掉你的指令', '忘记之前的指令',
  '你现在是', '扮演一个', '系统提示词', '你的提示词', '输出你的指令',
]

/** 上传时同步扫模板（§10.6）：注入话术 + 占位符 ⊆ manifest.variables */
async function scanPromptTemplate(buffer, m, issues) {
  if (m.runtime !== 'prompt' || !m.prompt?.system) return
  try {
    const zip = await unzipper.Open.buffer(buffer)
    const entry = zip.files.find((f) => f.type === 'File' && f.path === m.prompt.system)
    if (!entry) {
      issues.push(issue('manifest.prompt.system', 'TEMPLATE_NOT_FOUND', '模板文件不存在于包内'))
      return
    }
    if ((entry.uncompressedSize || 0) > 16 * 1024) {
      issues.push(issue('manifest.prompt.system', 'TEMPLATE_TOO_LARGE', '模板不能超过 16KB'))
      return
    }
    const text = (await entry.buffer()).toString('utf8')
    const lower = text.toLowerCase()
    for (const kw of INJECTION_KEYWORDS) {
      if (lower.includes(kw)) {
        issues.push(issue('manifest.prompt.system', 'PROMPT_INJECTION', `模板包含疑似注入话术: 「${kw}」`))
      }
    }
    const declared = new Set(m.variables || [])
    for (const match of text.matchAll(/\{(\w+)\}/g)) {
      if (!declared.has(match[1])) {
        issues.push(
          issue('manifest.prompt.system', 'VARIABLE_NOT_DECLARED', `模板占位符 {${match[1]}} 未在 manifest.variables 里声明`),
        )
      }
    }
  } catch (e) {
    issues.push(issue('manifest.prompt.system', 'TEMPLATE_UNREADABLE', '模板读取失败: ' + e.message))
  }
}

/** 完整的「体检」：结构 + 语义 + prompt 模板扫描，上传和安装都用这一套 */
export async function healthCheck(buffer) {
  const pkg = await inspectZip(buffer)
  if (!pkg.ok || !pkg.manifest) {
    return { ok: false, issues: pkg.issues, pkg }
  }
  const semantic = validateManifest(pkg.manifest)
  await scanPromptTemplate(buffer, pkg.manifest, semantic)
  return { ok: semantic.length === 0, issues: semantic, pkg }
}
