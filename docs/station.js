/* Tripod station mode.
 *
 * The phone sits on a tripod, the bill goes in the same place every time, and
 * a Bluetooth remote fires the shutter. No confirm step: capture, rectify,
 * read, upload, beep. The operator just keeps feeding paper.
 *
 * Because the camera never moves, the page corners are marked once, during
 * calibration, and every frame gets the same perspective correction. That is
 * both faster and more reliable than detecting page edges in each shot.
 */

const ST = {
  get on(){ return localStorage.getItem('st_on') === '1' },
  set on(v){ localStorage.setItem('st_on', v ? '1' : '0') },
  /* page corners as fractions of the frame: TL, TR, BR, BL */
  get quad(){ try { return JSON.parse(localStorage.getItem('st_quad') || 'null') } catch(e){ return null } },
  set quad(v){ localStorage.setItem('st_quad', JSON.stringify(v)) },
  get beep(){ return localStorage.getItem('st_beep') !== '0' },
  set beep(v){ localStorage.setItem('st_beep', v ? '1' : '0') },
  /* skip the confirm screen: capture goes straight to the board */
  /* Default ON. Confirming every bill was never the point; the operator
     wants click, beep, next. Switch it off to get the check screen back. */
  /* Trim the desk away and straighten the page. Off unless asked for:
     on a white desk the page cannot be told from the table. */
  get trim(){ return localStorage.getItem('st_trim') === '1' },
  set trim(v){ localStorage.setItem('st_trim', v ? '1' : '0') },
  /* Hard black-and-white "scanned" look. Off by default — on a shadowed
     photo it crushes the page into blotches. */
  get bw(){ return localStorage.getItem('st_bw') === '1' },
  set bw(v){ localStorage.setItem('st_bw', v ? '1' : '0') },
  get auto(){ return localStorage.getItem('st_auto') !== '0' },
  set auto(v){ localStorage.setItem('st_auto', v ? '1' : '0') }
};

/* Straight-to-board upload, used whenever the confirm step is switched off.
 * Nothing is thrown away on a bad read: the bill still reaches the board as
 * NO-INV, where the bill number can be set by hand. */
async function autoSend(r){
  if(!current) return;
  const nm = (x, fb) => { const c = String(x||'').trim().replace(/[\/\\:*?"<>|]+/g,'-')
                            .replace(/\s+/g,'-').replace(/-+/g,'-').replace(/^-|-$/g,'');
                          return c || fb; };
  const job = {
    filename: nm(r.inv,'NO-INV') + '_' + nm(r.party,'NO-PARTY') + '_' + nm(r.date,'NO-DATE') + '.pdf',
    invoice: String(r.inv||'').trim(), party: String(r.party||'').trim(),
    date: String(r.date||'').trim(), day: today(),
    pdf: toPDF(current.jpeg, current.w, current.h)
  };
  toast('Uploading ' + (job.invoice || 'bill') + '…');
  try{
    if(!navigator.onLine) throw new Error('offline');
    await send(job);
    beep(true);
    toast(job.invoice ? 'On the board — ' + job.invoice : 'On the board, but no bill number was read', 'good');
  }catch(e){
    if(e.rejected){ beep(false); toast('Rejected: ' + e.message, 'bad'); }
    else { await qAdd(job); await paint(); beep(true); toast('No signal — saved, will upload itself','bad'); }
  }
  current = null;
  show('scHome');
}

/* ---------- geometry ---------- */

/* Homography mapping the four source points onto a w x h rectangle. */
function homography(src, w, h){
  const dst = [[0,0],[w,0],[w,h],[0,h]];
  const A = [], b = [];
  for(let i=0;i<4;i++){
    const [x,y] = src[i], [u,v] = dst[i];
    A.push([x,y,1,0,0,0,-u*x,-u*y]); b.push(u);
    A.push([0,0,0,x,y,1,-v*x,-v*y]); b.push(v);
  }
  // Gaussian elimination with partial pivoting
  for(let c=0;c<8;c++){
    let p = c;
    for(let r=c+1;r<8;r++) if(Math.abs(A[r][c]) > Math.abs(A[p][c])) p = r;
    [A[c],A[p]] = [A[p],A[c]]; [b[c],b[p]] = [b[p],b[c]];
    const d = A[c][c] || 1e-9;
    for(let r=0;r<8;r++){
      if(r === c) continue;
      const f = A[r][c] / d;
      for(let k=c;k<8;k++) A[r][k] -= f * A[c][k];
      b[r] -= f * b[c];
    }
  }
  const m = b.map((v,i) => v / (A[i][i] || 1e-9));
  return [[m[0],m[1],m[2]],[m[3],m[4],m[5]],[m[6],m[7],1]];
}

function invert3(M){
  const [a,b,c] = M[0], [d,e,f] = M[1], [g,h,i] = M[2];
  const det = a*(e*i-f*h) - b*(d*i-f*g) + c*(d*h-e*g) || 1e-9;
  return [
    [(e*i-f*h)/det, (c*h-b*i)/det, (b*f-c*e)/det],
    [(f*g-d*i)/det, (a*i-c*g)/det, (c*d-a*f)/det],
    [(d*h-e*g)/det, (b*g-a*h)/det, (a*e-b*d)/det]
  ];
}

/* Straightens the page into a flat rectangle, sampling bilinearly. */
function rectify(srcCanvas, quadFrac, outW, outH){
  const sw = srcCanvas.width, sh = srcCanvas.height;
  const pts = quadFrac.map(([fx,fy]) => [fx*sw, fy*sh]);
  const Hi = invert3(homography(pts, outW, outH));

  const sctx = srcCanvas.getContext('2d');
  const sdata = sctx.getImageData(0,0,sw,sh).data;
  const out = document.createElement('canvas');
  out.width = outW; out.height = outH;
  const octx = out.getContext('2d');
  const odata = octx.createImageData(outW, outH);
  const o = odata.data;

  for(let y=0; y<outH; y++){
    for(let x=0; x<outW; x++){
      const d = Hi[2][0]*x + Hi[2][1]*y + Hi[2][2];
      const sx = (Hi[0][0]*x + Hi[0][1]*y + Hi[0][2]) / d;
      const sy = (Hi[1][0]*x + Hi[1][1]*y + Hi[1][2]) / d;
      const i = (y*outW + x) * 4;
      if(sx < 0 || sy < 0 || sx >= sw-1 || sy >= sh-1){ o[i]=o[i+1]=o[i+2]=255; o[i+3]=255; continue; }
      const x0 = sx|0, y0 = sy|0, fx = sx-x0, fy = sy-y0;
      const p00 = (y0*sw + x0)*4, p10 = p00+4, p01 = p00+sw*4, p11 = p01+4;
      for(let k=0;k<3;k++){
        o[i+k] = (sdata[p00+k]*(1-fx)*(1-fy) + sdata[p10+k]*fx*(1-fy) +
                  sdata[p01+k]*(1-fx)*fy     + sdata[p11+k]*fx*fy) | 0;
      }
      o[i+3] = 255;
    }
  }
  octx.putImageData(odata, 0, 0);
  return out;
}

/* ---------- document look ---------- */

/* Local-mean threshold over an integral image: the "scanned" black on white,
 * immune to the uneven lighting a desk lamp throws across a page. */
function scanLook(canvas){
  const w = canvas.width, h = canvas.height;
  const ctx = canvas.getContext('2d');
  const img = ctx.getImageData(0,0,w,h), p = img.data;

  const grey = new Float64Array(w*h);
  for(let i=0,j=0; i<p.length; i+=4, j++)
    grey[j] = p[i]*0.299 + p[i+1]*0.587 + p[i+2]*0.114;

  const sum = new Float64Array((w+1)*(h+1));
  for(let y=0; y<h; y++){
    let row = 0;
    for(let x=0; x<w; x++){
      row += grey[y*w+x];
      sum[(y+1)*(w+1) + (x+1)] = sum[y*(w+1) + (x+1)] + row;
    }
  }
  const r = Math.max(8, Math.round(w/24));      // window radius
  const C = 10;                                  // how far below local mean counts as ink
  for(let y=0; y<h; y++){
    const y0 = Math.max(0,y-r), y1 = Math.min(h-1,y+r);
    for(let x=0; x<w; x++){
      const x0 = Math.max(0,x-r), x1 = Math.min(w-1,x+r);
      const area = (x1-x0+1)*(y1-y0+1);
      const s = sum[(y1+1)*(w+1)+(x1+1)] - sum[y0*(w+1)+(x1+1)]
              - sum[(y1+1)*(w+1)+x0]     + sum[y0*(w+1)+x0];
      const mean = s / area;
      const i = (y*w+x)*4;
      const v = grey[y*w+x] < mean - C ? 0 : 255;
      p[i] = p[i+1] = p[i+2] = v;
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvas;
}

/* ---------- feedback ---------- */

let actx = null;
function beep(ok){
  if(!ST.beep) return;
  try{
    actx = actx || new (window.AudioContext || window.webkitAudioContext)();
    const o = actx.createOscillator(), g = actx.createGain();
    o.connect(g); g.connect(actx.destination);
    o.frequency.value = ok ? 880 : 220;
    g.gain.setValueAtTime(0.0001, actx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.25, actx.currentTime + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, actx.currentTime + (ok ? 0.14 : 0.4));
    o.start(); o.stop(actx.currentTime + (ok ? 0.15 : 0.42));
  }catch(e){}
}

/* ---------- reading a rectified page ---------- */

/* The page is flat and always framed the same way, so the fields sit in
 * known bands. Falls back to the handheld crops if a band comes up empty. */
async function stationRead(page){
  const passes = [];
  const read = async c => { const { data } = await worker.recognize(c); passes.push(data.text || ''); return parse(data.text || ''); };

  await worker.setParameters({
    tessedit_char_whitelist: 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/-.: ',
    tessedit_pageseg_mode: '6'
  });
  let out = { inv:'', party:'', date:'', cI:0, cP:0, cD:0 };
  for(const r of [[.58,0,.42,.18,2],[.52,0,.48,.24,2],[.55,0,.45,.22,3]]){
    out = merge(out, await read(crop(page, r[0], r[1], r[2], r[3], r[4])));
    if(out.inv) break;
  }
  await worker.setParameters({ tessedit_char_whitelist: '', tessedit_pageseg_mode: '3' });

  for(const r of [[.08,.14,.34,.14,2],[.05,.10,.42,.22,2]]){
    out = merge(out, await read(crop(page, r[0], r[1], r[2], r[3], r[4])));
    if(out.party) break;
  }
  if(!out.inv || !out.party) out = merge(out, await read(page));
  stationRead.lastRaw = passes.join('\n---\n');
  return out;
}

/* ---------- the shot ---------- */

let stBusy = false, stLast = [];

function stationLog(entry){
  stLast.unshift(entry);
  stLast = stLast.slice(0, 8);
  const el = document.getElementById('stList');
  if(!el) return;
  el.innerHTML = stLast.map(e =>
    '<div class="q"><div><b>' + (e.inv || '— no number —') + '</b><small>' + (e.party || '—') + '</small></div>' +
    '<span style="color:' + (e.state === 'ok' ? 'var(--ok)' : e.state === 'queued' ? 'var(--wait)' : 'var(--warn)') +
    '">' + e.state + '</span></div>').join('');
}

async function stationShot(){
  if(stBusy) return;
  const quad = ST.quad;
  if(!quad){ toast('Calibrate the page corners first','bad'); return; }
  const v = document.getElementById('cam');
  if(!v || !v.videoWidth){ toast('Camera not ready','bad'); return; }

  stBusy = true;
  const st = document.getElementById('stState');
  if(st) st.textContent = 'Capturing…';
  try{
    const frame = document.createElement('canvas');
    frame.width = v.videoWidth; frame.height = v.videoHeight;
    frame.getContext('2d').drawImage(v, 0, 0);

    // output keeps the page's own shape, at roughly 150 dpi
    const w = frame.width, h = frame.height;
    const d = (a,b) => Math.hypot((quad[a][0]-quad[b][0])*w, (quad[a][1]-quad[b][1])*h);
    const pw = (d(0,1) + d(3,2)) / 2, ph = (d(0,3) + d(1,2)) / 2;
    const outW = 1700, outH = Math.max(600, Math.round(outW * ph / pw));

    if(st) st.textContent = 'Straightening…';
    const flat = rectify(frame, quad, outW, outH);
    const page = ST.bw ? scanLook(flat) : flat;

    if(!worker){
      if(st) st.textContent = 'Loading the text engine…';
      worker = await Tesseract.createWorker('eng', 1);
    }
    if(st) st.textContent = 'Reading…';
    const r = await stationRead(page);

    const job = {
      filename: (r.inv ? r.inv.replace(/[\/\\:*?"<>|]+/g,'-') : 'NO-INV') + '_' +
                (r.party ? r.party.replace(/\s+/g,'-') : 'NO-PARTY') + '_' +
                (r.date || 'NO-DATE') + '.pdf',
      invoice: r.inv, party: r.party, date: r.date, day: today(),
      pdf: toPDF(page.toDataURL('image/jpeg', 0.75), page.width, page.height)
    };

    if(st) st.textContent = 'Uploading…';
    try{
      if(!navigator.onLine) throw new Error('offline');
      await send(job);
      stationLog({ inv:r.inv, party:r.party, state:'ok' });
      beep(true);
    }catch(e){
      if(e.rejected){ stationLog({ inv:r.inv, party:r.party, state:e.message }); beep(false); }
      else { await qAdd(job); await paint(); stationLog({ inv:r.inv, party:r.party, state:'queued' }); beep(true); }
    }
    if(st) st.textContent = r.inv ? 'Ready — last: ' + r.inv : 'Ready — last one had no number';
  }catch(e){
    stationLog({ inv:'', party:'', state:'failed' }); beep(false);
    if(st) st.textContent = 'Ready';
  }
  setTimeout(()=>{ stBusy = false; }, 600);
}

/* ---------- the remote ---------- */

/* Bluetooth shutters appear as keyboards. In their Android mode most send
 * Enter, Space or a volume key; a browser never receives volume keys, so if
 * nothing fires here, the remote is volume-only and the station has to run
 * on the Android build instead. */
const REMOTE_KEYS = ['Enter',' ','Spacebar','ArrowUp','ArrowDown','PageUp','PageDown',
                     'MediaPlayPause','AudioVolumeUp','AudioVolumeDown'];
addEventListener('keydown', e => {
  if(!ST.on) return;
  const t = e.target;
  if(t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  if(REMOTE_KEYS.indexOf(e.key) < 0) return;
  e.preventDefault();
  const seen = document.getElementById('stKey');
  if(seen) seen.textContent = 'remote key seen: ' + (e.key === ' ' ? 'Space' : e.key);
  stationShot();
});

/* ---------- calibration ---------- */

let calPts = null, calFrame = null, calDrag = -1;

function calDefault(){ return [[.08,.10],[.92,.10],[.92,.90],[.08,.90]]; }

function openCal(){
  const v = document.getElementById('cam');
  if(!v || !v.videoWidth){ toast('Camera not ready','bad'); return; }
  calFrame = document.createElement('canvas');
  calFrame.width = v.videoWidth; calFrame.height = v.videoHeight;
  calFrame.getContext('2d').drawImage(v, 0, 0);
  calPts = (ST.quad || calDefault()).map(p => p.slice());
  show('scCal');
  drawCal();
}

function drawCal(){
  const cv = document.getElementById('calCv');
  const wrap = cv.parentElement.getBoundingClientRect();
  const maxW = Math.min(wrap.width - 2, 900);
  cv.width = maxW;
  cv.height = Math.round(maxW * calFrame.height / calFrame.width);
  const x = cv.getContext('2d');
  x.drawImage(calFrame, 0, 0, cv.width, cv.height);

  const pts = calPts.map(([fx,fy]) => [fx*cv.width, fy*cv.height]);
  x.strokeStyle = '#c9a24a'; x.lineWidth = 3;
  x.beginPath(); x.moveTo(pts[0][0], pts[0][1]);
  pts.slice(1).forEach(p => x.lineTo(p[0], p[1]));
  x.closePath(); x.stroke();
  x.fillStyle = 'rgba(20,37,74,.25)'; x.fill();
  pts.forEach(([px,py], i) => {
    x.beginPath(); x.arc(px, py, 16, 0, 7); x.fillStyle = '#14254a'; x.fill();
    x.lineWidth = 3; x.strokeStyle = '#fff'; x.stroke();
    x.fillStyle = '#fff'; x.font = 'bold 13px sans-serif'; x.textAlign = 'center';
    x.fillText(String(i+1), px, py+5);
  });
}

function calPick(ev){
  const cv = document.getElementById('calCv'), r = cv.getBoundingClientRect();
  const px = (ev.clientX - r.left) / r.width, py = (ev.clientY - r.top) / r.height;
  return [px, py];
}

function calInit(){
  const cv = document.getElementById('calCv');
  if(!cv || cv.dataset.wired) return;
  cv.dataset.wired = '1';
  cv.style.touchAction = 'none';
  cv.addEventListener('pointerdown', e => {
    const [px,py] = calPick(e);
    let best = -1, bd = 0.06;
    calPts.forEach((p,i) => { const d = Math.hypot(p[0]-px, p[1]-py); if(d < bd){ bd = d; best = i; } });
    calDrag = best;
    if(best >= 0) cv.setPointerCapture(e.pointerId);
  });
  cv.addEventListener('pointermove', e => {
    if(calDrag < 0) return;
    const [px,py] = calPick(e);
    calPts[calDrag] = [Math.min(1,Math.max(0,px)), Math.min(1,Math.max(0,py))];
    drawCal();
  });
  cv.addEventListener('pointerup', () => { calDrag = -1; });
  cv.addEventListener('pointercancel', () => { calDrag = -1; });
}

/* ---------- wiring ---------- */

function stationPaint(){
  const panel = document.getElementById('stPanel');
  const normal = document.getElementById('stNormal');
  if(panel) panel.style.display = ST.on ? '' : 'none';
  if(normal) normal.style.display = ST.on ? 'none' : '';
  const s = document.getElementById('stStatus');
  if(s) s.textContent = ST.on
    ? (ST.quad ? 'On. Page corners are set.' : 'On, but the page corners are not set yet.')
    : 'Off. The phone is used by hand.';
}

addEventListener('DOMContentLoaded', () => {
  calInit();
  stationPaint();

  const tr = document.getElementById('stTrim');
  if(tr){ tr.checked = ST.trim; tr.onchange = () => { ST.trim = tr.checked; }; }
  const bw = document.getElementById('stBw');
  if(bw){ bw.checked = ST.bw; bw.onchange = () => { ST.bw = bw.checked; }; }
  const au = document.getElementById('stAuto');
  if(au){ au.checked = ST.auto; au.onchange = () => { ST.auto = au.checked; }; }
  const on = document.getElementById('stOn'), bp = document.getElementById('stBeep');
  if(on){ on.checked = ST.on; on.onchange = () => { ST.on = on.checked; stationPaint(); }; }
  if(bp){ bp.checked = ST.beep; bp.onchange = () => { ST.beep = bp.checked; }; }

  const cal = document.getElementById('stCal');
  if(cal) cal.onclick = () => { show('scHome'); setTimeout(openCal, 150); };

  const save = document.getElementById('calSave');
  if(save) save.onclick = () => {
    ST.quad = calPts; ST.on = true;
    const o = document.getElementById('stOn'); if(o) o.checked = true;
    stationPaint(); toast('Page corners saved','good'); show('scHome');
  };
  const cancel = document.getElementById('calCancel');
  if(cancel) cancel.onclick = () => show('scHome');
  const reset = document.getElementById('calReset');
  if(reset) reset.onclick = () => { calPts = calDefault(); drawCal(); };

  const shoot = document.getElementById('stShoot');
  if(shoot) shoot.onclick = stationShot;
});

/* ---------- finding the page in a photo ----------
 *
 * Everything outside the paper is noise: desk, hands, shadow. With a tripod
 * the corners are calibrated once; handheld they have to be found per shot.
 *
 * The bill is a bright blob on a darker background, so: shrink, split light
 * from dark at the Otsu point, flood the bright region outward from the
 * middle of the frame, then take its four extreme corners. Those are the
 * corners of a sheet of paper even when it sits at an angle.
 */
function detectPage(src){
  const W = 400, sc = W / src.width, H = Math.max(1, Math.round(src.height * sc));
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  c.getContext('2d').drawImage(src, 0, 0, W, H);
  const d = c.getContext('2d').getImageData(0,0,W,H).data;

  const g = new Uint8Array(W*H), hist = new Uint32Array(256);
  for(let i=0,j=0; j<W*H; i+=4, j++){
    g[j] = (d[i]*0.299 + d[i+1]*0.587 + d[i+2]*0.114) | 0;
    hist[g[j]]++;
  }

  // Otsu: the split that best separates paper from background
  const total = W*H;
  let sum = 0; for(let i=0;i<256;i++) sum += i*hist[i];
  let sumB = 0, wB = 0, best = 0, thr = 128;
  for(let t=0;t<256;t++){
    wB += hist[t]; if(!wB) continue;
    const wF = total - wB; if(!wF) break;
    sumB += t*hist[t];
    const mB = sumB/wB, mF = (sum-sumB)/wF, v = wB*wF*(mB-mF)*(mB-mF);
    if(v > best){ best = v; thr = t; }
  }

  // flood the bright region from the middle of the frame
  const seen = new Uint8Array(W*H);
  const stack = [];
  for(let y = (H*0.35)|0; y < (H*0.65|0); y++)
    for(let x = (W*0.35)|0; x < (W*0.65|0); x++)
      if(g[y*W+x] > thr){ stack.push(y*W+x); seen[y*W+x] = 1; break; }
  if(!stack.length) return null;

  let n = 0;
  let tl=[1e9,0], br=[-1e9,0], tr=[-1e9,0], bl=[1e9,0];
  const push = (i) => { if(i>=0 && i<W*H && !seen[i] && g[i] > thr){ seen[i]=1; stack.push(i); } };
  while(stack.length){
    const i = stack.pop(), x = i % W, y = (i / W) | 0;
    n++;
    if(x+y < tl[0]) tl = [x+y, x, y];
    if(x+y > br[0]) br = [x+y, x, y];
    if(x-y > tr[0]) tr = [x-y, x, y];
    if(x-y < bl[0]) bl = [x-y, x, y];
    if(x > 0)   push(i-1);
    if(x < W-1) push(i+1);
    if(y > 0)   push(i-W);
    if(y < H-1) push(i+W);
  }

  if(n < total * 0.25) return null;                 // too small to be the page
  const quad = [tl, tr, br, bl].map(p => [p[1]/W, p[2]/H]);

  // a page is convex and roughly rectangular; reject anything odd
  const side = (a,b) => Math.hypot((quad[a][0]-quad[b][0]), (quad[a][1]-quad[b][1]));
  const top = side(0,1), bot = side(3,2), left = side(0,3), right = side(1,2);
  if(Math.min(top,bot) < 0.35 || Math.min(left,right) < 0.20) return null;
  if(Math.max(top,bot) / Math.min(top,bot) > 1.6) return null;
  if(Math.max(left,right) / Math.min(left,right) > 1.6) return null;

  // breathe a little so no ink sits on the cut line
  const cx = (quad[0][0]+quad[1][0]+quad[2][0]+quad[3][0])/4;
  const cy = (quad[0][1]+quad[1][1]+quad[2][1]+quad[3][1])/4;
  return quad.map(([x,y]) => [
    Math.min(1, Math.max(0, cx + (x-cx)*1.012)),
    Math.min(1, Math.max(0, cy + (y-cy)*1.012))
  ]);
}

/* Returns a flat, trimmed, scanned-looking page — or null to keep the photo
 * as it is, when no page could be found with confidence. */
function pageFrom(canvas, quad){
  const q = quad || (ST.trim ? detectPage(canvas) : null);
  if(!q) return null;
  const w = canvas.width, h = canvas.height;
  const d = (a,b) => Math.hypot((q[a][0]-q[b][0])*w, (q[a][1]-q[b][1])*h);
  const pw = (d(0,1) + d(3,2)) / 2, ph = (d(0,3) + d(1,2)) / 2;
  const outW = 1700, outH = Math.max(500, Math.round(outW * ph / pw));
  const flat = rectify(canvas, q, outW, outH);
  return ST.bw ? scanLook(flat) : flat;
}
