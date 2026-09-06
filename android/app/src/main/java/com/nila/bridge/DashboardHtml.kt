package com.nila.bridge

/**
 * The dashboard page, served to a laptop browser.
 *
 * Self-contained: no CDN, no fonts to fetch, no framework. The phone is serving
 * this on a local network with no route to the internet guaranteed, and a
 * dashboard that renders unstyled because a stylesheet did not load is worse
 * than useless at 3am.
 *
 * It polls the phone's own JSON endpoint every two seconds, which is often
 * enough to feel live and rare enough to be irrelevant to battery.
 */
internal val DASHBOARD_HTML = """
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nila - night view</title>
<style>
  :root{
    --bg:#0f1211; --card:#171b1a; --line:#232827; --ink:#e8ecea;
    --dim:#8d9895; --teal:#4fbfa6; --amber:#d9a44a; --red:#e8887a;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);
    font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
  .wrap{max-width:1000px;margin:0 auto;padding:28px 22px 60px}
  h1{font-size:26px;margin:0 0 4px;letter-spacing:-.02em}
  .sub{color:var(--dim);margin:0 0 26px;font-size:14px}
  .row{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));
    gap:12px;margin-bottom:26px}
  .card{background:var(--card);border:1px solid var(--line);border-radius:8px;
    padding:16px 18px}
  .card dt{font-size:11px;letter-spacing:.11em;text-transform:uppercase;
    color:var(--dim);margin:0 0 6px}
  .card dd{margin:0;font-size:28px;font-weight:600;font-variant-numeric:tabular-nums}
  .card dd small{font-size:14px;color:var(--dim);font-weight:400}
  .status{display:flex;align-items:center;gap:10px;font-size:19px;font-weight:600;
    margin-bottom:22px}
  .dot{width:11px;height:11px;border-radius:50%;background:var(--dim)}
  .dot.on{background:var(--teal);animation:pulse 3.2s ease-in-out infinite}
  @keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
  @media (prefers-reduced-motion:reduce){.dot.on{animation:none}}
  h2{font-size:13px;letter-spacing:.11em;text-transform:uppercase;color:var(--dim);
    margin:0 0 10px;font-weight:600}
  table{width:100%;border-collapse:collapse;font-size:14px}
  th{text-align:left;font-size:11px;letter-spacing:.09em;text-transform:uppercase;
    color:var(--dim);padding:0 12px 8px 0;border-bottom:1px solid var(--line);
    font-weight:600}
  td{padding:9px 12px 9px 0;border-bottom:1px solid var(--line);color:var(--ink)}
  td.dim{color:var(--dim);font-variant-numeric:tabular-nums}
  .sev{display:inline-block;padding:2px 7px;border-radius:3px;font-size:11px;
    font-weight:700;letter-spacing:.05em}
  .s1{background:#1f2624;color:var(--dim)}
  .s2{background:#2a2214;color:var(--amber)}
  .s3,.s4{background:#2c1815;color:var(--red)}
  .empty{color:var(--dim);padding:22px 0}
  footer{margin-top:34px;padding-top:16px;border-top:1px solid var(--line);
    color:var(--dim);font-size:12.5px}
</style>
<div class="wrap">
  <h1>Nila</h1>
  <p class="sub">Read-only view, served by the phone on your local network.
     No audio or video is ever sent here.</p>

  <div class="status"><span class="dot" id="dot"></span><span id="status">connecting</span></div>

  <div class="row">
    <div class="card"><dt>Crying today</dt><dd id="mins">-<small> min</small></dd></div>
    <div class="card"><dt>Episodes</dt><dd id="eps">-</dd></div>
    <div class="card"><dt>Running on</dt><dd id="accel" style="font-size:20px">-</dd></div>
    <div class="card"><dt>Detect latency</dt><dd id="lat">-<small> ms</small></dd></div>
  </div>

  <h2>Tonight</h2>
  <table>
    <thead><tr><th>Time</th><th>Event</th><th>Duration</th><th>Trend</th><th>Level</th></tr></thead>
    <tbody id="rows"></tbody>
  </table>
  <div class="empty" id="empty">Nothing recorded yet.</div>

  <footer>
    Updates every 2 seconds. Close this tab and the phone keeps monitoring.
    Nila is an awareness aid, not a medical device.
  </footer>
</div>
<script>
const NAMES = {
  CRY_STARTED:'Crying started', CRY_ONGOING:'Fussing', CRY_ENDED:'Cry ended',
  SOOTHE_PLAYED:'Played a sound', SOOTHE_WORKED:'That settled her',
  SOOTHE_FAILED:"That didn't help", ESCALATED_TO_PARENT:'Alerted you',
  FACE_NOT_VISIBLE:"Couldn't see the face", FACE_RETURNED:'Face visible again',
  STILLNESS:'Unusually still', GUARDIAN_FAULT:'Monitoring problem'
};
const pad = n => String(n).padStart(2,'0');
function clock(ms){ const d=new Date(ms); return pad(d.getHours())+':'+pad(d.getMinutes()); }

async function tick(){
  try{
    const r = await fetch('/api', {cache:'no-store'});
    const d = await r.json();
    document.getElementById('dot').className = 'dot' + (d.monitoring ? ' on' : '');
    document.getElementById('status').textContent = d.status;
    document.getElementById('mins').innerHTML =
      Math.round(d.cryingSecondsToday/60) + '<small> min</small>';
    document.getElementById('eps').textContent = d.episodesToday;
    document.getElementById('accel').textContent = d.accelerator;
    document.getElementById('lat').innerHTML =
      d.detectLatencyMs.toFixed(1) + '<small> ms</small>';

    const rows = d.events.map(e =>
      '<tr><td class="dim">'+clock(e.at)+'</td>'+
      '<td>'+(NAMES[e.kind]||e.kind)+'</td>'+
      '<td class="dim">'+(e.seconds?e.seconds+'s':'')+'</td>'+
      '<td class="dim">'+(e.trend?e.trend.toLowerCase():'')+'</td>'+
      '<td><span class="sev s'+e.severity+'">L'+e.severity+'</span></td></tr>'
    ).join('');
    document.getElementById('rows').innerHTML = rows;
    document.getElementById('empty').style.display = rows ? 'none' : 'block';
  }catch(err){
    document.getElementById('status').textContent = 'phone unreachable';
    document.getElementById('dot').className = 'dot';
  }
}
tick(); setInterval(tick, 2000);
</script>
""".trimIndent()
