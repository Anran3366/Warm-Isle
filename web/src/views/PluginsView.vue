<template>
  <div class="plugins-page">
    <section class="card hero">
      <div class="hero-main">
        <div class="hero-icon" aria-hidden="true">
          <el-icon :size="24"><Grid /></el-icon>
        </div>
        <div class="hero-text">
          <h2 class="hero-title">插件商店</h2>
          <p class="hero-desc">
            插件是「能力包」：装一次，所有账号按需启用。未启用时她什么都不会多，也不会多说一句话。
          </p>
        </div>
      </div>
      <div class="hero-control">
        <el-tag :type="pluginEnabled ? 'success' : 'info'" size="large">
          {{ pluginEnabled ? '插件链路已开启' : '插件链路未开启' }}
        </el-tag>
      </div>
    </section>

    <el-tabs v-model="tab" class="plugin-tabs" @tab-change="onTabChange">
      <el-tab-pane label="插件商店" name="store">
        <section class="card">
          <div class="card-head">
            <h3>插件商店</h3>
            <div class="head-actions">
              <span v-if="storeStatus.count" class="muted">{{ storeStatus.count }} 个已上架</span>
              <el-tag v-if="upgradableCount" size="small" type="warning" effect="dark">
                {{ upgradableCount }} 个可升级
              </el-tag>
              <el-button :loading="storeLoading" @click="loadStore(true)">刷新</el-button>
            </div>
          </div>

          <el-alert
            v-if="!storeStatus.enabled"
            type="info"
            :closable="false"
            title="没有配置商店地址"
            description="设置 qqbot.plugin.store.url 后，这里会出现可安装的插件。不配置也不影响已装插件。"
          />
          <el-alert
            v-else-if="storeStatus.needsPublicKey"
            type="error"
            :closable="false"
            title="商店公钥未配置，商店响应会被拒绝"
            description="把商店 /api/v1/pubkey 的 publicKey 填到 PLUGIN_STORE_PUBLIC_KEY，或显式关闭 PLUGIN_STORE_SIGNATURE_REQUIRED。"
          />
          <el-alert
            v-else-if="!storeAvailable"
            type="warning"
            :closable="false"
            :title="storeReason || '商店暂不可用'"
            description="已安装的插件不受影响，商店恢复后刷新即可。"
          />
          <el-empty
            v-else-if="!storeLoading && storePlugins.length === 0"
            description="商店里还没有上架的插件"
          />
          <el-table v-else v-loading="storeLoading" :data="storePlugins" row-key="id">
            <el-table-column label="插件" min-width="260">
              <template #default="{ row }">
                <div class="plugin-name">
                  <b>{{ row.name }}</b>
                  <el-tag size="small" effect="plain">{{ row.runtime }}</el-tag>
                  <el-tag v-if="row.installed" size="small" type="success">
                    已装 {{ row.installedVersion }}
                  </el-tag>
                  <el-tag v-if="row.upgradable" size="small" type="warning">
                    可升级 {{ row.latest }}
                  </el-tag>
                </div>
                <div class="plugin-desc">{{ row.summary }}</div>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="90">
              <template #default="{ row }">{{ row.latest }}</template>
            </el-table-column>
            <el-table-column label="下载" width="80">
              <template #default="{ row }">{{ row.downloads }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="right">
              <template #default="{ row }">
                <el-button
                  text
                  type="primary"
                  :disabled="row.installed && !row.upgradable"
                  @click="openStorePreview(row)"
                >
                  {{ row.installed ? (row.upgradable ? '升级' : '已装') : '安装' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="已安装" name="installed">
    <section class="card">
      <div class="card-head">
        <h3>已安装</h3>
        <div class="head-actions">
          <el-upload
            :show-file-list="false"
            accept=".zip"
            :http-request="onPickFile"
            :disabled="installing"
          >
            <el-button type="primary" :loading="installing">
              安装本地 zip
            </el-button>
          </el-upload>
          <el-button :loading="healthChecking" @click="checkHealth">检查服务版本</el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <el-empty v-if="!loading && installed.length === 0" description="还没有插件。先装一个「唱歌」试试。" />

      <el-table v-else v-loading="loading" :data="installed" row-key="id" class="plugin-table">
        <el-table-column label="插件" min-width="220">
          <template #default="{ row }">
            <div class="plugin-name">
              <el-button link type="primary" class="plugin-link" @click="openDetail(row)">{{ row.name }}</el-button>
              <el-tag size="small" effect="plain">{{ row.runtime }}</el-tag>
              <el-tooltip v-if="row.revoked" :content="'商店已吊销：' + (row.revokedReason || '未说明原因') + '（签名上线前暂不阻断，建议卸载）'" placement="top">
                <el-tag size="small" type="danger" effect="dark">已拉黑</el-tag>
              </el-tooltip>
              <el-tag v-if="row.status !== 'installed'" size="small" type="danger">{{ row.status }}</el-tag>
              <el-tag v-if="storeUpgrade(row)" size="small" type="warning" effect="dark">
                可升级 {{ storeUpgrade(row)!.latest }}
              </el-tag>
              <el-tag v-if="row.remoteStatus === 'outdated'" size="small" type="warning">
                服务端 {{ row.remoteVersion || '?' }}
              </el-tag>
              <el-tag v-else-if="row.remoteStatus === 'unreachable'" size="small" type="danger">服务不可达</el-tag>
              <el-tag v-else-if="row.remoteStatus === 'unconfigured'" size="small" type="info">未配置地址</el-tag>
              <el-tag v-else-if="row.remoteStatus === 'unknown'" size="small" type="info">服务未报版本</el-tag>
            </div>
            <div class="plugin-desc">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="意图" width="140">
          <template #default="{ row }">
            <code class="intent">{{ row.intent || '-' }}</code>
          </template>
        </el-table-column>
        <el-table-column label="启用账号" min-width="220">
          <template #default="{ row }">
            <div class="bot-switches">
              <span v-for="botId in bots" :key="botId" class="bot-switch">
                <el-switch
                  :model-value="row.enabledFor.includes(botId)"
                  size="small"
                  @change="(v: string | number | boolean) => toggle(row, botId, Boolean(v))"
                />
                <span class="bot-id">{{ botId }}</span>
              </span>
              <span v-if="bots.length === 0" class="muted">暂无账号</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button v-if="storeUpgrade(row)" text type="warning" @click="openStorePreview(storeUpgrade(row)!)">升级</el-button>
            <el-button text type="primary" @click="openDetail(row, true)">配置</el-button>
            <el-button text type="danger" @click="uninstall(row)">卸载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
      </el-tab-pane>

      <el-tab-pane label="运行记录" name="runs">
    <section class="card">
      <div class="card-head">
        <h3>最近运行</h3>
        <span class="muted">只记匿名运行信息，不含聊天内容与 QQ 号</span>
      </div>
      <el-empty v-if="runs.length === 0" description="还没有运行记录" />
      <el-table v-else :data="runs" size="small" max-height="320">
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="插件" min-width="180">
          <template #default="{ row }">{{ row.pluginId }} <span class="muted">v{{ row.version }}</span></template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ok' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="150" />
        <el-table-column prop="latencyMs" label="耗时(ms)" width="100" />
      </el-table>
    </section>
      </el-tab-pane>

      <el-tab-pane v-if="storeStatus.canPublish" label="上架插件" name="publish">
        <section class="card publish-card">
          <div class="card-head publish-head">
            <div class="publish-title">
              <div class="publish-icon"><el-icon><UploadFilled /></el-icon></div>
              <div>
                <h3>上架新插件 / 新版本</h3>
                <p>提交后进入所有者的待审队列，通过后所有部署者可见。</p>
              </div>
            </div>
            <el-tag size="small" effect="plain" type="info">仅商店所有者审核</el-tag>
          </div>

          <div class="publish-grid">
            <div class="publish-main">
              <div
                class="publish-drop"
                :class="{ 'has-file': publishFile }"
                @click="publishInput?.click()"
                @dragover.prevent
                @drop.prevent="onDropPublish"
              >
                <input ref="publishInput" type="file" accept=".zip" hidden @click.stop @change="onPublishFile" />
                <div class="drop-icon"><el-icon><UploadFilled /></el-icon></div>
                <strong>{{ publishFile ? '已选择插件包' : '把插件 zip 拖到这里' }}</strong>
                <p>{{ publishFile ? '点击可重新选择，或拖入新的 zip' : '也可以点击选择文件，只支持 .zip' }}</p>
              </div>

              <div v-if="publishFile" class="publish-file">
                <div class="file-badge">ZIP</div>
                <div class="file-info">
                  <div class="file-name" :title="publishFile.name">{{ publishFile.name }}</div>
                  <div class="file-meta">{{ fmtBytes(publishFile.size) }} · 等待提交体检</div>
                </div>
                <el-button text size="small" @click.stop="clearPublish">移除</el-button>
              </div>

              <div class="publish-actions">
                <el-button
                  type="primary"
                  size="large"
                  :loading="publishing"
                  :disabled="!publishFile"
                  @click="doPublish"
                >
                  {{ publishing ? '正在上传并体检…' : '提交并体检' }}
                </el-button>
                <span class="publish-hint">体检通过才会进入待审队列</span>
              </div>

              <el-alert
                v-if="publishResult"
                class="publish-result"
                :type="publishResult.ok ? 'success' : 'error'"
                :closable="false"
                show-icon
                :title="publishResult.ok ? '提交成功，等待所有者审核' : '提交失败'"
                :description="publishResult.text"
              />
            </div>

            <aside class="publish-side">
              <h4>上架前检查</h4>
              <ul class="publish-checklist">
                <li><span>✓</span>根目录有 <code>manifest.json</code></li>
                <li><span>✓</span>id 带命名空间，版本是 semver</li>
                <li><span>✓</span>不含可执行文件，素材在包内</li>
                <li><span>✓</span>README 写明用法与权限</li>
              </ul>

              <div class="publish-flow">
                <span>1 上传</span><i>→</i><span>2 所有者审核</span><i>→</i><span>3 部署者可见</span>
              </div>

              <a
                class="publish-doc"
                href="https://web.example.com/blog/post/warm-isle-plugin-dev-manual"
                target="_blank"
                rel="noopener"
              >
                查看插件开发手册 →
              </a>
            </aside>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane v-if="storeStatus.canReview" label="上架审核" name="review">
        <section class="card">
          <div class="card-head">
            <h3>待审核</h3>
            <el-button size="small" :loading="pendingLoading" @click="loadPending">刷新</el-button>
          </div>
          <el-empty v-if="!pendingLoading && pending.length === 0" description="没有待审插件" />
          <el-table v-else v-loading="pendingLoading" :data="pending" size="small">
            <el-table-column prop="pluginId" label="插件" min-width="170" />
            <el-table-column prop="version" label="版本" width="90" />
            <el-table-column label="来源" min-width="150">
              <template #default="{ row }">{{ row.publisher || '部署实例' }}</template>
            </el-table-column>
            <el-table-column label="提交时间" width="175">
              <template #default="{ row }">{{ fmtTime(row.submittedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="reviewItem(row, 'approve')">通过</el-button>
                <el-button text type="danger" @click="reviewItem(row, 'reject')">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="storePreviewVisible" :title="storePreview?.upgrading ? '升级插件' : '确认安装'" width="560px">
      <div v-if="storePreview">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="插件">
            {{ storePreview.manifest?.name || storePreview.pluginId }}
            <code>{{ storePreview.pluginId }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="版本">
            {{ storePreview.version }}（{{ storePreview.manifest?.runtime || '-' }}）
          </el-descriptions-item>
          <el-descriptions-item label="意图">{{ storePreview.manifest?.intent || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联网">
            {{ (storePreview.manifest?.outbound || []).join('、') || '不联网' }}
          </el-descriptions-item>
          <el-descriptions-item label="写记忆">
            {{ storePreview.manifest?.memoryWrite && storePreview.manifest.memoryWrite !== 'none'
              ? '会写「我做过的事」，每天最多 5 条'
              : '不写' }}
          </el-descriptions-item>
          <el-descriptions-item label="发语音">
            {{ storePreview.manifest?.returnsAudio ? '会发语音条' : '不发语音' }}
          </el-descriptions-item>
          <el-descriptions-item label="包大小">{{ fmtBytes(storePreview.size) }}</el-descriptions-item>
          <el-descriptions-item v-if="storePreview.changelog" label="更新说明">
            <pre class="changelog">{{ storePreview.changelog }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="校验值">
            <code class="addr">{{ (storePreview.sha256 || '').slice(0, 16) }}…</code>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="storePreview.ok === false"
          style="margin-top: 12px"
          type="error"
          :closable="false"
          title="这个包没通过校验"
          :description="(storePreview.issues || []).map((i: any) => i.message).join('；')"
        />
        <p class="preview-note">装完默认不启用，需要在「已安装」里打开对应账号的开关。</p>
        <p v-if="storePreview?.upgrading" class="preview-note">
          升级只替换本地包与 manifest；hosted 的代码在作者服务器上，local 的服务需要按 SETUP 自行更新。
        </p>
      </div>
      <template #footer>
        <el-button @click="storePreviewVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="storeInstalling"
          :disabled="storePreview?.ok === false"
          @click="confirmStoreInstall"
        >
          {{ storePreview?.upgrading ? '确认升级' : '确认安装' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="安装确认" width="520px">
      <div v-if="preview">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="插件">{{ preview.name }} <code>{{ preview.id }}</code></el-descriptions-item>
          <el-descriptions-item label="版本">{{ preview.version }}（{{ preview.runtime }}）</el-descriptions-item>
          <el-descriptions-item label="意图">{{ preview.intent || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联网">
            {{ preview.outbound.length ? preview.outbound.join('、') : '不联网' }}
          </el-descriptions-item>
          <el-descriptions-item label="写记忆">
            {{ preview.memoryWrite === 'none' ? '不写' : '会写「我做过的事」，每天最多 5 条' }}
          </el-descriptions-item>
        </el-descriptions>
        <p class="preview-note">装完默认不启用，需要在上表里打开。</p>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button type="primary" :loading="installing" @click="confirmInstall">确认安装</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="600px" class="detail-drawer">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-alert
            v-if="detail.revoked"
            type="error"
            :closable="false"
            title="这个插件已被商店吊销"
            :description="(detail.revokedReason || '未说明原因') + '。签名机制上线前暂不阻断触发，但建议尽快卸载。'"
            class="revoked-alert"
          />
          <div class="detail-section">
            <h4>基本信息</h4>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="标识"><code>{{ detail.id }}</code></el-descriptions-item>
              <el-descriptions-item label="版本">{{ detail.version }}</el-descriptions-item>
              <el-descriptions-item label="运行形态">{{ runtimeLabel }}</el-descriptions-item>
              <el-descriptions-item label="作者">{{ detail.manifest?.author?.name || '-' }}</el-descriptions-item>
              <el-descriptions-item label="许可">{{ detail.manifest?.license || '-' }}</el-descriptions-item>
              <el-descriptions-item label="来源">{{ detail.source === 'upload' ? '本地上传' : detail.source }}</el-descriptions-item>
              <el-descriptions-item label="给谁开了">{{ enabledText }}</el-descriptions-item>
              <el-descriptions-item label="校验值">
                <code class="addr">{{ (detail.sha256 || '').slice(0, 16) }}…</code>
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <div ref="configSection" class="detail-section">
            <h4>配置</h4>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="运行形态">{{ runtimeLabel }}</el-descriptions-item>
              <el-descriptions-item label="连接方式">{{ connectionType }}</el-descriptions-item>
              <el-descriptions-item v-for="r in endpointRows" :key="r.label" :label="r.label">
                <code class="addr">{{ r.value }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="联网白名单">
                {{ outbound.length ? outbound.join('、') : '不联网' }}
              </el-descriptions-item>
              <el-descriptions-item label="额度">
                每人每天 {{ detail.manifest?.quota?.perUserPerDay ?? '不限' }} 次 · 全局每小时
                {{ detail.manifest?.quota?.globalPerHour ?? '不限' }} 次
              </el-descriptions-item>
              <el-descriptions-item label="语音">{{ voiceText }}</el-descriptions-item>
              <el-descriptions-item label="写记忆">{{ memoryWriteText }}</el-descriptions-item>
              <el-descriptions-item label="装在哪"><code class="addr">{{ detail.dir }}</code></el-descriptions-item>
            </el-descriptions>

            <div v-if="detail.manifest?.runtime === 'local'" class="endpoint-editor">
              <div class="endpoint-row">
                <el-input
                  v-model="endpointInput"
                  size="small"
                  placeholder="http://host.docker.internal:34001"
                />
                <el-button size="small" :loading="endpointTesting" @click="testEndpoint">
                  测试连接
                </el-button>
                <el-button size="small" type="primary" :loading="endpointSaving" @click="saveEndpoint">
                  保存
                </el-button>
              </div>
              <p class="detail-note">
                宿主只调用这个地址，不会启动或管理进程。宿主机上的服务填
                <code>http://host.docker.internal:端口</code>，兄弟容器填
                <code>http://容器名:端口</code>。保存前会先请求一次健康检查。
              </p>
            </div>

            <p v-if="!endpointRows.length" class="detail-note">
              <template v-if="detail.manifest?.runtime === 'data'">
                数据型插件：素材（音频 / 图片 / 文本）全部在包里，运行时不连任何外部服务，没有需要填写的地址。
              </template>
              <template v-else>这个插件不需要填写地址。</template>
            </p>

            <h5 class="sub-head">可调项（{{ configFields.length }}）</h5>
            <el-table v-if="configFields.length" :data="configFields" size="small" border>
              <el-table-column label="配置" min-width="130">
                <template #default="{ row }">
                  <b>{{ row.label || row.key }}</b>
                  <div class="muted">{{ row.key }}</div>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="80">
                <template #default="{ row }">{{ row.type || 'text' }}</template>
              </el-table-column>
              <el-table-column label="默认值" width="90">
                <template #default="{ row }">{{ fmtDefault(row) }}</template>
              </el-table-column>
            </el-table>
            <p v-else class="detail-note">
              这个插件没有声明可调项：所有参数都写死在包内 manifest.json 里，后台没有可改的项。
            </p>

            <template v-if="assetsRows.length">
              <h5 class="sub-head">包内素材（{{ assetsRows.length }}）</h5>
              <el-table :data="assetsRows" size="small" border>
                <el-table-column prop="name" label="名称" width="110" />
                <el-table-column label="文件" min-width="170">
                  <template #default="{ row }"><code class="addr">{{ row.file }}</code></template>
                </el-table-column>
                <el-table-column label="时长" width="70">
                  <template #default="{ row }">
                    {{ row.durationMs ? Math.round(row.durationMs / 1000) + 's' : '-' }}
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </div>

          <div class="detail-section">
            <h4>怎么触发</h4>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="意图">{{ detail.manifest?.intent?.id || '-' }}</el-descriptions-item>
              <el-descriptions-item label="会接这些话">
                <span class="example-chips">
                  <el-tag v-for="ex in examples" :key="ex" size="small" effect="plain">{{ ex }}</el-tag>
                  <span v-if="!examples.length" class="muted">-</span>
                </span>
              </el-descriptions-item>
            </el-descriptions>
            <div v-if="actions.length" class="action-list">
              <div v-for="a in actions" :key="a.name" class="action-item">
                <div class="action-head">
                  <code>{{ a.name }}</code>
                  <span class="muted">{{ (a.returns || ['text']).join(' + ') }}</span>
                  <span class="muted">超时 {{ a.timeoutMs || 15000 }}ms</span>
                </div>
                <div class="action-desc">{{ a.description }}</div>
                <div v-if="paramsOf(a).length" class="action-params">
                  <el-tag
                    v-for="p in paramsOf(a)"
                    :key="p.name"
                    size="small"
                    :type="p.required ? 'warning' : 'info'"
                    effect="plain"
                  >
                    {{ p.name }}{{ p.required ? '（必填）' : '（可选）' }}
                  </el-tag>
                </div>
              </div>
            </div>
          </div>

          <div v-if="catalogEntries.length" class="detail-section">
            <h4>能做什么（{{ catalogEntries.length }} 项）</h4>
            <el-table :data="catalogEntries" size="small" border>
              <el-table-column prop="value" label="名称" width="120" />
              <el-table-column label="也认这些叫法" min-width="150">
                <template #default="{ row }">{{ (row.aliases || []).join('、') || '-' }}</template>
              </el-table-column>
              <el-table-column label="时长" width="72">
                <template #default="{ row }">
                  {{ row.durationMs ? Math.round(row.durationMs / 1000) + 's' : '-' }}
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="detail-section">
            <h4>权限（{{ pluginPermissions.length }}）</h4>
            <div v-if="pluginPermissions.length" class="perm-list">
              <div
                v-for="p in pluginPermissions"
                :key="p.key"
                class="perm-item"
                :class="`lv-${p.level || 'low'}`"
              >
                <div class="perm-head">
                  <b>{{ p.label || p.key }}</b>
                  <el-tag size="small" effect="plain" type="info">
                    {{ p.phase === 'install' ? '安装时说明' : '启用时确认' }}
                  </el-tag>
                </div>
                <div class="perm-detail">{{ p.detail }}</div>
              </div>
            </div>
            <p v-else class="detail-note">
              这个插件不联网、不写记忆、不连本机服务，启用时不需要额外确认。
            </p>

            <el-descriptions v-if="ackEntries.length" :column="1" size="small" border class="ack-table">
              <el-descriptions-item v-for="[bid, st] in ackEntries" :key="bid" :label="`账号 ${bid}`">
                <el-tag size="small" effect="plain" :type="st.enabled ? 'success' : 'info'">
                  {{ st.enabled ? '已启用' : '未启用' }}
                </el-tag>
                <el-tag
                  size="small"
                  effect="plain"
                  :type="st.confirmed ? 'success' : 'danger'"
                  style="margin-left: 6px"
                >
                  {{ st.confirmed ? '权限已确认' : '权限待确认' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>

            <p v-if="detail.privacyMode" class="detail-note">
              当前已开启隐私模式：联网型和本机服务型插件不可启用。
            </p>
          </div>

          <div class="detail-section">
            <h4>版本与回滚</h4>
            <el-table v-if="versionRows.length" :data="versionRows" size="small" border>
              <el-table-column label="版本" width="130">
                <template #default="{ row }">
                  <span>{{ row.version }}</span>
                  <el-tag v-if="row.current" size="small" type="success" effect="plain" style="margin-left: 4px">
                    当前
                  </el-tag>
                  <el-tag v-else-if="row.previous" size="small" type="warning" effect="plain" style="margin-left: 4px">
                    可回滚
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="90">
                <template #default="{ row }">{{ fmtBytes(row.sizeBytes) }}</template>
              </el-table-column>
              <el-table-column label="留在磁盘上的时间" min-width="150">
                <template #default="{ row }">{{ fmtTime(row.modifiedAt) }}</template>
              </el-table-column>
            </el-table>
            <div class="version-actions">
              <el-button
                size="small"
                :disabled="!canRollback"
                :loading="rollingBack"
                @click="rollback"
              >
                回滚到上一版
              </el-button>
              <span class="muted">
                旧版本保留 {{ versionsInfo?.keepDays ?? 7 }} 天；回滚只切代码，不动记忆
              </span>
            </div>
          </div>

        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

interface InstalledRow {
  id: string
  name: string
  version: string
  runtime: string
  status: string
  description: string
  intent?: string
  enabledFor: number[]
}

interface PreviewInfo {
  id: string
  name: string
  version: string
  runtime: string
  intent?: string
  outbound: string[]
  memoryWrite: string
}

const loading = ref(false)
const installing = ref(false)
const pluginEnabled = ref(false)
const bots = ref<number[]>([])
const installed = ref<InstalledRow[]>([])
const runs = ref<any[]>([])
const preview = ref<PreviewInfo | null>(null)
const previewVisible = ref(false)
const pendingFile = ref<File | null>(null)

// ── 商店（文档 §7.3 / §7.5）──────────────────────────────────────
const tab = ref<'store' | 'installed' | 'runs'>('store')
const storeStatus = ref<{ enabled: boolean; url?: string; count?: number; needsPublicKey?: boolean; canPublish?: boolean; canReview?: boolean }>({ enabled: false })
const pending = ref<any[]>([])
const pendingLoading = ref(false)
const publishFile = ref<File | null>(null)
const publishInput = ref<HTMLInputElement | null>(null)
const publishing = ref(false)
const publishResult = ref<{ ok: boolean; text: string } | null>(null)
const storeAvailable = ref(true)
const storeReason = ref('')
const storePlugins = ref<any[]>([])
const healthChecking = ref(false)
const storeById = computed(() => new Map(storePlugins.value.map((p: any) => [p.id, p])))
const upgradableCount = computed(() => storePlugins.value.filter((p: any) => p.upgradable).length)
function storeUpgrade(row: any): any {
  const p = storeById.value.get(row.id)
  return p && p.upgradable ? p : null
}
const storeLoading = ref(false)
const storePreviewVisible = ref(false)
const storePreview = ref<any>(null)
const storeInstalling = ref(false)

function fmtBytes(n?: number): string {
  if (!n) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

function onTabChange(name: string | number) {
  if (name === 'review') loadPending()
}

function setPublishFile(file: File | null) {
  publishResult.value = null
  if (!file) {
    publishFile.value = null
    return
  }
  if (!file.name.toLowerCase().endsWith('.zip')) {
    publishFile.value = null
    ElMessage.error('只支持 .zip 插件包')
    return
  }
  publishFile.value = file
}

function onPublishFile(e: Event) {
  const input = e.target as HTMLInputElement
  setPublishFile(input.files?.[0] || null)
  input.value = ''
}

function onDropPublish(e: DragEvent) {
  setPublishFile(e.dataTransfer?.files?.[0] || null)
}

function clearPublish() {
  publishFile.value = null
  publishResult.value = null
}

async function doPublish() {
  if (!publishFile.value) return
  publishing.value = true
  publishResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', publishFile.value)
    const r = await call<any>('/api/store/publish', { method: 'POST', body: fd })
    publishResult.value = {
      ok: true,
      text: `${r.pluginId} v${r.version} 已进入待审队列，所有者通过后会出现在商店。`,
    }
    publishFile.value = null
    ElMessage.success('已提交，等待审核')
  } catch (e: any) {
    publishResult.value = { ok: false, text: e.message || '上传失败' }
    ElMessage.error(publishResult.value.text)
  } finally {
    publishing.value = false
  }
}

async function loadPending() {
  if (!storeStatus.value.canReview) return
  pendingLoading.value = true
  try {
    const r = await call<any>('/api/store/admin/pending')
    pending.value = r.items || []
  } catch (e: any) {
    ElMessage.error(e.message || '读取待审列表失败')
  } finally {
    pendingLoading.value = false
  }
}

async function reviewItem(row: any, action: 'approve' | 'reject') {
  const label = action === 'approve' ? '通过' : '驳回'
  try {
    await ElMessageBox.confirm(`确定${label} ${row.pluginId} v${row.version}？`, '审核确认', {
      type: action === 'approve' ? 'success' : 'warning',
    })
    await call(`/api/store/admin/review/${row.submissionId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, note: '' }),
    })
    ElMessage.success(`已${label}`)
    loadPending()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e.message || `${label}失败`)
  }
}

async function loadStore(forceRefresh = false) {
  storeLoading.value = true
  try {
    if (forceRefresh) {
      await call('/api/store/refresh', { method: 'POST' }).catch(() => {})
    }
    const st = await call<any>('/api/store/status')
    storeStatus.value = st
    if (!st.enabled) {
      storeAvailable.value = false
      storeReason.value = ''
      storePlugins.value = []
      // 没配商店就退回「已安装」，别让人对着空 Tab 发愣
      tab.value = 'installed'
      return
    }
    if (st.canReview) loadPending()
    if (st.needsPublicKey) {
      storeAvailable.value = false
      storeReason.value = '商店公钥未配置'
      storePlugins.value = []
      return
    }
    const data = await call<any>('/api/store/plugins')
    storeAvailable.value = !!data.available
    storeReason.value = data.reason || ''
    storePlugins.value = data.plugins || []
  } catch (e: any) {
    storeAvailable.value = false
    storeReason.value = e.message || '商店暂不可用'
  } finally {
    storeLoading.value = false
  }
}

async function checkHealth() {
  healthChecking.value = true
  try {
    const res = await call<any>('/api/plugins/health-check', { method: 'POST' })
    ElMessage.success(`已检查 ${res.checked || 0} 个服务`)
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '检查失败')
  } finally {
    healthChecking.value = false
  }
}

async function openStorePreview(row: any) {
  storePreviewVisible.value = true
  storePreview.value = { pluginId: row.id, version: row.latest, ok: true, upgrading: !!row.upgradable }
  try {
    const preview = await call<any>('/api/store/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: row.id, version: row.latest }),
    })
    storePreview.value = { ...preview, upgrading: !!row.upgradable, changelog: row.changelog || null }
  } catch (e: any) {
    storePreview.value = { pluginId: row.id, version: row.latest, ok: false, issues: [{ message: e.message }] }
  }
}

async function confirmStoreInstall() {
  const p = storePreview.value
  if (!p?.sha256) {
    ElMessage.error('预览信息不完整，请重新打开')
    return
  }
  storeInstalling.value = true
  try {
    const res = await call<any>('/api/store/install', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: p.pluginId, version: p.version, sha256: p.sha256 }),
    })
    if (res.ok) {
      storePreviewVisible.value = false
      ElMessage.success(res.hint || '已安装')
      await Promise.all([load(), loadStore()])
    } else {
      ElMessage.error(res.issues?.[0]?.message || '安装失败')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '安装失败')
  } finally {
    storeInstalling.value = false
  }
}

// ── 插件详情（只读展示包内 manifest，不改状态）────────────────────
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<any>(null)
const configSection = ref<HTMLElement | null>(null)
// 版本与回滚（§7.4）：当前版 + 备着的上一版
const versionsInfo = ref<any>(null)
const rollingBack = ref(false)
const versionRows = computed<any[]>(() => versionsInfo.value?.versions || [])
const canRollback = computed(() => versionRows.value.some((v: any) => v.previous))
const pluginPermissions = computed<any[]>(() => detail.value?.permissions || [])
const ackEntries = computed<any[]>(() => Object.entries(detail.value?.ackState || {}))

function fmtTime(ms?: number): string {
  if (!ms) return '-'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function rollback() {
  const id = detail.value?.id
  if (!id) return
  const prev = versionRows.value.find((v: any) => v.previous)
  try {
    await ElMessageBox.confirm(
      `把「${detail.value.manifest?.name || id}」回滚到 ${prev?.version}？\n`
      + `当前版本 ${detail.value.version} 会留作下一次回滚点；记忆不回滚。`,
      '确认回滚',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  rollingBack.value = true
  try {
    const r = await call<any>(`/api/plugins/${encodeURIComponent(id)}/rollback`, { method: 'POST' })
    if (!r.ok) {
      ElMessage.error(r.message || '回滚失败')
      return
    }
    ElMessage.success(r.hint || r.message || '已回滚')
    await load()
    const row = installed.value.find((x) => x.id === id)
    if (row) await openDetail(row)
  } catch (e: any) {
    ElMessage.error(e.message || '回滚失败')
  } finally {
    rollingBack.value = false
  }
}

const detailTitle = computed(() =>
  detail.value ? `${detail.value.manifest?.name || detail.value.id} · 插件详情` : '插件详情',
)

// local 型：地址由部署者填，保存前必须先测通（文档 §7.6）
const endpointInput = ref('')
const endpointTesting = ref(false)
const endpointSaving = ref(false)

async function testEndpoint() {
  if (!detail.value || !endpointInput.value.trim()) return
  endpointTesting.value = true
  try {
    const r = await call<any>(`/api/plugins/${encodeURIComponent(detail.value.id)}/endpoint/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: endpointInput.value.trim() }),
    })
    if (r.ok) ElMessage.success(`连接正常（${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连不上')
  } catch (e: any) {
    ElMessage.error(e.message || '测试失败')
  } finally {
    endpointTesting.value = false
  }
}

async function saveEndpoint() {
  if (!detail.value || !endpointInput.value.trim()) return
  endpointSaving.value = true
  try {
    const r = await call<any>(`/api/plugins/${encodeURIComponent(detail.value.id)}/endpoint`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: endpointInput.value.trim() }),
    })
    if (r.ok) {
      ElMessage.success(r.message || '已保存')
      await openDetail({ id: detail.value.id })
    } else {
      ElMessage.error(r.message || '保存失败')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    endpointSaving.value = false
  }
}

const runtimeLabel = computed(() => {
  const m = detail.value?.manifest
  if (!m) return '-'
  const map: Record<string, string> = {
    data: '数据型（查表，素材在包里）',
    prompt: '提示词型（宿主代调模型）',
    hosted: '作者服务（远端 HTTP）',
    local: '本机服务（本机进程）',
    process: '独立进程',
  }
  return map[m.runtime] || m.runtime
})

const connectionType = computed(() => {
  const m = detail.value?.manifest
  if (!m) return '-'
  if (m.runtime === 'data') return '无 —— 数据型，完全离线'
  if (m.runtime === 'prompt') return '无 —— 由宿主调用模型'
  if (m.runtime === 'hosted') return '作者部署的远端服务'
  if (m.runtime === 'local') return '本机进程（宿主转发）'
  return m.runtime
})

const endpointRows = computed<{ label: string; value: string }[]>(() => {
  const d = detail.value
  const m = d?.manifest
  if (!m) return []
  const rows: { label: string; value: string }[] = []
  if (m.runtime === 'local') {
    // 实例上真实填过的地址优先，没填过才退回 manifest 里的示例地址
    rows.push({ label: '本地地址', value: d.endpoint || m.local?.endpointHint || '未配置' })
    if (m.local?.invoke) rows.push({ label: '调用路径', value: m.local.invoke })
    if (m.local?.health) rows.push({ label: '健康检查', value: m.local.health })
  }
  if (m.runtime === 'hosted' && (d.endpoint || m.hosted?.endpoint)) {
    rows.push({ label: '服务地址', value: d.endpoint || m.hosted.endpoint })
    if (m.hosted?.health) rows.push({ label: '健康检查', value: m.hosted.health })
  }
  if (!rows.length && d.endpoint) rows.push({ label: '端点', value: d.endpoint })
  return rows
})

const outbound = computed<string[]>(() => detail.value?.manifest?.permissions?.network?.outbound || [])
const examples = computed<string[]>(() => detail.value?.manifest?.intent?.examples || [])
const actions = computed<any[]>(() => detail.value?.manifest?.actions || [])
const catalogEntries = computed<any[]>(() => detail.value?.manifest?.catalog?.entries || [])
const configFields = computed<any[]>(() => detail.value?.manifest?.config || [])

/** 包内素材：catalog 里带 file 的条目，直接对应安装目录里的真实文件 */
const assetsRows = computed<any[]>(() =>
  (detail.value?.manifest?.catalog?.entries || [])
    .filter((e: any) => e && e.file)
    .map((e: any) => ({ name: e.value, file: e.file, durationMs: e.durationMs })),
)


const voiceText = computed(() => {
  const s = detail.value?.manifest?.voice?.strategy
  if (!s) return '不发语音'
  if (s === 'audio') return '包里自带音频'
  if (s === 'tts') return '宿主 TTS 合成'
  return s
})

const memoryWriteText = computed(() => {
  const mem = detail.value?.manifest?.memory
  if (!mem || !mem.policy || mem.policy === 'none') return '不写'
  return `${mem.policy} · ${mem.keyTemplate || ''}`
})

const enabledText = computed(() => {
  const e = detail.value?.enabledFor || {}
  const ids = Object.keys(e).filter((k) => e[k])
  return ids.length ? `账号 ${ids.join('、')}` : '未启用'
})

function paramsOf(a: any): any[] {
  if (!a?.params) return []
  return Object.entries(a.params as Record<string, any>).map(([name, v]) => ({ name, ...(v as any) }))
}

function fmtDefault(row: any): string {
  const v = row?.default
  if (v === null || v === undefined || v === '') return '—'
  return typeof v === 'boolean' ? (v ? '开' : '关') : String(v)
}

async function openDetail(row: any, focusConfig = false) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  versionsInfo.value = null
  try {
    detail.value = await call<any>(`/api/plugins/${encodeURIComponent(row.id)}`)
    endpointInput.value =
      detail.value?.endpoint || detail.value?.manifest?.local?.endpointHint || ''
    // 版本信息单独一个接口：详情页要能立刻判断「能不能回滚」
    versionsInfo.value = await call<any>(
      `/api/plugins/${encodeURIComponent(row.id)}/versions`,
    ).catch(() => null)
    if (focusConfig) {
      await nextTick()
      // 抽屉内容有渲染延迟，等一帧再滚
      window.setTimeout(() => {
        configSection.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
      }, 120)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '读取详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

// 插件接口统一走真实后端（不进 mock 层，避免开发态被假数据挡住）
async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    cache: 'no-store',
    ...init,
    headers: { 'X-QQ-Id': localStorage.getItem('activeQq') || '', ...(init.headers || {}) },
  })
  if (!res.ok) {
    let msg = `请求失败 ${res.status}`
    try {
      const body = await res.json()
      if (body?.message) msg = body.message
    } catch {
      // 非 JSON 响应（比如网关 502），保留状态码
    }
    throw new Error(msg)
  }
  return (await res.json()) as T
}

async function load() {
  loading.value = true
  try {
    const data = await call<{ pluginEnabled: boolean; bots: number[]; installed: InstalledRow[] }>('/api/plugins')
    pluginEnabled.value = data.pluginEnabled
    bots.value = data.bots || []
    installed.value = data.installed || []
    runs.value = await call<any[]>('/api/plugins/runs?limit=50').catch(() => [])
  } catch (e: any) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function onPickFile(options: any) {
  const file: File = options.file
  const form = new FormData()
  form.append('file', file)
  installing.value = true
  try {
    const res = await call<any>('/api/plugins/inspect', { method: 'POST', body: form })
    if (!res.ok) {
      ElMessage.error(res.issues?.[0]?.message || '体检未通过')
      return
    }
    preview.value = res.manifest
    pendingFile.value = file
    previewVisible.value = true
  } catch (e: any) {
    ElMessage.error(e.message || '检查失败')
  } finally {
    installing.value = false
  }
}

async function confirmInstall() {
  if (!pendingFile.value) return
  const form = new FormData()
  form.append('file', pendingFile.value)
  installing.value = true
  try {
    const res = await call<any>('/api/plugins/install', { method: 'POST', body: form })
    if (res.ok) {
      ElMessage.success('已安装，去打开开关吧')
      previewVisible.value = false
      pendingFile.value = null
      await load()
    } else {
      ElMessage.error(res.issues?.[0]?.message || '安装失败')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '安装失败')
  } finally {
    installing.value = false
  }
}

/**
 * 启用前的权限确认（§7.7）。
 *
 * <p>后端只回「清单 + 摘要」，用户在弹窗里看到的是具体域名、条数，
 * 点确认后才带 digest 回来写库；取消则保持关闭。
 */
async function confirmPermissions(row: any, res: any): Promise<boolean> {
  const perms: any[] = res.permissions || []
  const children: any[] = [
    h('p', { style: 'margin:0 0 10px;color:#606266;line-height:1.6' },
      perms.length
        ? `「${row.name}」要用到下面这些能力，确认后才会启用：`
        : `确认启用「${row.name}」？`),
  ]
  for (const p of perms) {
    children.push(h('div', {
      style: 'padding:8px 10px;margin-bottom:8px;border-left:3px solid #e6a23c;'
        + 'background:#fdf6ec;border-radius:4px',
    }, [
      h('div', { style: 'font-weight:600;margin-bottom:2px;color:#303133' }, p.label || p.key),
      h('div', { style: 'color:#606266;font-size:12px;line-height:1.6' }, p.detail || ''),
    ]))
  }
  children.push(h('p', { style: 'margin:10px 0 0;color:#909399;font-size:12px;line-height:1.6' },
    '宿主只按上面写的范围放行：插件拿不到 QQ 号、群号和聊天记录。'
    + '升级后如果权限有变化，会再次要求你确认。'))
  try {
    await ElMessageBox.confirm(h('div', null, children), '启用前确认', {
      confirmButtonText: '我了解，启用',
      cancelButtonText: '取消',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function toggle(row: any, botId: number, value: boolean) {
  const base = `/api/plugins/${encodeURIComponent(row.id)}`
  try {
    if (value) {
      let res: any = await call<any>(`${base}/enable?botId=${botId}`, { method: 'POST' })
      if (res?.privacyBlocked) {
        ElMessage.warning(res.message || '隐私模式下不允许启用这个插件')
        await load()
        return
      }
      if (res?.needConfirm) {
        const ok = await confirmPermissions(row, res)
        if (!ok) {
          await load()
          return
        }
        res = await call<any>(
          `${base}/enable?botId=${botId}&ack=${encodeURIComponent(res.digest)}`,
          { method: 'POST' },
        )
        if (!res?.ok) {
          ElMessage.error(res?.message || '启用失败')
          await load()
          return
        }
      }
      if (!row.enabledFor.includes(botId)) row.enabledFor.push(botId)
      ElMessage.success('已启用')
    } else {
      await call(`${base}/disable?botId=${botId}`, { method: 'POST' })
      row.enabledFor = row.enabledFor.filter((id: number) => id !== botId)
      ElMessage.success('已停用')
    }
    if (detailVisible.value && detail.value?.id === row.id) await openDetail(row)
  } catch (e: any) {
    ElMessage.error(e.message || '操作失败')
    await load()
  }
}

async function uninstall(row: any) {
  try {
    await ElMessageBox.confirm(
      `卸载「${row.name}」会从所有账号停用它，并归档它写过的记忆（不删聊天记录）。`,
      '确认卸载',
      { type: 'warning', confirmButtonText: '卸载', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await call(`/api/plugins/${encodeURIComponent(row.id)}`, { method: 'DELETE' })
    ElMessage.success('已卸载')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '卸载失败')
  }
}

onMounted(() => {
  load()
  loadStore()
})
</script>

<style scoped>
.plugins-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.plugin-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}
.card {
  padding: 18px 20px;
}
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.hero-main {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.hero-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
}
.hero-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.hero-desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  max-width: 620px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.card-head h3 {
  margin: 0;
  font-size: 15px;
}
.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.plugin-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.plugin-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}
.intent {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.bot-switches {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}
.bot-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.bot-id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.preview-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.plugin-link {
  font-size: 14px;
  font-weight: 600;
  padding: 0;
}
.detail-body {
  padding-bottom: 24px;
}
.detail-section {
  margin-bottom: 22px;
}
.detail-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
/* 权限清单：左侧色条表示风险等级，用户扫一眼就知道哪条最该在意 */
.perm-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.perm-item {
  padding: 8px 10px;
  border-left: 3px solid var(--el-color-info);
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}
.perm-item.lv-medium {
  border-left-color: var(--el-color-warning);
}
.perm-item.lv-high {
  border-left-color: var(--el-color-danger);
}
.perm-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.perm-detail {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.ack-table {
  margin-top: 10px;
}
.version-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.version-actions .muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sub-head {
  margin: 16px 0 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
}
.endpoint-editor {
  margin-top: 10px;
}
.endpoint-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.detail-note {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.addr {
  font-size: 12px;
  word-break: break-all;
}
.example-chips {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}
.action-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}
.action-item {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.action-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.action-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.action-params {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.publish-card {
  padding: 22px 24px 24px;
}
.publish-head {
  align-items: flex-start;
}
.publish-title {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.publish-icon {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 20px;
  background: linear-gradient(135deg, #6c8cf5, #4f6ef0);
  box-shadow: 0 6px 16px rgba(79, 110, 240, .22);
}
.publish-title h3 {
  margin: 0;
}
.publish-title p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.publish-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(240px, .8fr);
  gap: 22px;
  margin-top: 20px;
}
.publish-drop {
  border: 1.5px dashed #c9d4ea;
  border-radius: 14px;
  background: #f8faff;
  padding: 26px 20px;
  text-align: center;
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, transform .18s ease;
}
.publish-drop:hover {
  border-color: #6c8cf5;
  background: #f2f6ff;
}
.publish-drop.has-file {
  border-style: solid;
  border-color: #9fb3ee;
  background: #f5f8ff;
}
.drop-icon {
  width: 38px;
  height: 38px;
  margin: 0 auto 10px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: #e8efff;
  color: #4f6ef0;
  font-size: 20px;
}
.publish-drop strong {
  display: block;
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.publish-drop p {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.publish-file {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: #fff;
}
.file-badge {
  flex: 0 0 auto;
  padding: 3px 6px;
  border-radius: 6px;
  background: #eef3ff;
  color: #4f6ef0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .04em;
}
.file-info {
  min-width: 0;
  flex: 1;
}
.file-name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
}
.file-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.publish-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}
.publish-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.publish-result {
  margin-top: 14px;
}
.publish-side {
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 22px;
}
.publish-side h4 {
  margin: 2px 0 10px;
  font-size: 13px;
}
.publish-checklist {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 7px;
}
.publish-checklist li {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.publish-checklist li span {
  color: #67c23a;
  font-weight: 700;
}
.publish-checklist code {
  font-size: 11px;
}
.publish-flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 18px;
  padding: 10px;
  border-radius: 10px;
  background: #fafbfe;
  border: 1px solid var(--el-border-color-lighter);
  font-size: 11px;
  color: var(--el-text-color-regular);
}
.publish-flow i {
  color: #b6c2d8;
  font-style: normal;
}
.publish-doc {
  display: inline-block;
  margin-top: 12px;
  color: #4f6ef0;
  font-size: 12px;
  text-decoration: none;
}
.publish-doc:hover {
  text-decoration: underline;
}
@media (max-width: 760px) {
  .publish-grid {
    grid-template-columns: 1fr;
  }
  .publish-side {
    border-left: none;
    border-top: 1px solid var(--el-border-color-lighter);
    padding-left: 0;
    padding-top: 16px;
  }
}

.changelog {
  white-space: pre-wrap;
  max-height: 160px;
  overflow: auto;
  margin: 0;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
}
</style>
