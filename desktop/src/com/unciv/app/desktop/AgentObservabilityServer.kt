package com.unciv.app.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.logic.automation.agent.AgentObservability
import com.unciv.utils.Log
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object AgentObservabilityServer {
    private var server: HttpServer? = null

    fun startFromEnvironment() {
        val enabled = (System.getenv("UNCIV_AGENT_OBS_ENABLED") ?: "true").lowercase() !in setOf("0", "false", "no")
        if (!enabled) return

        val port = (System.getenv("UNCIV_AGENT_OBS_PORT") ?: "7071").toIntOrNull() ?: 7071
        start(port)
    }

    private fun start(port: Int) {
        if (server != null) return

        val httpServer = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        httpServer.executor = Executors.newSingleThreadExecutor()

        httpServer.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/" -> respond(exchange, 200, htmlPage, "text/html; charset=utf-8")
                "/api/snapshot" -> respond(exchange, 200, AgentObservability.snapshotJson(), "application/json; charset=utf-8")
                else -> respond(exchange, 404, "Not found", "text/plain; charset=utf-8")
            }
        }

        httpServer.start()
        server = httpServer
        Log.error("AI (agent) observability dashboard started on port %s", port)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private val htmlPage = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Unciv AI Agent Observability</title>
  <style>
    :root {
      --bg: #0b1220;
      --bg-2: #12263a;
      --panel: #111d2f;
      --card: #15253a;
      --line: #2f4760;
      --text: #ecf6ff;
      --muted: #9cb2c8;
      --accent: #39d98a;
      --accent-2: #4dabf7;
      --warn: #ffb347;
      --error: #ff6b6b;
      --chip: #20344f;
      --chip-on: #2b5f8f;
    }

    * { box-sizing: border-box; }

    body {
      margin: 0;
      color: var(--text);
      font-family: "JetBrains Mono", "Fira Code", monospace;
      background:
        radial-gradient(1200px 700px at -5% -10%, #1a3454 0%, transparent 60%),
        radial-gradient(900px 700px at 110% -20%, #1b2d40 0%, transparent 55%),
        linear-gradient(180deg, var(--bg-2) 0%, var(--bg) 55%);
      min-height: 100vh;
    }

    .wrap {
      width: min(1320px, 96vw);
      margin: 0 auto;
      padding: 18px 0 28px;
    }

    .hero {
      background: linear-gradient(180deg, rgba(24, 43, 67, 0.92), rgba(14, 28, 45, 0.92));
      border: 1px solid var(--line);
      border-radius: 14px;
      padding: 14px 16px;
      box-shadow: 0 18px 38px rgba(0, 0, 0, 0.24);
    }

    .title-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 14px;
      flex-wrap: wrap;
    }

    .title {
      margin: 0;
      font-size: 25px;
      line-height: 1.2;
      letter-spacing: 0.2px;
    }

    .subtitle {
      margin-top: 6px;
      color: var(--muted);
      font-size: 13px;
    }

    .controls {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }

    .btn {
      border: 1px solid var(--line);
      color: var(--text);
      background: linear-gradient(180deg, #1d3553, #15263a);
      border-radius: 9px;
      padding: 7px 11px;
      cursor: pointer;
      font-family: inherit;
      font-size: 12px;
    }

    .btn:hover { border-color: #47688b; }

    .row {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      align-items: center;
      margin-top: 12px;
    }

    .status {
      color: var(--muted);
      font-size: 12px;
      min-height: 18px;
    }

    label {
      color: var(--muted);
      font-size: 12px;
      display: inline-flex;
      gap: 6px;
      align-items: center;
    }

    input[type="search"], select {
      border-radius: 9px;
      border: 1px solid var(--line);
      background: #0f1d2f;
      color: var(--text);
      padding: 7px 9px;
      font-family: inherit;
      font-size: 12px;
    }

    .metrics {
      margin-top: 14px;
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
    }

    .metric {
      background: linear-gradient(180deg, #182d47, #122338);
      border: 1px solid var(--line);
      border-radius: 12px;
      padding: 11px;
    }

    .metric .k {
      color: var(--muted);
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 0.8px;
    }

    .metric .v {
      margin-top: 6px;
      font-size: 20px;
      font-weight: 700;
      color: var(--accent);
    }

    .civ-panel {
      margin-top: 12px;
      border: 1px solid var(--line);
      border-radius: 12px;
      background: rgba(17, 29, 47, 0.86);
      padding: 10px;
    }

    .chips {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }

    .chip {
      border: 1px solid #355170;
      background: var(--chip);
      color: #cae7ff;
      border-radius: 999px;
      padding: 5px 10px;
      font-size: 11px;
    }

    .chip.active {
      background: var(--chip-on);
      border-color: #63a4e0;
    }

    .layout {
      margin-top: 12px;
      display: grid;
      grid-template-columns: 280px 1fr;
      gap: 10px;
    }

    .side {
      background: rgba(15, 27, 43, 0.88);
      border: 1px solid var(--line);
      border-radius: 12px;
      padding: 10px;
      max-height: 70vh;
      overflow: auto;
    }

    .side h3 {
      margin: 3px 0 10px;
      font-size: 13px;
      color: #d8ecff;
    }

    .dist-row {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 8px;
      align-items: center;
      padding: 6px 7px;
      border-radius: 8px;
      font-size: 12px;
      border: 1px solid transparent;
    }

    .dist-row:hover { border-color: #34506f; background: #15283f; }

    .dist-count {
      color: var(--accent-2);
      font-weight: 700;
    }

    .main {
      min-height: 320px;
      max-height: 70vh;
      overflow: auto;
      padding-right: 3px;
    }

    .events {
      display: grid;
      gap: 10px;
    }

    .event {
      background: linear-gradient(180deg, rgba(19, 34, 54, 0.95), rgba(12, 22, 36, 0.95));
      border: 1px solid var(--line);
      border-left: 4px solid var(--accent);
      border-radius: 11px;
      padding: 10px;
      animation: fadeIn 140ms ease-out;
    }

    .event.warn { border-left-color: var(--warn); }
    .event.error { border-left-color: var(--error); }

    .event-head {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
    }

    .event-type {
      color: #95f0bc;
      font-size: 12px;
      letter-spacing: 0.3px;
      font-weight: 700;
      text-transform: uppercase;
    }

    .event-meta {
      color: var(--muted);
      font-size: 11px;
    }

    .event-msg {
      margin-top: 7px;
      font-size: 13px;
      line-height: 1.35;
      white-space: pre-wrap;
      color: #f1f8ff;
    }

    details { margin-top: 8px; }

    summary {
      color: #9fd0ff;
      font-size: 12px;
      cursor: pointer;
    }

    pre {
      margin: 7px 0 0;
      border: 1px solid #2d4764;
      border-radius: 8px;
      background: #08111f;
      color: #d8ecff;
      padding: 9px;
      overflow: auto;
      font-size: 11px;
      line-height: 1.3;
    }

    .empty {
      text-align: center;
      color: var(--muted);
      border: 1px dashed #355170;
      border-radius: 11px;
      padding: 24px 12px;
      margin-top: 8px;
      background: rgba(15, 25, 38, 0.7);
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(5px); }
      to { opacity: 1; transform: translateY(0); }
    }

    @media (max-width: 1080px) {
      .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .layout { grid-template-columns: 1fr; }
      .side, .main { max-height: none; }
    }

    @media (max-width: 640px) {
      .wrap { width: min(96vw, 700px); }
      .metrics { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <div class="wrap">
    <section class="hero">
      <div class="title-row">
        <div>
          <h1 class="title">Unciv AI Agent Observability</h1>
          <div class="subtitle">Dashboard view for multi-agent turns, LLM I/O, parse outcomes, and execution behavior.</div>
        </div>
        <div class="controls">
          <button class="btn" id="refresh">Refresh</button>
          <label><input id="autorefresh" type="checkbox" checked> Auto 2s</label>
          <label><input id="showDetails" type="checkbox" checked> Expand details</label>
        </div>
      </div>

      <div class="metrics">
        <div class="metric"><div class="k">Buffered Events</div><div class="v" id="mTotal">0</div></div>
        <div class="metric"><div class="k">Visible Events</div><div class="v" id="mVisible">0</div></div>
        <div class="metric"><div class="k">Active Civs</div><div class="v" id="mCivs">0</div></div>
        <div class="metric"><div class="k">Last Update</div><div class="v" id="mUpdated">-</div></div>
      </div>

      <div class="row">
        <label>Civilization
          <select id="civFilter"></select>
        </label>
        <label>Event Type
          <select id="typeFilter"></select>
        </label>
        <label>Search
          <input id="textFilter" type="search" placeholder="Filter by message/details...">
        </label>
        <div class="status" id="status">Connecting...</div>
      </div>

      <div class="civ-panel">
        <div class="chips" id="civChips"></div>
      </div>
    </section>

    <section class="layout">
      <aside class="side">
        <h3>Event Distribution</h3>
        <div id="typeDistribution"></div>
      </aside>
      <main class="main">
        <div class="events" id="events"></div>
      </main>
    </section>
  </div>

  <script>
    const eventsEl = document.getElementById('events');
    const statusEl = document.getElementById('status');
    const refreshBtn = document.getElementById('refresh');
    const autoEl = document.getElementById('autorefresh');
    const showDetailsEl = document.getElementById('showDetails');
    const civFilterEl = document.getElementById('civFilter');
    const typeFilterEl = document.getElementById('typeFilter');
    const textFilterEl = document.getElementById('textFilter');
    const civChipsEl = document.getElementById('civChips');
    const typeDistributionEl = document.getElementById('typeDistribution');

    const mTotal = document.getElementById('mTotal');
    const mVisible = document.getElementById('mVisible');
    const mCivs = document.getElementById('mCivs');
    const mUpdated = document.getElementById('mUpdated');

    const STATE = {
      snapshot: null,
      civFilter: 'all',
      typeFilter: 'all',
      textFilter: ''
    };

    function esc(str) {
      return String(str)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
    }

    function mapCount(items, keyGetter) {
      const out = new Map();
      for (const item of items) {
        const key = keyGetter(item);
        out.set(key, (out.get(key) || 0) + 1);
      }
      return out;
    }

    function eventClass(type) {
      const t = String(type || '').toLowerCase();
      if (t.includes('error')) return 'event error';
      if (t.includes('fallback') || t.includes('missing')) return 'event warn';
      return 'event';
    }

    function normalizeDetails(details) {
      try {
        return JSON.stringify(details || {}, null, 2);
      } catch (_) {
        return '{}';
      }
    }

    function eventTextBlob(e) {
      return [
        e.type || '',
        e.message || '',
        e.civName || '',
        normalizeDetails(e.details)
      ].join(' ').toLowerCase();
    }

    function sortedEntriesByCount(mapObj) {
      return [...mapObj.entries()].sort((a, b) => b[1] - a[1]);
    }

    function buildSelectOptions(selectEl, values, currentValue, allLabel) {
      const options = [
        `<option value="all">${'$'}{esc(allLabel)}</option>`,
        ...values.map(v => `<option value="${'$'}{esc(v)}">${'$'}{esc(v)}</option>`)
      ];
      selectEl.innerHTML = options.join('');
      selectEl.value = values.includes(currentValue) ? currentValue : 'all';
    }

    function renderCivChips(civCounts, activeCiv) {
      const rows = sortedEntriesByCount(civCounts).map(([civ, count]) => {
        const active = civ === activeCiv ? 'chip active' : 'chip';
        return `<button class="${'$'}{esc(active)}" data-civ="${'$'}{esc(civ)}">${'$'}{esc(civ)} (${'$'}{count})</button>`;
      });
      civChipsEl.innerHTML = [`<button class="${'$'}{activeCiv === 'all' ? 'chip active' : 'chip'}" data-civ="all">All (${'$'}{[...civCounts.values()].reduce((a, b) => a + b, 0)})</button>`, ...rows].join('');

      [...civChipsEl.querySelectorAll('button[data-civ]')].forEach(btn => {
        btn.addEventListener('click', () => {
          STATE.civFilter = btn.dataset.civ || 'all';
          civFilterEl.value = STATE.civFilter;
          render();
        });
      });
    }

    function renderTypeDistribution(typeCounts) {
      const rows = sortedEntriesByCount(typeCounts).map(([type, count]) => {
        return `<div class="dist-row"><span>${'$'}{esc(type)}</span><span class="dist-count">${'$'}{count}</span></div>`;
      });
      typeDistributionEl.innerHTML = rows.join('');
    }

    function applyFilters(events) {
      return events.filter(e => {
        if (STATE.civFilter !== 'all' && (e.civName || 'unknown') !== STATE.civFilter) return false;
        if (STATE.typeFilter !== 'all' && (e.type || 'unknown') !== STATE.typeFilter) return false;
        if (STATE.textFilter) {
          const blob = eventTextBlob(e);
          if (!blob.includes(STATE.textFilter)) return false;
        }
        return true;
      });
    }

    function renderEvents(filtered) {
      if (!filtered.length) {
        eventsEl.innerHTML = '<div class="empty">No events match current filters.</div>';
        return;
      }

      const expand = showDetailsEl.checked;
      eventsEl.innerHTML = filtered.slice().reverse().map(e => {
        const detailJson = normalizeDetails(e.details);
        const ts = new Date(e.epochMs || Date.now()).toLocaleTimeString();
        const civ = e.civName || 'unknown';
        const turn = e.turn !== undefined && e.turn !== null ? e.turn : '-';
        const detailsOpenAttr = expand ? ' open' : '';

        return `
          <article class="${'$'}{eventClass(e.type)}">
            <div class="event-head">
              <span class="event-type">${'$'}{esc(e.type || 'unknown')}</span>
              <span class="event-meta">#${'$'}{e.id} | ${'$'}{ts} | civ=${'$'}{esc(civ)} | turn=${'$'}{turn}</span>
            </div>
            <div class="event-msg">${'$'}{esc(e.message || '')}</div>
            <details${'$'}{detailsOpenAttr}>
              <summary>Details</summary>
              <pre>${'$'}{esc(detailJson)}</pre>
            </details>
          </article>
        `;
      }).join('');
    }

    function render() {
      const snapshot = STATE.snapshot || { recentEvents: [], totalBufferedEvents: 0, generatedAtEpochMs: Date.now() };
      const events = snapshot.recentEvents || [];
      const civCounts = mapCount(events, e => e.civName || 'unknown');
      const typeCounts = mapCount(events, e => e.type || 'unknown');

      const civValues = [...civCounts.keys()].sort((a, b) => a.localeCompare(b));
      const typeValues = [...typeCounts.keys()].sort((a, b) => a.localeCompare(b));
      buildSelectOptions(civFilterEl, civValues, STATE.civFilter, 'All civs');
      buildSelectOptions(typeFilterEl, typeValues, STATE.typeFilter, 'All event types');

      const filtered = applyFilters(events);
      renderEvents(filtered);
      renderCivChips(civCounts, STATE.civFilter);
      renderTypeDistribution(typeCounts);

      mTotal.textContent = String(snapshot.totalBufferedEvents || events.length);
      mVisible.textContent = String(filtered.length);
      mCivs.textContent = String(civValues.length);
      mUpdated.textContent = new Date(snapshot.generatedAtEpochMs || Date.now()).toLocaleTimeString();
      statusEl.textContent = `OK (${'$'}{filtered.length} shown / ${'$'}{events.length} fetched)`;
    }

    async function load() {
      try {
        const r = await fetch('/api/snapshot', { cache: 'no-store' });
        if (!r.ok) throw new Error(`HTTP ${'$'}{r.status}`);
        STATE.snapshot = await r.json();
        render();
      } catch (e) {
        statusEl.textContent = `Fetch failed: ${'$'}{e.message}`;
      }
    }

    refreshBtn.addEventListener('click', load);

    civFilterEl.addEventListener('change', () => {
      STATE.civFilter = civFilterEl.value;
      render();
    });

    typeFilterEl.addEventListener('change', () => {
      STATE.typeFilter = typeFilterEl.value;
      render();
    });

    textFilterEl.addEventListener('input', () => {
      STATE.textFilter = textFilterEl.value.trim().toLowerCase();
      render();
    });

    showDetailsEl.addEventListener('change', render);

    setInterval(() => {
      if (autoEl.checked) load();
    }, 2000);

    load();
  </script>
</body>
</html>
""".trimIndent()
}
