'use strict';

const NAMES = {
  'performance.dynamic-distance.enabled': 'Dynamic view distance',
  'performance.pregen.enabled': 'Pre-generate chunks when empty',
  'security.anti-bot.enabled': 'Anti-bot',
  'security.spam-limit.enabled': 'Command and chat spam limit',
  'security.hide-plugins.enabled': 'Hide plugin list',
  'security.exploit-guard.enabled': 'Exploit guard',
  'security.auto-ban.enabled': 'Auto-ban repeat offenders',
  'security.vpn-check.enabled': 'VPN and proxy check',
  'security.connection-guard.enabled': 'Connection flood protection',
  'security.plugin-guard.enabled': 'Plugin backdoor protection',
  'votes.enabled': 'Vote listener',
  'backups.enabled': 'Automatic backups',
  'updates.check': 'Check for updates'
};

const $ = (id) => document.getElementById(id);
let after = 0;
let timers = [];

async function api(path, body) {
  const opt = { credentials: 'same-origin', headers: {} };
  if (body !== undefined) {
    opt.method = 'POST';
    opt.headers['Content-Type'] = 'application/json';
    opt.headers['X-Ashlar'] = '1';
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(path, opt);
  let data = {};
  try { data = await r.json(); } catch (e) { /* empty answer */ }
  if (r.status === 401) { showSignIn(); throw new Error('signed out'); }
  if (!r.ok) throw new Error(data.error || ('Error ' + r.status));
  return data;
}

function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function say(id, text, bad) {
  const m = $(id);
  m.textContent = text;
  m.classList.toggle('bad', !!bad);
}

function showSignIn() {
  timers.forEach(clearInterval);
  timers = [];
  $('app').hidden = true;
  $('signin').hidden = false;
  $('token').focus();
}

function showApp() {
  $('signin').hidden = true;
  $('app').hidden = false;
  after = 0;
  $('log').textContent = '';
  status();
  consoleTail();
  timers.push(setInterval(status, 3000), setInterval(consoleTail, 1500));
}

async function status() {
  let s;
  try { s = await api('/api/status'); } catch (e) { return; }
  $('ver').textContent = 'Minecraft ' + s.version;
  $('stats').replaceChildren(el('span', 'TPS ' + s.tps), el('span', 'MSPT ' + s.mspt), el('span', 'Memory ' + s.memUsed + ' / ' + s.memMax + ' MB'));

  const pb = $('players').tBodies[0];
  pb.replaceChildren();
  for (const p of s.players) {
    const tr = el('tr');
    tr.append(el('td', p.name), el('td', p.ping + ' ms', 'num dim'));
    const td = el('td');
    const b = el('button', 'Kick', 'plain');
    b.type = 'button';
    b.addEventListener('click', async () => {
      if (!window.confirm('Kick ' + p.name + '?')) return;
      try { await api('/api/kick', { name: p.name }); } catch (e) { /* shown on next refresh */ }
    });
    td.append(b);
    tr.append(td);
    pb.append(tr);
  }
  $('pcount').textContent = s.players.length + ' / ' + s.maxPlayers;
  $('noplayers').hidden = s.players.length > 0;

  const wb = $('worlds').tBodies[0];
  wb.replaceChildren();
  for (const w of s.worlds) {
    const tr = el('tr');
    tr.append(el('td', w.name), el('td', String(w.chunks), 'num'), el('td', String(w.entities), 'num'));
    wb.append(tr);
  }

  const ul = $('switches');
  if (!ul.children.length) {
    for (const key of Object.keys(s.switches)) {
      const li = el('li');
      const lab = el('label');
      const cb = el('input');
      cb.type = 'checkbox';
      cb.dataset.key = key;
      cb.addEventListener('change', async () => {
        cb.disabled = true;
        try { await api('/api/switch', { key, on: cb.checked }); } catch (e) { cb.checked = !cb.checked; }
        cb.disabled = false;
      });
      lab.append(cb, document.createTextNode(NAMES[key] || key));
      li.append(lab);
      ul.append(li);
    }
  }
  for (const cb of ul.querySelectorAll('input')) {
    if (!cb.disabled) cb.checked = !!s.switches[cb.dataset.key];
  }

  const bl = $('backups');
  bl.replaceChildren();
  if (!s.backups.length) bl.append(el('li', 'No backups yet.', 'dim'));
  for (const b of s.backups.slice(0, 6)) bl.append(el('li', b));

  const ll = $('lines');
  ll.replaceChildren();
  for (const l of s.lines) ll.append(el('li', l));
}

async function consoleTail() {
  let d;
  try { d = await api('/api/console?after=' + after); } catch (e) { return; }
  if (!d.lines.length) return;
  const log = $('log');
  const atEnd = log.scrollTop + log.clientHeight >= log.scrollHeight - 30;
  const frag = document.createDocumentFragment();
  for (const [n, text] of d.lines) {
    after = Math.max(after, Number(n));
    const cls = text.includes(' WARN] ') ? 'w' : text.includes(' ERROR] ') ? 'e' : '';
    frag.append(el('span', text + '\n', cls));
  }
  log.append(frag);
  while (log.childNodes.length > 1500) log.firstChild.remove();
  if (atEnd) log.scrollTop = log.scrollHeight;
  $('cmd-in').disabled = !d.commands;
  if (!d.commands) $('cmd-in').placeholder = 'Console commands are turned off in ashlar.yml';
}

$('signin').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  say('signin-msg', '');
  try {
    await api('/api/login', { token: $('token').value });
    $('token').value = '';
    showApp();
  } catch (e) {
    say('signin-msg', e.message, true);
  }
});

$('signout').addEventListener('click', async () => {
  try { await api('/api/logout', {}); } catch (e) { /* already out */ }
  showSignIn();
});

$('cmd').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const v = $('cmd-in').value.trim();
  if (!v) return;
  say('cmd-msg', '');
  try {
    await api('/api/command', { command: v });
    $('cmd-in').value = '';
    setTimeout(consoleTail, 300);
  } catch (e) {
    say('cmd-msg', e.message, true);
  }
});

$('backup-now').addEventListener('click', async () => {
  const b = $('backup-now');
  b.disabled = true;
  say('backup-msg', 'Backing up. The result shows in the console.');
  try { await api('/api/backup', {}); } catch (e) { say('backup-msg', e.message, true); }
  setTimeout(() => { b.disabled = false; }, 4000);
});

api('/api/status').then(showApp, (e) => { if (e.message !== 'signed out') showSignIn(); });
