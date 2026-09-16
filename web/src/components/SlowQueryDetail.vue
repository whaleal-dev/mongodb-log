<script setup>
import { computed } from 'vue'
import { formatDate } from '../utils/format.js'
import { explainQuery, logMetadata } from '../utils/queryExplanation.js'

const props = defineProps({ query: { type: Object, default: null } })
const attributes = computed(() => JSON.stringify(props.query?.attributes || {}, null, 2))
const metadata = computed(() => logMetadata(props.query || {}))
const explanation = computed(() => explainQuery(props.query || {}))
const logFields = [
  ['id', '日志消息类型编号，用于识别消息类别，并非单条查询的唯一 ID。'],
  ['severity', '日志级别：I 信息、W 警告、E 错误、F 致命、D 调试。慢查询也可能记录为 I。'],
  ['component', '产生日志的组件，例如 COMMAND 为命令、WRITE 为写入。'],
  ['context', '记录时的连接或执行上下文，用于关联同一上下文的日志。'],
  ['message', 'MongoDB 记录的事件描述。'],
]
</script>

<template>
  <section v-if="query" class="selected-detail" data-testid="slow-query-detail">
      <h3>日志解读</h3>
      <p class="query-overview">{{ explanation.overview }}</p>
      <p class="detail-note">按本条日志已记录字段生成。缺失不代表零；以下线索用于排查，不能代替完整执行计划和运行环境分析。</p>
      <div class="interpreted-metrics">
        <div v-for="row in explanation.rows" :key="row.field" class="interpreted-row">
          <div><span>{{ row.label }}</span><strong>{{ row.value }}</strong></div>
          <p>{{ row.meaning }}</p><code>{{ row.field }}</code>
        </div>
      </div>
      <section v-if="explanation.observations.length" class="query-observations">
        <h3>排查线索</h3>
        <div v-for="item in explanation.observations" :key="item.title"><h4>{{ item.title }}</h4><p>{{ item.text }}</p></div>
      </section>
      <template v-if="explanation.command">
        <h3>本次命令</h3><pre>{{ JSON.stringify(explanation.command, null, 2) }}</pre>
      </template>
      <template v-if="explanation.originatingCommand">
        <h3>游标来源命令</h3><p class="detail-note">创建当前游标的原始命令；本条 getMore 耗时仅对应后续批次。</p>
        <pre>{{ JSON.stringify(explanation.originatingCommand, null, 2) }}</pre>
      </template>
      <h3>日志记录</h3>
      <table class="data-table detail-fields"><tbody>
        <tr><th>queryId</th><td>{{ query.queryId }}<small>本工具的日志定位标识。文件序号 {{ query.fileIndex }}，行号 {{ query.lineNumber }}。</small></td></tr>
        <tr><th>timestamp</th><td>{{ formatDate(query.timestampEpochMillis) }}<small>按本机时区显示。UTC 毫秒值：{{ query.timestampEpochMillis }}。</small></td></tr>
        <tr v-for="[field, meaning] in logFields" :key="field"><th>{{ field }}</th><td>{{ metadata[field] ?? '日志未提供' }}<small>{{ meaning }}</small></td></tr>
        <tr><th>remote</th><td>{{ query.remote ?? '日志未提供' }}<small>日志中记录的客户端地址。</small></td></tr>
      </tbody></table>
      <h3>attributes</h3>
      <pre>{{ attributes }}</pre>
      <h3>原始日志</h3>
      <pre class="raw">{{ query.rawLine }}</pre>
  </section>
</template>

<style scoped>
.selected-detail { min-width: 0; }
.query-overview { color: #305e4c; padding: 12px 14px; background: #eff6f2; border-radius: 8px; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.detail-note { color: #8a97a4; font-size: 12px; line-height: 1.8; }
.interpreted-metrics { border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.interpreted-row { padding: 13px 15px; border-bottom: 1px solid var(--line); }
.interpreted-row:last-child { border-bottom: 0; }
.interpreted-row > div { display: flex; justify-content: space-between; gap: 16px; font-size: 12px; }
.interpreted-row strong { color: #356c55; font-weight: 550; text-align: right; overflow-wrap: anywhere; max-width: 65%; }
.interpreted-row p, .query-observations p { margin: 7px 0 0; color: #7c8c99; font-size: 12px; line-height: 1.8; }
.interpreted-row code { color: #a0abb6; font: 10px/1.7 ui-monospace, monospace; overflow-wrap: anywhere; }
.query-observations > div { padding: 12px 14px; margin-bottom: 8px; background: #f8fafb; border-left: 2px solid #9ebead; }
.query-observations h4 { margin: 0; font-size: 12px; font-weight: 550; }
.detail-fields th { width: 130px; }
.detail-fields td { white-space: pre-wrap; }
.detail-fields small { display: block; color: #8a97a4; font-size: 11px; line-height: 1.7; margin-top: 5px; }
h3 { margin: 22px 0 8px; font-size: 14px; }
pre { margin: 0; padding: 16px; border: 1px solid #e7edf2; border-radius: 8px; background: #f6f8fa; color: #526577; font: 12px/1.7 "SFMono-Regular", Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.raw { max-height: 300px; overflow: auto; }
</style>
