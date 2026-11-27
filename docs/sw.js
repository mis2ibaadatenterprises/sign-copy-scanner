const CORE = 'sign-core-v18', RT = 'sign-rt-v1';
const SHELL = ['./', './index.html', './manifest.json', './station.js', './version.txt', './icon-180.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CORE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks =>
    Promise.all(ks.filter(k => k !== CORE && k !== RT).map(k => caches.delete(k)))
  ).then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const r = e.request;
  if (r.method !== 'GET') return;                       // uploads always hit the network
  const u = new URL(r.url);

  // OCR engine and language data: cache first, they never change.
  if (/jsdelivr\.net|projectnaptha\.com/.test(u.hostname)) {
    e.respondWith(caches.open(RT).then(async c => {
      const hit = await c.match(r);
      if (hit) return hit;
      const res = await fetch(r);
      if (res && res.status === 200) c.put(r, res.clone());
      return res;
    }));
    return;
  }

  // The app itself: network first, so a fix reaches the phone on the next
  // open. Cache is the fallback for a counter with no signal.
  e.respondWith(
    fetch(new Request(r, { cache: 'reload' })).then(res => {
      const copy = res.clone();
      caches.open(CORE).then(c => c.put(r, copy));
      return res;
    }).catch(() => caches.match(r).then(hit => hit || caches.match('./index.html')))
  );
});
