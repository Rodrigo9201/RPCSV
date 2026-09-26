'use strict';

const Games = (() => {
  let active = false;
  let timers = [];

  const PT = () => (currentLangCode || 'pt-BR').startsWith('pt');
  const el = (id) => document.getElementById(id);

  function later(fn, ms) {
    const id = setTimeout(fn, ms);
    timers.push(id);
    return id;
  }

  function clearTimers() {
    timers.forEach((id) => clearTimeout(id));
    timers = [];
  }

  function escAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function bodyHide(off) {
    document.body.classList.toggle('game-immersive', !off);
  }

  async function launch(game, opts) {
    active = true;
    clearTimers();
    const ready = opts && typeof opts.onReady === 'function' ? opts.onReady : null;
    const tid = String((game && game.titleId) || '');
    const title = String((game && game.title) || tid);
    const artEl = el('game-art');
    const icon = game && game.icon ? await iconHref(game.icon) : '';
    artEl.innerHTML = '';
    if (icon) {
      const im = document.createElement('img');
      im.className = 'ga-img';
      im.alt = '';
      // Handler via addEventListener: a CSP (script-src 'self') bloqueia onerror inline.
      im.addEventListener('error', () => {
        const fb = document.createElement('div');
        fb.className = 'ga-svg';
        artEl.replaceChildren(fb);
      }, { once: true });
      im.src = String(icon);
      artEl.appendChild(im);
    } else {
      const fb = document.createElement('div');
      fb.className = 'ga-svg';
      fb.innerHTML = gameArtSvg(tid, tid);
      artEl.appendChild(fb);
    }
    el('game-title').textContent = title || tid;
    el('game-tid').textContent = tid;
    el('game-note').textContent = '';
    el('game-boot-label').textContent = PT() ? 'Carregando jogo…' : 'Loading game…';
    el('game-bootbar').style.width = '0%';
    el('game-top-title').textContent = title || tid;

    showScreen('game');
    bodyHide(true);

    const steps = 24;
    let i = 0;
    const tick = () => {
      i += 1;
      const p = Math.min(100, Math.round((i / steps) * 100));
      el('game-bootbar').style.width = p + '%';
      if (p >= 100) {
        clearTimers();
        live(ready);
        return;
      }
      timers.push(setTimeout(tick, 46));
    };
    timers.push(setTimeout(tick, 120));
  }

  function live(ready) {
    el('screen-game').dataset.state = 'live';
    el('game-live-label').textContent = PT() ? 'Agora jogando' : 'Now playing';
    if (ready) {
      Promise.resolve(ready()).then(function (r) {
        if (!r || r.ok === false) {
          el('game-note').textContent = PT()
            ? 'Não foi possível abrir o jogo — instale/configure o Vita3K.'
            : 'Could not open the game — install or configure Vita3K.';
        } else if (window.rpcsv.platform === 'android') {
          later(() => el('game-note').textContent = PT() ? 'Iniciando no motor Vita3K…' : 'Starting Vita3K engine…', 700);
        }
      });
      return;
    }
    if (window.rpcsv.platform !== 'android') {
      later(() => el('game-note').textContent = PT() ? 'Motor embutido iniciado no desktop.' : 'Embedded engine started on desktop.', 600);
    }
  }

  function hide() {
    active = false;
    clearTimers();
    bodyHide(false);
    const scr = el('screen-game');
    if (scr) scr.dataset.state = 'boot';
  }

  function exit() {
    hide();
    showScreen('home');
  }

  function init() {
    el('game-ps').addEventListener('click', () => exit());
    el('game-exit').addEventListener('click', () => exit());
    const fmt = (d) => {
      const p = (n) => String(n).padStart(2, '0');
      return p(d.getHours()) + ':' + p(d.getMinutes());
    };
    const upd = () => {
      const s = fmt(new Date());
      const a = el('game-top-time');
      const b = el('game-clk');
      if (a) a.textContent = s;
      if (b) b.textContent = s;
    };
    upd();
    setInterval(upd, 1000);
  }

  return { init, launch, hide, exit, isActive: () => active };
})();