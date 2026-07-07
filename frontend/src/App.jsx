import { useEffect, useState, useCallback, useRef } from 'react';
import { Client } from '@stomp/stompjs';

const SOURCES = [
  { key: null, label: 'All' },
  { key: 'INSIDER_FORM4', label: 'Insider' },
  { key: 'CONGRESS', label: 'Congress' },
  { key: 'THIRTEEN_F', label: '13F' },
];

const SOURCE_LABEL = {
  INSIDER_FORM4: 'Insider',
  CONGRESS: 'Congress',
  THIRTEEN_F: '13F',
  NEWS: 'News',
  MANUAL: 'Manual',
};

async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(`${res.status} ${path}`);
  return res.json();
}

function fmtDate(s) {
  if (!s) return '—';
  return String(s).slice(0, 10);
}

function fmtTime(s) {
  if (!s) return '';
  try { return new Date(s).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }
  catch { return ''; }
}

function SidePill({ side }) {
  return <span className={`pill ${side?.toLowerCase()}`}>{side}</span>;
}

function SourceBadge({ source }) {
  return <span className={`badge src-${source}`}>{SOURCE_LABEL[source] || source}</span>;
}

function Confidence({ value }) {
  if (value == null) return <span className="conf-na">—</span>;
  const pct = Math.round(Number(value) * 100);
  return (
    <div className="conf" title={`confidence ${pct}%`}>
      <div className="conf-track"><div className="conf-fill" style={{ width: `${pct}%` }} /></div>
      <span className="conf-num">{pct}</span>
    </div>
  );
}

function SignalRow({ s }) {
  return (
    <tr>
      <td className="ticker">{s.ticker}</td>
      <td><SidePill side={s.side} /></td>
      <td><SourceBadge source={s.source} /></td>
      <td className="actor">{s.actorName || '—'}</td>
      <td className="date">{fmtDate(s.transactedAt)}</td>
      <td className="date">{fmtDate(s.disclosedAt)}</td>
      <td><Confidence value={s.confidence} /></td>
    </tr>
  );
}

function Fund({ fund }) {
  const [open, setOpen] = useState(false);
  const [holdings, setHoldings] = useState(null);

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (next && holdings == null && fund.cik) {
      try { setHoldings(await api(`/api/funds/${fund.cik}/holdings`)); }
      catch { setHoldings([]); }
    }
  };

  return (
    <div className="fund">
      <button className="fund-head" onClick={toggle} aria-expanded={open}>
        <span className="fund-name">{fund.name}</span>
        <span className="fund-meta">{fund.holdings} · {fund.latestPeriod || '—'}</span>
      </button>
      {open && (
        <div className="fund-body">
          {holdings == null ? <p className="muted small">Loading…</p>
            : holdings.length === 0 ? <p className="muted small">No holdings.</p>
            : (
              <ul className="holdings">
                {holdings.slice(0, 12).map((h, i) => (
                  <li key={i}>
                    <span className="h-ticker">{h.ticker || h.issuerName?.slice(0, 16) || '—'}</span>
                    <span className="h-val">${Number(h.value || 0).toLocaleString(undefined, { maximumFractionDigits: 0 })}</span>
                  </li>
                ))}
              </ul>
            )}
        </div>
      )}
    </div>
  );
}

export default function App() {
  const [source, setSource] = useState(null);
  const [signals, setSignals] = useState([]);
  const [funds, setFunds] = useState([]);
  const [news, setNews] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const [briefings, setBriefings] = useState([]);
  const [briefingStatus, setBriefingStatus] = useState(null);
  const [generating, setGenerating] = useState(false);

  const [alerts, setAlerts] = useState([]);
  const [alertStatus, setAlertStatus] = useState(null);

  const [live, setLive] = useState(false);
  const [lastUpdate, setLastUpdate] = useState(null);
  const [pulse, setPulse] = useState(false);

  const sourceRef = useRef(source);
  useEffect(() => { sourceRef.current = source; }, [source]);

  const reload = useCallback(async () => {
    setError(null);
    try {
      const src = sourceRef.current;
      const [sig, fnd, nws, brf, st, alr, alst] = await Promise.all([
        api(`/api/signals${src ? `?source=${src}` : ''}`),
        api('/api/funds'),
        api('/api/news'),
        api('/api/briefings/today'),
        api('/api/briefings/status'),
        api('/api/alerts'),
        api('/api/alerts/status'),
      ]);
      setSignals(sig); setFunds(fnd); setNews(nws); setBriefings(brf); setBriefingStatus(st);
      setAlerts(alr); setAlertStatus(alst);
    } catch {
      setError('Could not reach the backend on :8080. Is it running?');
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadSignals = useCallback(async () => {
    const src = sourceRef.current;
    try { setSignals(await api(`/api/signals${src ? `?source=${src}` : ''}`)); } catch { /* ignore */ }
  }, []);

  // Initial load + live WebSocket connection.
  useEffect(() => {
    reload();
    // Dev: backend is on :8080 (Vite serves the app on :5173). Prod: same origin as the page.
    const wsUrl = import.meta.env.DEV
      ? `ws://${window.location.hostname}:8080/ws`
      : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`;
    const client = new Client({
      brokerURL: wsUrl,
      reconnectDelay: 5000,
      onConnect: () => {
        setLive(true);
        client.subscribe('/topic/updates', (msg) => {
          try {
            const u = JSON.parse(msg.body);
            setLastUpdate(u);
            if (u.newCount > 0) {
              setPulse(true);
              setTimeout(() => setPulse(false), 1500);
              reload();
            }
          } catch { /* ignore */ }
        });
      },
      onWebSocketClose: () => setLive(false),
    });
    client.activate();
    return () => { client.deactivate(); };
  }, [reload]);

  // Refetch signals when the source filter changes (after the initial load).
  useEffect(() => { if (!loading) reloadSignals(); }, [source]); // eslint-disable-line

  const refresh = async () => {
    setRefreshing(true);
    try {
      await Promise.all([
        api('/api/signals/refresh', { method: 'POST' }),
        api('/api/news/refresh', { method: 'POST' }),
      ]);
      await reload();
    } catch { setError('Refresh failed.'); }
    finally { setRefreshing(false); }
  };

  const generateBriefings = async () => {
    setGenerating(true);
    try {
      await api('/api/briefings/generate', { method: 'POST' });
      await reload();
    } catch { setError('Briefing generation failed.'); }
    finally { setGenerating(false); }
  };

  const counts = signals.reduce((a, s) => { a[s.source] = (a[s.source] || 0) + 1; return a; }, {});

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <div className="eyebrow">Signal Desk · personal research</div>
          <h1>SignalDesk</h1>
        </div>
        <div className="top-actions">
          <div className={`livewrap ${pulse ? 'pulse' : ''}`}>
            <span className={`live-dot ${live ? 'on' : ''}`} />
            <span className="live-text">{live ? 'Live' : 'Offline'}</span>
            {lastUpdate && (
              <span className="live-last">
                {SOURCE_LABEL[lastUpdate.source] || lastUpdate.source}
                {lastUpdate.newCount > 0 ? ` +${lastUpdate.newCount}` : ' ·'} {fmtTime(lastUpdate.at)}
              </span>
            )}
          </div>
          <div className="stat"><b>{signals.length}</b><span>signals shown</span></div>
          <button className="refresh" onClick={refresh} disabled={refreshing}>
            {refreshing ? 'Refreshing…' : 'Refresh data'}
          </button>
        </div>
      </header>

      {error && <div className="banner error">{error}</div>}

      <div className="filters">
        {SOURCES.map((s) => (
          <button
            key={s.label}
            className={`chip ${source === s.key ? 'active' : ''}`}
            onClick={() => setSource(s.key)}
          >
            {s.label}
            {s.key && counts[s.key] != null && <span className="chip-count">{counts[s.key]}</span>}
          </button>
        ))}
      </div>

      <section className="panel ai-panel">
        <div className="panel-head">
          <h2>AI briefing {briefingStatus?.model && <span className="ai-model">{briefingStatus.model}</span>}</h2>
          <button
            className="gen-btn"
            onClick={generateBriefings}
            disabled={generating || !briefingStatus?.enabled}
          >
            {generating ? 'Generating…' : 'Generate briefings'}
          </button>
        </div>
        {!briefingStatus?.enabled ? (
          <p className="muted small">
            Set an <code>ANTHROPIC_API_KEY</code> and restart the backend to enable AI briefings —
            Claude synthesizes the trade signals + news into a per-ticker research read.
          </p>
        ) : briefings.length === 0 ? (
          <p className="muted small">No briefings yet today. Click “Generate briefings”.</p>
        ) : (
          <div className="briefs">
            {briefings.map((b) => (
              <div className="brief" key={b.id}>
                <div className="brief-head">
                  <span className="ticker">{b.ticker}</span>
                  <SidePill side={b.signal} />
                  <Confidence value={b.confidence} />
                </div>
                <p className="brief-sum">{b.summary}</p>
              </div>
            ))}
          </div>
        )}
        <p className="ai-disclaimer">AI-generated research — not financial advice. Data is delayed by disclosure rules.</p>
      </section>

      <div className="layout">
        <main className="panel">
          <div className="panel-head">
            <h2>Trade signals</h2>
            <span className="muted small">insider · congress · 13F — one normalized feed</span>
          </div>
          {loading ? <p className="muted">Loading…</p>
            : signals.length === 0 ? <p className="muted">No signals yet.</p>
            : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Ticker</th><th>Side</th><th>Source</th><th>Actor</th>
                      <th>Transacted</th><th>Disclosed</th><th>Conf</th>
                    </tr>
                  </thead>
                  <tbody>{signals.map((s) => <SignalRow key={s.id} s={s} />)}</tbody>
                </table>
              </div>
            )}
        </main>

        <aside className="rail">
          <section className="panel">
            <div className="panel-head">
              <h2>Alerts</h2>
              <span className={`line-badge ${alertStatus?.lineConfigured ? 'on' : ''}`}>
                {alertStatus?.lineConfigured ? 'LINE on' : 'LINE off'}
              </span>
            </div>
            {alerts.length === 0 ? (
              <p className="muted small">
                No alerts yet. High-confidence BUY/SELL briefings become alerts; set{' '}
                <code>LINE_CHANNEL_TOKEN</code> + <code>LINE_USER_ID</code> to push them to your phone.
              </p>
            ) : (
              <ul className="alerts">
                {alerts.map((a) => (
                  <li key={a.id}>
                    <div className="alert-top">
                      <span className="ticker">{a.ticker}</span>
                      <SidePill side={a.signal} />
                      <span className={`alert-state ${a.sentAt ? 'sent' : ''}`}>
                        {a.sentAt ? 'pushed' : 'queued'}
                      </span>
                    </div>
                    <p className="alert-reason">{a.reason}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <div className="panel-head"><h2>Tracked funds</h2></div>
            {funds.length === 0 ? <p className="muted small">No funds.</p>
              : funds.map((f) => <Fund key={f.cik} fund={f} />)}
          </section>

          <section className="panel">
            <div className="panel-head"><h2>Company news</h2></div>
            {news.length === 0 ? (
              <p className="muted small">
                No news yet. Set a free <code>FINNHUB_API_KEY</code> and refresh to enable news ingestion.
              </p>
            ) : (
              <ul className="news">
                {news.slice(0, 15).map((n) => (
                  <li key={n.id}>
                    <a href={n.url} target="_blank" rel="noreferrer">{n.headline}</a>
                    <div className="news-meta">
                      <span className="news-ticker">{n.ticker}</span>
                      <span>{n.source}</span>
                      <span>{fmtDate(n.publishedAt)}</span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>

      <footer className="foot">
        Research tool · AI output is not financial advice · disclosed-trade data is delayed by design.
      </footer>
    </div>
  );
}
