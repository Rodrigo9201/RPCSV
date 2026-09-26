'use strict';

const Lock = (() => {
  let unlocked = false;
  let clockTimer = null;

  function startClock() {
    // Um unico timer por instancia. Antes cada render() criava um setInterval
    // novo sem nunca limpar, entao trocar de usuario varias vezes deixava
    // N timers de 1 Hz escrevendo no DOM para sempre.
    if (clockTimer) { clearInterval(clockTimer); clockTimer = null; }
    const clockEl = document.getElementById('lock-clock');
    const dateEl = document.getElementById('lock-date');
    if (!clockEl || !dateEl) return;
    const tick = () => {
      const d = new Date();
      clockEl.textContent = fmtTime(d, false);
      dateEl.textContent = fmtDate(d);
    };
    tick();
    clockTimer = setInterval(tick, 1000);
  }

  function stopClock() {
    if (clockTimer) { clearInterval(clockTimer); clockTimer = null; }
  }

  async function render() {
    const cfg = await window.rpcsv.getConfig();
    const avatarEl = document.getElementById('lock-avatar');
    if (!avatarEl) return;
    avatarEl.innerHTML = avatarSvg(Number(cfg.avatar) || 0, 44);
    document.getElementById('lock-uname').textContent = cfg.user || 'RPCSV';
    startClock();
  }

  function unlock() {
    if (unlocked) return;
    unlocked = true;
    const lockEl = document.getElementById('screen-lock');
    if (!lockEl) { unlocked = false; return; }
    document.querySelectorAll('.lock-unlocking').forEach((el) => el.classList.remove('lock-unlocking'));
    lockEl.classList.add('lock-unlocking');
    lockEl.classList.add('flip-away');
    setTimeout(() => {
      // Home.render() pode rejeitar; sem este catch o `unlocked` ficava em true
      // para sempre e a tela de bloqueio nunca mais abria (app travado).
      Promise.resolve()
        .then(() => Home.render())
        .then(() => {
          showScreen('home');
          Home.entrance();
          lockEl.classList.remove('lock-unlocking', 'flip-away');
        })
        .catch((e) => {
          console.error('Lock: falha ao abrir a home', e);
          lockEl.classList.remove('lock-unlocking', 'flip-away');
          toast('Erro ao abrir a tela inicial: ' + ((e && e.message) || e));
        })
        .finally(() => { unlocked = false; });
    }, 520);
  }

  function bind() {
    const lockEl = document.getElementById('screen-lock');
    const handle = document.getElementById('lock-handle');
    const ripple = document.getElementById('lock-ripple');
    let startY = null;
    let active = false;

    const threshold = () => Math.max(60, Math.round(window.innerHeight * 0.12));

    const clientY = (e) =>
      e.clientY != null ? e.clientY : (e.touches && e.touches[0] && e.touches[0].clientY)
        || (e.changedTouches && e.changedTouches[0] && e.changedTouches[0].clientY) || null;

    const down = (e) => {
      try { if (e.cancelable) e.preventDefault(); } catch (ignore) {}
      active = true;
      startY = clientY(e);
    };
    const move = (e) => {
      if (!active) return;
      const y = clientY(e);
      if (y == null) return;
      try { if (e.cancelable) e.preventDefault(); } catch (ignore) {}
      const dy = startY - y;
      const pct = Math.max(0, Math.min(1, dy / 200));
      handle.style.transform = `translateY(${(-pct * 40).toFixed(1)}px)`;
      ripple.style.opacity = pct + '';
      ripple.style.background = `radial-gradient(circle at 50% 100%, rgba(120,200,255,${(0.5 * pct).toFixed(2)}) 0%, transparent 65%)`;
      ripple.style.transform = `scale(${(0.6 + pct * 0.8).toFixed(3)})`;
    };
    const up = (e) => {
      try { if (e.cancelable) e.preventDefault(); } catch (ignore) {}
      const y = clientY(e);
      const dy = active ? ((startY || 0) - (y || 0)) : 0;
      const tap = active && y != null && dy >= -18 && dy <= 18;
      active = false;
      startY = null;
      handle.style.transform = '';
      ripple.style.opacity = '0';
      if (dy > threshold() || tap) unlock();
    };

    const opts = { passive: false };
    ['pointerdown', 'touchstart'].forEach((n) => lockEl.addEventListener(n, down, opts));
    ['pointermove', 'touchmove'].forEach((n) => lockEl.addEventListener(n, move, opts));
    ['pointerup', 'pointercancel', 'touchend', 'touchcancel'].forEach((n) => lockEl.addEventListener(n, up));
  }

  return { init: async () => { bind(); await render(); }, refresh: render, unlock, stopClock };
})();