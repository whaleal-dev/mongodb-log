<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const visible = ref(false)

function open() {
  visible.value = true
}

function close() {
  visible.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') close()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <button class="usage-guide-button" type="button" @click="open">使用说明</button>
  <div v-if="visible" class="guide-overlay" @click.self="close">
    <section role="dialog" aria-modal="true" aria-labelledby="usage-guide-title" class="guide-dialog">
      <header class="guide-header">
        <div>
          <h2 id="usage-guide-title">使用方法与限制</h2>
          <p>MongoDB 日志与 Metric 本地离线分析</p>
        </div>
        <button class="guide-close" type="button" aria-label="关闭使用说明" @click="close">×</button>
      </header>

      <div class="guide-content">
        <section class="guide-intro">
          <strong>开始前</strong>
          <p>使用 Java 17 或更高版本启动程序，然后访问 <code>http://127.0.0.1:18080</code>。全部解析均在本机完成，不需要连接 MongoDB，也不会把数据发送到外部 AI 服务。</p>
        </section>

        <div class="guide-columns">
          <section class="guide-section">
            <h3>MongoDB Log 使用方法</h3>
            <ol>
              <li>在顶部选择「MongoDB Log」，填写可选的任务名称。</li>
              <li>选择普通文本、结构化 JSON、旧版日志或 <code>.gz</code> 文件，一次最多选择 20 个文件。</li>
              <li>开始分析并等待任务完成。任务按顺序在本机处理。</li>
              <li>查看慢查询分析与运行诊断；需要进一步分析时，可导出脱敏 Markdown 报告。</li>
              <li>不再需要结果时，从任务列表删除应用保存的数据。</li>
            </ol>
          </section>

          <section class="guide-section">
            <h3>MongoDB Metric 使用方法</h3>
            <ol>
              <li>在顶部选择「MongoDB Metric」。</li>
              <li>选择 1～20 个 <code>diagnostic.data/metrics.*</code> 文件；程序按内容识别，不依赖扩展名。</li>
              <li>等待索引建立完成，然后选择一个或多个指标组，也可一键选择核心指标组。</li>
              <li>按顺序查询指标组，切换原始值／相邻差值、合并图／拆分图或隐藏全零指标。</li>
              <li>任务删除后，应用保存的 Metric 副本和索引会一并清理，用户原文件不受影响。</li>
            </ol>
          </section>
        </div>

        <section class="guide-section limits-section">
          <h3>主要限制</h3>
          <div class="limit-grid">
            <div><strong>运行范围</strong><p>只监听本机地址，不提供账号、多人协作、远程访问或实时 MongoDB 监控能力。</p></div>
            <div><strong>上传与队列</strong><p>单次上传请求最大 12 GB；日志分析使用单工作线程，Metric 建索引和查询也按顺序执行。</p></div>
            <div><strong>日志保留</strong><p>完整日志不会全部保存；仅保留最慢 Top 5000 明细和 Top 50 查询模式各自最慢的一条样本，其余只参与聚合。</p></div>
            <div><strong>字段依赖</strong><p>CPU、连接、执行计划等结果取决于原日志实际字段。缺失字段显示无数据，不会按零值补齐。</p></div>
            <div><strong>Metric 边界</strong><p>每个指标组最多 200 个指标，每个指标图最多 1200 个点；不提供单指标入口、原始值分页或完整指标导出。</p></div>
            <div><strong>分析结论</strong><p>页面提供统计、字段解读和排查线索，不会自动判断根因；结论仍需结合业务、配置和服务器现场验证。</p></div>
            <div><strong>历史结果</strong><p>升级程序不会重算已有任务。要应用新版解析规则，需要重新上传原始文件。</p></div>
            <div><strong>本地数据</strong><p>结果保存在启动目录的 <code>data</code> 中。Metric 副本会持续占用磁盘，直到删除对应任务。</p></div>
          </div>
        </section>
      </div>

      <footer class="guide-footer">
        <span>关闭启动窗口或按 <code>Ctrl+C</code> 可停止程序。</span>
        <button type="button" @click="close">知道了</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.usage-guide-button { padding: 5px 10px; border: 1px solid #d7e0e5; border-radius: 5px; color: #526b66; background: #fff; font-size: 12px; cursor: pointer; }
.usage-guide-button:hover { border-color: #8db4a4; color: #215c44; background: #f5faf7; }
.guide-overlay { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; padding: 24px; background: rgba(28, 39, 48, .46); }
.guide-dialog { display: flex; flex-direction: column; width: min(960px, 100%); max-height: min(820px, calc(100vh - 48px)); overflow: hidden; border-radius: 10px; background: #fff; box-shadow: 0 24px 70px rgba(22, 36, 44, .24); }
.guide-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 20px 24px 17px; border-bottom: 1px solid #e2e8ec; }
.guide-header h2 { margin: 0; color: #253b37; font-size: 20px; font-weight: 650; }
.guide-header p { margin: 5px 0 0; color: #7b8b96; font-size: 12px; }
.guide-close { width: 30px; height: 30px; padding: 0; border: 0; border-radius: 5px; color: #6f7f89; background: transparent; font-size: 23px; line-height: 28px; cursor: pointer; }
.guide-close:hover { color: #334b46; background: #f0f4f3; }
.guide-content { overflow: auto; padding: 20px 24px 22px; }
.guide-intro { padding: 13px 15px; border-left: 3px solid #5c9b7f; background: #f4f9f6; }
.guide-intro strong { color: #315f4e; font-size: 13px; }
.guide-intro p { margin: 5px 0 0; color: #536a63; font-size: 12px; line-height: 1.75; }
.guide-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 18px; }
.guide-section { min-width: 0; }
.guide-section h3 { margin: 0 0 10px; color: #334b46; font-size: 14px; }
.guide-section ol { margin: 0; padding-left: 20px; color: #546571; font-size: 12px; line-height: 1.75; }
.guide-section li + li { margin-top: 5px; }
.limits-section { margin-top: 20px; }
.limit-grid { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid #e3e9ed; border-left: 1px solid #e3e9ed; }
.limit-grid > div { padding: 12px 14px; border-right: 1px solid #e3e9ed; border-bottom: 1px solid #e3e9ed; }
.limit-grid strong { color: #465d58; font-size: 12px; }
.limit-grid p { margin: 4px 0 0; color: #657580; font-size: 11px; line-height: 1.7; }
.guide-dialog code { padding: 1px 4px; border-radius: 3px; color: #355c4c; background: #edf4f0; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .95em; }
.guide-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 24px; border-top: 1px solid #e2e8ec; color: #7b8b96; background: #fafbfc; font-size: 11px; }
.guide-footer button { padding: 7px 18px; border: 1px solid #347961; border-radius: 5px; color: #fff; background: #347961; font-size: 12px; cursor: pointer; }
@media (max-width: 720px) {
  .guide-overlay { padding: 12px; }
  .guide-dialog { max-height: calc(100vh - 24px); }
  .guide-header, .guide-content, .guide-footer { padding-left: 16px; padding-right: 16px; }
  .guide-columns, .limit-grid { grid-template-columns: 1fr; }
  .guide-footer span { display: none; }
  .guide-footer { justify-content: flex-end; }
}
</style>
