'use strict';

const SystemApps = (() => {
  const $ = (id) => document.getElementById(id);
  const audioEl = () => $('app-audio');
  const ovEl = () => $('media-overlay');
  const PT = () => (currentLangCode || 'pt-BR').startsWith('pt');

  const MEDIA_EXT = {
    music: ['mp3', 'ogg', 'wav', 'm4a', 'flac', 'aac', 'opus'],
    photos: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'],
    videos: ['mp4', 'webm', 'mkv', 'mov', 'avi', '3gp'],
  };

  function ext(name) {
    const i = String(name).lastIndexOf('.');
    return i < 0 ? '' : String(name).slice(i + 1).toLowerCase();
  }
  function fileUrl(p) {
    return 'file://' + encodeURI(String(p).replace(/\\/g, '/'));
  }
  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  async function mediaBase(sub) {
    const cfg = await window.rpcsv.getConfig();
    const base = cfg.installDir || (await window.rpcsv.installDir());
    return base + '/' + sub;
  }

  async function scan(sub) {
    const dir = await mediaBase(sub);
    const list = await window.rpcsv.listDir(dir);
    const files = [];
    if (list && Array.isArray(list)) {
      for (const e of list) {
        if (e.d) continue;
        if (MEDIA_EXT[sub].indexOf(ext(e.n)) >= 0) files.push({ name: e.n, path: dir + '/' + e.n });
      }
    }
    files.sort((a, b) => a.name.localeCompare(b.name));
    return { dir, files };
  }

  async function importMedia(sub) {
    const file = await window.rpcsv.pickFile({
      title: PT() ? 'Adicionar arquivo' : 'Add file',
      filters: [{ name: 'Arquivo', extensions: ['*'] }],
    });
    if (!file) return;
    const dir = await mediaBase(sub);
    const name = String(file).split(/[\\/]/).pop();
    const ok = await window.rpcsv.copyFile(file, dir + '/' + name);
    toast(ok ? '✓' : (PT() ? 'Falha ao copiar' : 'Copy failed'));
    open(sub);
  }

  function emptyBox(text) {
    const d = document.createElement('div');
    d.className = 'empty-box';
    d.textContent = text;
    return d;
  }

  async function open(name) {
    showScreen(name);
    if (name === 'browser') Browser.render();
    else if (name === 'music') Music.render();
    else if (name === 'photos') Photos.render();
    else if (name === 'videos') Videos.render();
    else if (name === 'trophies') Trophies.render();
    else if (name === 'friends') Friends.render();
    else if (name === 'party') Party.render();
    else if (name === 'messages') Messages.render();
    else if (name === 'camera') Camera.render();
    else if (name === 'maps') Maps.render();
    else if (name === 'store') Store.render();
    else if (name === 'calendar') Calendar.render();
    else if (name === 'email') window.rpcsv.openExternal('mailto:');
  }

  // ---------------------------------------------------------------- Browser
  const Browser = {
    LINKS: [
      { n: 'Vita3K GitHub (releases)', u: 'https://github.com/Vita3K/Vita3K/releases' },
      { n: 'Vita Homebrew Guide', u: 'https://vita.hacks.guide/' },
      { n: PT() ? 'Lista de firmware da Sony' : 'Sony firmware list', u: 'http://dus01.psp2.update.playstation.net/update/psp2/list/us/psp2-updatelist.xml' },
      { n: 'PKGj (homebrew)', u: 'https://pkgj.dev/' },
      { n: 'RPCSV (este projeto)', u: 'https://github.com/Vita3K/Vita3K' },
    ],
    render() {
      const grid = $('browser-links');
      grid.innerHTML = '';
      this.LINKS.forEach((l) => {
        const el = document.createElement('button');
        el.className = 'link-card';
        el.innerHTML = `<strong>${esc(l.n)}</strong><span>${esc(l.u)}</span>`;
        el.addEventListener('click', () => window.rpcsv.openExternal(l.u));
        grid.appendChild(el);
      });
    },
    open() {
      let u = $('browser-url').value.trim();
      if (!u) return;
      if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(u)) u = 'https://' + u;
      window.rpcsv.openExternal(u);
    },
  };

  // ------------------------------------------------------------------ Music
  const Music = {
    tracks: [],
    current: -1,
    playing: false,
    async render() {
      const { dir, files } = await scan('music');
      this.tracks = files;
      this.current = -1;
      this.playing = false;
      const a = audioEl();
      a.pause();
      a.removeAttribute('src');
      $('music-info').textContent = files.length ? dir : (PT() ? 'Pasta vazia. Adicione músicas (mp3, ogg, flac...).' : 'Empty folder. Add songs (mp3, ogg, flac...).');
      const list = $('music-list');
      list.innerHTML = '';
      if (!files.length) {
        list.appendChild(emptyBox(PT() ? 'Nenhuma música ainda.' : 'No music yet.'));
        this.updateNP();
        return;
      }
      files.forEach((f, i) => {
        const el = document.createElement('div');
        el.className = 'track';
        el.dataset.idx = i;
        el.innerHTML = `<span class="trk-n">${i + 1}</span><span class="trk-t">${esc(f.name)}</span>`;
        el.addEventListener('click', () => Music.play(i));
        list.appendChild(el);
      });
      this.updateNP();
    },
    play(i) {
      if (i < 0 || i >= this.tracks.length) return;
      this.current = i;
      const a = audioEl();
      a.src = fileUrl(this.tracks[i].path);
      a.play().catch(() => {});
      this.playing = true;
      document.querySelectorAll('#music-list .track').forEach((el) => {
        el.classList.toggle('on', Number(el.dataset.idx) === i);
      });
      this.updateNP();
    },
    toggle() {
      const a = audioEl();
      if (!a.src) return;
      if (a.paused) {
        a.play().catch(() => {});
        this.playing = true;
      } else {
        a.pause();
        this.playing = false;
      }
      this.updateNP();
    },
    step(dirIdx) {
      const n = this.tracks.length;
      if (!n) return;
      const next = this.current < 0 ? 0 : (this.current + dirIdx + n) % n;
      this.play(next);
    },
    updateNP() {
      $('np-title').textContent = this.current >= 0 ? this.tracks[this.current].name : (PT() ? 'Nada tocando' : 'Nothing playing');
      $('np-play').textContent = this.playing ? '❚❚' : '▶';
      const a = audioEl();
      $('np-seek').value = a.duration ? Math.round((a.currentTime / a.duration) * 100) : 0;
      $('np-time').textContent = fmtTime(a.currentTime) + ' / ' + fmtTime(a.duration);
    },
  };

  function fmtTime(s) {
    if (!isFinite(s) || s < 0) s = 0;
    const m = Math.floor(s / 60);
    const sec = Math.floor(s % 60);
    return m + ':' + String(sec).padStart(2, '0');
  }

  // ----------------------------------------------------------------- Photos
  const Photos = {
    async render() {
      const { dir, files } = await scan('photos');
      $('photo-info').textContent = files.length ? dir : (PT() ? 'Pasta vazia. Adicione imagens (jpg, png, webp...).' : 'Empty folder. Add images (jpg, png, webp...).');
      const grid = $('photo-grid');
      grid.innerHTML = '';
      if (!files.length) {
        grid.appendChild(emptyBox(PT() ? 'Nenhuma foto ainda.' : 'No photos yet.'));
        return;
      }
      files.forEach((f, i) => {
        const el = document.createElement('div');
        el.className = 'photo-card';
        const img = document.createElement('img');
        img.src = fileUrl(f.path);
        img.loading = 'lazy';
        img.alt = f.name;
        el.appendChild(img);
        el.addEventListener('click', () => lightbox(files, i));
        grid.appendChild(el);
      });
    },
  };

  function lightbox(files, idx) {
    const ov = ovEl();
    const stage = $('ov-stage');
    stage.innerHTML = '';
    const img = document.createElement('img');
    img.className = 'ov-img';
    const cap = document.createElement('div');
    cap.className = 'ov-cap';
    const nav = document.createElement('div');
    nav.className = 'ov-nav';
    nav.innerHTML = `<button class="ov-btn" id="ov-prev">&#9664;</button><button class="ov-btn" id="ov-close">&#10005;</button><button class="ov-btn" id="ov-next">&#9654;</button>`;
    stage.appendChild(img);
    stage.appendChild(cap);
    stage.appendChild(nav);
    const prev = nav.querySelector('#ov-prev');
    const next = nav.querySelector('#ov-next');
    const close = nav.querySelector('#ov-close');
    const show = () => {
      img.src = fileUrl(files[idx].path);
      cap.textContent = idx + 1 + '/' + files.length + '  ' + files[idx].name;
    };
    show();
    const navLeft = () => { idx = (idx - 1 + files.length) % files.length; show(); };
    const navRight = () => { idx = (idx + 1) % files.length; show(); };
    prev.addEventListener('click', navLeft);
    next.addEventListener('click', navRight);
    close.addEventListener('click', closeOv);
    const key = (e) => {
      if (e.key === 'ArrowLeft') navLeft();
      else if (e.key === 'ArrowRight') navRight();
      else if (e.key === 'Escape') closeOv();
    };
    document.addEventListener('keydown', key);
    ov._key = key;
    ov.classList.remove('hidden');
  }

  function closeOv() {
    const ov = ovEl();
    ov.classList.add('hidden');
    $('ov-stage').innerHTML = '';
    if (ov._key) document.removeEventListener('keydown', ov._key);
    delete ov._key;
    audioEl().pause();
  }

  // ----------------------------------------------------------------- Videos
  const Videos = {
    async render() {
      const { dir, files } = await scan('videos');
      $('video-info').textContent = files.length ? dir : (PT() ? 'Pasta vazia. Adicione vídeos (mp4, webm...).' : 'Empty folder. Add videos (mp4, webm...).');
      const grid = $('video-grid');
      grid.innerHTML = '';
      if (!files.length) {
        grid.appendChild(emptyBox(PT() ? 'Nenhum vídeo ainda.' : 'No videos yet.'));
        return;
      }
      files.forEach((f) => {
        const el = document.createElement('div');
        el.className = 'video-card';
        el.innerHTML = `<span class="vc-ic">${iconSvg('videos')}</span><span class="vc-t">${esc(f.name)}</span>`;
        el.addEventListener('click', () => videoOverlay(f));
        grid.appendChild(el);
      });
    },
  };

  function videoOverlay(f) {
    const ov = ovEl();
    const stage = $('ov-stage');
    stage.innerHTML = '';
    const cap = document.createElement('div');
    cap.className = 'ov-cap';
    cap.textContent = f.name;
    const vid = document.createElement('video');
    vid.className = 'ov-video';
    vid.controls = true;
    vid.autoplay = true;
    vid.src = fileUrl(f.path);
    const close = document.createElement('button');
    close.className = 'ov-close-btn';
    close.textContent = '✕';
    close.addEventListener('click', closeOv);
    stage.appendChild(cap);
    stage.appendChild(vid);
    stage.appendChild(close);
    ov.classList.remove('hidden');
  }

  // ----------------------------------------------------------------Trophies
  const TIERS = ['bronze', 'bronze', 'bronze', 'silver', 'gold'];
  const PTS = { bronze: 15, silver: 30, gold: 90 };
  const POOL = {
    pt: [
      ['Primeiros passos', 'Comece a aventura em %GAME%'],
      ['Colecionador', 'Complete o primeiro conjunto'],
      ['Rápido como um raio', 'Conclua uma fase rapidamente'],
      ['Veterano', 'Complete o jogo principal'],
      ['Lenda local', 'Ganhe todos os troféus deste jogo'],
    ],
    en: [
      ['First Steps', 'Begin the adventure in %GAME%'],
      ['Collector', 'Complete the first set'],
      ['Quick as Lightning', 'Finish a stage quickly'],
      ['Veteran', 'Complete the main game'],
      ['Local Legend', 'Earn every trophy in this game'],
    ],
  };

  function seed(s) {
    let n = 0;
    s = String(s || 'G');
    for (let i = 0; i < s.length; i++) n = (n * 31 + s.charCodeAt(i)) >>> 0;
    return n;
  }
  function buildSet(titleId) {
    const s = seed(titleId);
    const pool = POOL[PT() ? 'pt' : 'en'];
    return TIERS.map((tier, i) => {
      const item = pool[(i + s) % pool.length];
      return {
        id: titleId + '_' + i,
        tier,
        pts: PTS[tier],
        name: item[0].replace('%GAME%', titleId),
        desc: item[1].replace('%GAME%', titleId),
      };
    });
  }

  const Trophies = {
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const games = (await window.rpcsv.listApps()) || [];
      const data = cfg.trophies || {};
      const listEl = $('trophy-list');
      listEl.innerHTML = '';
      if (!games.length) {
        listEl.appendChild(emptyBox(PT() ? 'Sem jogos instalados para exibir troféus.' : 'No installed games to show trophies.'));
        $('trophy-summary').textContent = '';
        return;
      }
      let totalT = 0, earnedT = 0, totalPts = 0, gainedPts = 0;
      games.forEach((g, gi) => {
        const tid = g.titleId;
        const set = buildSet(tid);
        const st = data[tid] || { earned: {}, open: gi === 0 };
        const earned = st.earned || {};
        const ear = set.filter((t) => earned[t.id]).length;
        const ptsG = set.filter((t) => earned[t.id]).reduce((a, t) => a + t.pts, 0);
        const ptsT = set.reduce((a, t) => a + t.pts, 0);
        totalT += set.length; earnedT += ear; totalPts += ptsT; gainedPts += ptsG;

        const group = document.createElement('div');
        group.className = 'trophy-group';
        const head = document.createElement('button');
        head.className = 'trophy-head';
        head.innerHTML = `<span class="th-art">${gameArtSvg(tid, tid)}</span><span class="th-txt"><strong>${esc(tid)}</strong><span class="th-prog">${ear}/${set.length} · ${ptsG}/${ptsT} pts</span></span><span class="th-arrow">${st.open ? '▾' : '▸'}</span>`;
        head.addEventListener('click', async () => {
          const c = await window.rpcsv.getConfig();
          const d = Object.assign({}, c.trophies || {});
          const s2 = Object.assign({}, d[tid] || { inherited: true }, { open: !(d[tid] || {}).open });
          d[tid] = s2;
          await window.rpcsv.setConfig({ trophies: d });
          Trophies.render();
        });
        group.appendChild(head);

        if (st.open) {
          const body = document.createElement('div');
          body.className = 'trophy-body';
          set.forEach((t) => {
            const on = !!earned[t.id];
            const row = document.createElement('button');
            row.className = 'trophy-row ' + t.tier + (on ? ' earned' : '');
            row.innerHTML = `<span class="trop-ic">${iconSvg('trophy')}</span><span class="trop-txt"><strong>${esc(t.name)}</strong><span>${esc(t.desc)}</span></span><span class="trop-pts">${t.pts}</span>`;
            row.addEventListener('click', () => toggleTrophy(tid, t, !on));
            body.appendChild(row);
          });
          group.appendChild(body);
        }
        listEl.appendChild(group);
      });
      $('trophy-summary').textContent = PT()
        ? `${games.length} jogo(s) · ${earnedT}/${totalT} troféus · ${gainedPts}/${totalPts} pts`
        : `${games.length} game(s) · ${earnedT}/${totalT} trophies · ${gainedPts}/${totalPts} pts`;
    },
  };

  async function toggleTrophy(tid, t, on) {
    const cfg = await window.rpcsv.getConfig();
    const d = Object.assign({}, cfg.trophies || {});
    const st = Object.assign({}, d[tid] || {}, { earned: Object.assign({}, ((d[tid] || {}).earned) || {}) });
    if (on) st.earned[t.id] = true;
    else delete st.earned[t.id];
    d[tid] = st;
    await window.rpcsv.setConfig({ trophies: d });
    toast(on ? '🏆' : '');
    Trophies.render();
  }

  // -----------------------------------------------------------------Friends
  const Friends = {
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const list = cfg.friends || [];
      const wrap = $('friend-list');
      wrap.innerHTML = '';
      const me = document.createElement('div');
      me.className = 'friend-row you';
      me.innerHTML = `<span class="fr-ava">${avatarSvg(cfg.avatar || 0, 44)}</span><span class="fr-txt"><strong>${esc(cfg.user || 'RPCSV')}</strong><span class="fr-sub">${PT() ? 'Você' : 'You'}</span></span><span class="fr-on"></span>`;
      wrap.appendChild(me);
      if (!list.length) {
        wrap.appendChild(emptyBox(PT() ? 'Nenhum amigo ainda. Adicione alguns.' : 'No friends yet. Add some.'));
        return;
      }
      list.forEach((f, i) => {
        const row = document.createElement('div');
        row.className = 'friend-row';
        row.innerHTML = `<span class="fr-ava">${avatarSvg(f.avatar, 44)}</span><span class="fr-txt"><strong>${esc(f.name)}</strong><span class="fr-sub">${PT() ? 'Jogando agora' : 'Now playing'}</span></span><button class="fr-rm" data-i="${i}" title="Remover">✕</button>`;
        row.querySelector('.fr-rm').addEventListener('click', () => removeFriend(i));
        wrap.appendChild(row);
      });
    },
  };

  async function addFriend() {
    const name = await promptModal(PT() ? 'Adicionar amigo' : 'Add friend', '');
    if (!name) return;
    const cfg = await window.rpcsv.getConfig();
    const list = (cfg.friends || []).slice();
    list.push({ name, avatar: Math.floor(Math.random() * AVATARS.length), since: Date.now() });
    await window.rpcsv.setConfig({ friends: list });
    toast('✓');
    Friends.render();
  }

  async function removeFriend(i) {
    const cfg = await window.rpcsv.getConfig();
    const list = (cfg.friends || []).slice();
    list.splice(i, 1);
    await window.rpcsv.setConfig({ friends: list });
    Friends.render();
  }

  function promptModal(title, placeholder) {
    return new Promise((resolve) => {
      const modal = $('modal');
      $('modal-title').textContent = title;
      const input = $('modal-input');
      input.value = '';
      input.placeholder = placeholder || '';
      modal.classList.remove('hidden');
      input.focus();
      const done = (val) => {
        modal.classList.add('hidden');
        okBtn.removeEventListener('click', ok);
        cancelBtn.removeEventListener('click', cancel);
        input.removeEventListener('keydown', key);
        resolve(val);
      };
      const ok = () => done(input.value.trim() || null);
      const cancel = () => done(null);
      const key = (e) => {
        if (e.key === 'Enter') ok();
        if (e.key === 'Escape') cancel();
      };
      const okBtn = $('modal-ok');
      const cancelBtn = $('modal-cancel');
      okBtn.addEventListener('click', ok);
      cancelBtn.addEventListener('click', cancel);
      input.addEventListener('keydown', key);
    });
  }

  // ------------------------------------------------------------------- Party
  const Party = {
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const p = cfg.party || null;
      const me = cfg.user || 'RPCSV';
      const wrap = $('party-wrap');
      const list = $('party-members');
      const tools = $('party-tools');
      list.innerHTML = '';
      tools.innerHTML = '';
      if (!p || !p.name) {
        wrap.innerHTML = '';
        const box = emptyBox(PT() ? 'Nenhuma festa ativa.' : 'No active party.');
        const mk = document.createElement('button');
        mk.className = 'btn btn-sm';
        mk.textContent = PT() ? 'Criar festa' : 'Create party';
        mk.addEventListener('click', partyCreate);
        const row = document.createElement('div');
        row.className = 'tool-row';
        row.appendChild(mk);
        wrap.appendChild(box);
        wrap.appendChild(row);
        $('party-name').textContent = PT() ? 'Festa' : 'Party';
        return;
      }
      $('party-name').textContent = p.name;
      const seen = {};
      const names = [me].concat(p.members || []);
      names.forEach((n) => {
        if (!n || seen[n]) return;
        seen[n] = 1;
        const row = document.createElement('div');
        row.className = 'friend-row';
        row.innerHTML = `<span class="fr-ava">${avatarSvg(0, 40)}</span><span class="fr-txt"><strong>${esc(n)}</strong><span class="fr-sub">${n === me ? (PT() ? 'Você (anfitrião)' : 'You (host)') : (PT() ? 'Na festa' : 'In party')}</span></span><span class="fr-on"></span>`;
        list.appendChild(row);
      });
      const inv = document.createElement('button');
      inv.className = 'btn btn-sm';
      inv.textContent = PT() ? 'Convidar amigo' : 'Invite friend';
      inv.addEventListener('click', () => {
        const d = (cfg.friends || []).find((f) => !(p.members || []).includes(f.name));
        if (!d) { toast(PT() ? 'Todos os amigos já estão aqui.' : 'All friends are already here.'); return; }
        partyInvite(d.name);
      });
      const lv = document.createElement('button');
      lv.className = 'btn btn-sm';
      lv.textContent = PT() ? 'Encerrar festa' : 'End party';
      lv.addEventListener('click', partyLeave);
      tools.appendChild(inv);
      tools.appendChild(lv);
    },
  };

  async function partyCreate() {
    const name = await promptModal(PT() ? 'Nome da festa' : 'Party name', PT() ? 'minha festa' : 'my party');
    if (!name) return;
    const cfg = await window.rpcsv.getConfig();
    const members = (cfg.friends || []).slice(0, 3).map((f) => f.name);
    await window.rpcsv.setConfig({ party: { name, members, created: Date.now() } });
    toast('🎉');
    Party.render();
  }
  async function partyInvite(name) {
    const cfg = await window.rpcsv.getConfig();
    const p = cfg.party || { name: 'Festa', members: [] };
    const members = (p.members || []).slice();
    if (!members.includes(name)) members.push(name);
    await window.rpcsv.setConfig({ party: Object.assign({}, p, { members }) });
    toast('💬 ' + name);
    Party.render();
  }
  async function partyLeave() {
    await window.rpcsv.setConfig({ party: null });
    Party.render();
  }

  // -------------------------------------------------------------- Messages
  const Messages = {
    selected: null,
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const threads = cfg.messages || {};
      if (!Object.keys(threads).length) {
        threads['PlayStation'] = [{
          from: 'them',
          text: PT() ? 'Bem-vindo(a) ao RPCSV! Escolha um contato para conversar.' : 'Welcome to RPCSV! Pick a contact to chat.',
          ts: Date.now(),
        }];
        await window.rpcsv.setConfig({ messages: threads });
      }
      const contacts = [{ name: 'PlayStation', system: true }].concat((cfg.friends || []).map((f) => ({ name: f.name })));
      $('msg-contacts').innerHTML = '';
      contacts.forEach((c) => {
        const el = document.createElement('div');
        el.className = 'msg-contact' + (Messages.selected === c.name ? ' on' : '');
        el.innerHTML = `<span class="fr-ava">${avatarSvg(c.system ? 7 : (c.name.length % AVATARS.length), 40)}</span><span class="fr-txt"><strong>${esc(c.name)}</strong><span class="fr-sub">${c.system ? (PT() ? 'Mensagens do sistema' : 'System messages') : ''}</span></span>`;
        if (!c.system) el.addEventListener('click', () => Messages.openThread(c.name));
        $('msg-contacts').appendChild(el);
      });
      const tv = $('msg-thread');
      const sel = Messages.selected;
      if (!sel) {
        tv.innerHTML = '';
        tv.appendChild(emptyBox(PT() ? 'Escolha um contato à esquerda.' : 'Pick a contact on the left.'));
      } else {
        tv.innerHTML = '';
        (threads[sel] || []).forEach((m) => {
          const d = document.createElement('div');
          d.className = 'msg-bubble' + (m.from === 'me' ? ' me' : '');
          d.textContent = m.text;
          tv.appendChild(d);
        });
        tv.scrollTop = tv.scrollHeight;
      }
      $('msg-send-wrap').classList.toggle('hidden', !sel);
    },
    async openThread(name) {
      Messages.selected = name;
      await Messages.render();
    },
  };

  async function sendMsg() {
    const sel = Messages.selected;
    if (!sel) return;
    const txt = $('msg-input').value.trim();
    if (!txt) return;
    $('msg-input').value = '';
    const cfg = await window.rpcsv.getConfig();
    const threads = cfg.messages || {};
    const t = (threads[sel] || []).slice();
    t.push({ from: 'me', text: txt, ts: Date.now() });
    threads[sel] = t;
    await window.rpcsv.setConfig({ messages: threads });
    Messages.render();
  }

  // ------------------------------------------------------------------Camera
  const Camera = {
    async render() {
      const shots = await scan('photos');
      const latest = shots.files[shots.files.length - 1] || null;
      $('cam-info').textContent = window.rpcsv.platform === 'android'
        ? (PT() ? 'Toque em capturar para tirar uma foto.' : 'Tap capture to take a photo.')
        : (PT() ? 'Câmera disponível apenas no dispositivo móvel.' : 'Camera only available on the mobile device.');
      const th = $('cam-last');
      th.innerHTML = '';
      if (latest) {
        const img = document.createElement('img');
        img.src = fileUrl(latest.path);
        img.alt = latest.name;
        th.appendChild(img);
      } else {
        th.appendChild(emptyBox(PT() ? 'Nenhuma foto ainda.' : 'No photo yet.'));
      }
    },
  };

  async function capture() {
    if (window.rpcsv.platform !== 'android') {
      toast(PT() ? 'Câmera só funciona no aparelho.' : 'Camera works only on the device.');
      return;
    }
    toast(PT() ? 'Capturando...' : 'Capturing...');
    const res = await window.rpcsv.cameraTake();
    if (res && String(res).startsWith('/')) {
      const dir = await mediaBase('photos');
      const name = String(res).split(/[\\/]/).pop();
      await window.rpcsv.copyFile(res, dir + '/' + name);
      toast('📷 ' + (PT() ? 'Foto salva em Fotos' : 'Saved to Photos'));
      Camera.render();
      Photos.render();
    } else {
      toast(PT() ? 'Captura cancelada.' : 'Capture cancelled.', true);
    }
  }

  // ------------------------------------------------------------------- Maps
  const Maps = {
    PLACES: [
      { n: 'PlayStation Store', c: 'Tóquio, Japão' },
      { n: 'Café dos Amigos', c: 'Kyoto, Japão' },
      { n: 'Floresta da Aventura', c: 'Hokkaido, Japão' },
      { n: 'Praia de San Dimas', c: 'Califórnia, EUA' },
    ],
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const grid = $('map-grid');
      grid.innerHTML = '';
      const friends = (cfg.friends || []).slice(0, 6);
      friends.forEach((f, i) => {
        const el = document.createElement('div');
        el.className = 'map-marker';
        el.style.left = (16 + i * 13) + '%';
        el.style.top = (26 + (i % 2) * 26) + '%';
        el.innerHTML = avatarSvg(f.avatar, 36);
        el.title = f.name;
        el.addEventListener('click', () => toast(PT() ? `Perto de ${this.PLACES[i % 4].n}` : `Near ${this.PLACES[i % 4].n}`));
        grid.appendChild(el);
      });
      $('map-places').innerHTML = '';
      this.PLACES.forEach((p) => {
        const el = document.createElement('div');
        el.className = 'place-card';
        el.innerHTML = `<strong>${esc(p.n)}</strong><span>${esc(p.c)}</span>`;
        const nav = document.createElement('button');
        nav.className = 'btn btn-sm';
        nav.textContent = PT() ? 'Navegar' : 'Navigate';
        nav.addEventListener('click', () => window.rpcsv.openExternal('https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(p.n)));
        el.appendChild(nav);
        $('map-places').appendChild(el);
      });
    },
  };

  // ------------------------------------------------------------------ Store
  const Store = {
    ITEMS: [
      { name: 'Homebrew Essentials Pack', type: 'Conjunto', size: '48 MB', url: 'https://pkgj.dev/' },
      { name: 'Vita3K (emulador)', type: 'Sistema', size: '—', url: 'https://github.com/Vita3K/Vita3K/releases' },
      { name: 'Bibliotecas OpenGL (compat)', type: 'Lib', size: '12 MB', url: 'https://pkgj.dev/' },
      { name: 'Adrenaline (PSP)', type: 'Shell', size: '1.5 MB', url: 'https://pkgj.dev/' },
      { name: 'ZeroVSH (launcher)', type: 'App', size: '2 MB', url: 'https://pkgj.dev/' },
    ],
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const favs = cfg.storeFavs || [];
      const list = $('store-list');
      list.innerHTML = '';
      this.ITEMS.forEach((it) => {
        const on = favs.includes(it.name);
        const row = document.createElement('div');
        row.className = 'store-row';
        row.innerHTML = `<span class="sr-name"><strong>${esc(it.name)}</strong><span class="sr-sub">${esc(it.type)} · ${esc(it.size)}</span></span>
          <button class="btn btn-sm sr-fav">${on ? '♥' : '♡'}</button>
          <button class="btn btn-sm sr-go">${PT() ? 'Baixar' : 'Get'}</button>`;
        row.querySelector('.sr-fav').addEventListener('click', () => storeFav(it.name, !on));
        row.querySelector('.sr-go').addEventListener('click', () => {
          toast(PT() ? `Baixando ${it.name}...` : `Getting ${it.name}...`);
          window.rpcsv.openExternal(it.url);
        });
        list.appendChild(row);
      });
    },
  };

  async function storeFav(name, on) {
    const cfg = await window.rpcsv.getConfig();
    let favs = (cfg.storeFavs || []).slice();
    if (on && !favs.includes(name)) favs.push(name);
    else favs = favs.filter((x) => x !== name);
    await window.rpcsv.setConfig({ storeFavs: favs });
    Store.render();
  }

  // ----------------------------------------------------------------Calendar
  const Calendar = {
    view: new Date(),
    key(d) {
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, '0');
      const day = String(d.getDate()).padStart(2, '0');
      return y + '-' + m + '-' + day;
    },
    async render() {
      const cfg = await window.rpcsv.getConfig();
      const evs = cfg.calendar || {};
      const v = this.view;
      $('cal-label').textContent = v.toLocaleDateString(currentLangCode || 'pt-BR', { month: 'long', year: 'numeric' });
      const grid = $('cal-grid');
      grid.innerHTML = '';
      const first = new Date(v.getFullYear(), v.getMonth(), 1);
      const start = new Date(first);
      start.setDate(first.getDate() - first.getDay());
      const today = new Date();
      const todayKey = this.key(today);
      const weekday = document.createElement('div');
      weekday.className = 'cal-week';
      const letters = [0, 1, 2, 3, 4, 5, 6].map((d) => new Date(today.getFullYear(), today.getMonth(), today.getDate() - today.getDay() + d)
        .toLocaleDateString(currentLangCode || 'pt-BR', { weekday: 'short' }));
      letters.forEach((l) => {
        const s = document.createElement('span');
        s.textContent = l;
        weekday.appendChild(s);
      });
      grid.appendChild(weekday);
      for (let i = 0; i < 42; i++) {
        const d = new Date(start);
        d.setDate(start.getDate() + i);
        const key = this.key(d);
        const cell = document.createElement('button');
        cell.className = 'cal-cell' + (d.getMonth() === v.getMonth() ? '' : ' other')
          + (key === todayKey ? ' today' : '');
        const has = (evs[key] || []).length;
        cell.innerHTML = `<span class="cal-n">${d.getDate()}</span>${has ? '<span class="cal-dot"></span>' : ''}${has ? `<span class="cal-e">${evs[key].length}</span>` : ''}`;
        cell.addEventListener('click', () => calDay(key, evs[key] || []));
        grid.appendChild(cell);
      }
      $('cal-today').textContent = today.toLocaleDateString(currentLangCode || 'pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });
    },
  };

  async function calDay(key, list) {
    const cfg = await window.rpcsv.getConfig();
    if (list.length) {
      toast(list.join(' · '));
      const more = await promptModal(PT() ? 'Novo evento para ' + key : 'New event for ' + key, '');
      if (!more) return;
      list.push(more);
    } else {
      const label = await promptModal(PT() ? 'Adicionar evento (' + key + ')' : 'Add event (' + key + ')', '');
      if (!label) return;
      list = [label];
    }
    const evs = cfg.calendar || {};
    evs[key] = list;
    await window.rpcsv.setConfig({ calendar: evs });
    Calendar.render();
  }

  // ------------------------------------------------------------------- init
  function init() {
    $('browser-back').addEventListener('click', () => showScreen('home'));
    $('music-back').addEventListener('click', () => showScreen('home'));
    $('photos-back').addEventListener('click', () => showScreen('home'));
    $('videos-back').addEventListener('click', () => showScreen('home'));
    $('trophies-back').addEventListener('click', () => showScreen('home'));
    $('friends-back').addEventListener('click', () => showScreen('home'));
    $('party-back').addEventListener('click', () => showScreen('home'));
    $('messages-back').addEventListener('click', () => showScreen('home'));
    $('camera-back').addEventListener('click', () => showScreen('home'));
    $('maps-back').addEventListener('click', () => showScreen('home'));
    $('store-back').addEventListener('click', () => showScreen('home'));
    $('calendar-back').addEventListener('click', () => showScreen('home'));

    $('browser-open').addEventListener('click', () => Browser.open());
    $('browser-url').addEventListener('keydown', (e) => { if (e.key === 'Enter') Browser.open(); });
    $('music-add').addEventListener('click', () => importMedia('music'));
    $('photo-add').addEventListener('click', () => importMedia('photos'));
    $('video-add').addEventListener('click', () => importMedia('videos'));
    $('friends-add').addEventListener('click', () => addFriend());
    $('cam-shoot').addEventListener('click', () => capture());
    $('msg-send').addEventListener('click', () => sendMsg());
    $('msg-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') sendMsg(); });
    $('cal-prev').addEventListener('click', () => { Calendar.view.setMonth(Calendar.view.getMonth() - 1); Calendar.render(); });
    $('cal-next').addEventListener('click', () => { Calendar.view.setMonth(Calendar.view.getMonth() + 1); Calendar.render(); });

    $('np-play').addEventListener('click', () => Music.toggle());
    $('np-prev').addEventListener('click', () => Music.step(-1));
    $('np-next').addEventListener('click', () => Music.step(1));
    $('np-seek').addEventListener('input', (e) => {
      const a = audioEl();
      if (a.duration && isFinite(a.duration)) a.currentTime = (a.duration * Number(e.target.value)) / 100;
    });

    const a = audioEl();
    a.addEventListener('timeupdate', () => Music.updateNP());
    a.addEventListener('loadedmetadata', () => Music.updateNP());
    a.addEventListener('ended', () => {
      if (Music.current < Music.tracks.length - 1) Music.play(Music.current + 1);
      else {
        Music.playing = false;
        Music.updateNP();
      }
    });

    ovEl().addEventListener('click', (e) => {
      if (e.target === ovEl()) closeOv();
    });
  }

  return { init, open, mail: () => window.rpcsv.openExternal('mailto:'), Browser, Music, Photos, Videos, Trophies, Friends, Party, Messages, Camera, Maps, Store, Calendar };
})();