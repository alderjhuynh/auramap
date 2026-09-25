(function(){
  "use strict";

  var MAGIC = 0x4155524D;
  var MIN_ZOOM = 0.02, MAX_ZOOM = 16;
  var WP_COLORS = {
    BLACK:"#000000", DARK_BLUE:"#0000AA", DARK_GREEN:"#00AA00", DARK_AQUA:"#00AAAA",
    DARK_RED:"#AA0000", DARK_PURPLE:"#AA00AA", GOLD:"#FFAA00", GRAY:"#AAAAAA",
    DARK_GRAY:"#555555", BLUE:"#5555FF", GREEN:"#55FF55", AQUA:"#55FFFF",
    RED:"#FF5555", LIGHT_PURPLE:"#FF55FF", YELLOW:"#FFFF55", WHITE:"#FFFFFF"
  };

  var landing = document.getElementById('landing');
  var viewer = document.getElementById('viewer');
  var dropEl = document.getElementById('drop');
  var pickBtn = document.getElementById('pickBtn');
  var folderInput = document.getElementById('folderInput');
  var statusEl = document.getElementById('status');
  var groupsEl = document.getElementById('groups');
  var canvas = document.getElementById('mapCanvas');
  var ctx = canvas.getContext('2d');
  var tooltip = document.getElementById('tooltip');

  function setStatus(msg, cls){
    statusEl.textContent = msg || '';
    statusEl.className = cls || '';
  }

  function relPath(f){ return f.webkitRelativePath || f.__path || f.name; }

  function traverseEntry(entry, path, out){
    return new Promise(function(resolve){
      if (!entry) return resolve();
      if (entry.isFile){
        entry.file(function(file){ file.__path = path + entry.name; out.push(file); resolve(); }, resolve);
      } else if (entry.isDirectory){
        var reader = entry.createReader();
        var readAll = function(){
          reader.readEntries(function(entries){
            if (!entries.length) return resolve();
            var p = Promise.resolve();
            entries.forEach(function(e){ p = p.then(function(){ return traverseEntry(e, path + entry.name + '/', out); }); });
            p.then(readAll);
          }, resolve);
        };
        readAll();
      } else resolve();
    });
  }

  dropEl.addEventListener('dragover', function(e){ e.preventDefault(); dropEl.classList.add('drag'); });
  dropEl.addEventListener('dragleave', function(){ dropEl.classList.remove('drag'); });
  dropEl.addEventListener('drop', function(e){
    e.preventDefault(); dropEl.classList.remove('drag');
    var items = e.dataTransfer.items;
    if (items && items.length && items[0].webkitGetAsEntry){
      var entries = []; for (var i=0;i<items.length;i++){ var en = items[i].webkitGetAsEntry(); if (en) entries.push(en); }
      var out = [];
      var p = Promise.resolve();
      entries.forEach(function(en){ p = p.then(function(){ return traverseEntry(en, '', out); }); });
      p.then(function(){ handleFiles(out); });
    } else {
      handleFiles(Array.prototype.slice.call(e.dataTransfer.files));
    }
  });
  pickBtn.addEventListener('click', function(){ folderInput.click(); });
  folderInput.addEventListener('change', function(e){ handleFiles(Array.prototype.slice.call(e.target.files)); });

  var BIN_RE = /^r\.(-?\d+)\.(-?\d+)\.bin$/i;
  var WP_RE = /^waypoints\.json$/i;

  function handleFiles(files){
    if (!files.length){ setStatus('No files found in that selection.', 'err'); return; }
    var groups = {};
    files.forEach(function(f){
      var rp = relPath(f);
      var parts = rp.split('/');
      var base = parts[parts.length-1];
      var dir = parts.slice(0,-1).join('/') || '.';
      if (BIN_RE.test(base)){
        (groups[dir] = groups[dir] || {binFiles:[], waypointFile:null}).binFiles.push(f);
      } else if (WP_RE.test(base)){
        (groups[dir] = groups[dir] || {binFiles:[], waypointFile:null}).waypointFile = f;
      }
    });
    var keys = Object.keys(groups).filter(function(k){ return groups[k].binFiles.length > 0; });
    if (!keys.length){
      setStatus('No AuraMap region files (r.X.Z.bin) turned up in that folder. Make sure you selected the dimension folder itself, or a folder containing one.', 'err');
      groupsEl.style.display = 'none';
      return;
    }
    setStatus('');
    if (keys.length === 1){
      loadGroup(keys[0], groups[keys[0]]);
      return;
    }
    groupsEl.innerHTML = '';
    groupsEl.style.display = 'flex';
    keys.sort();
    keys.forEach(function(k){
      var segs = k.split('/');
      var label = segs.slice(-2).join(' / ');
      var row = document.createElement('div');
      row.className = 'group-row';
      row.innerHTML = '<div class="p"><b>'+escapeHtml(label)+'</b>'+escapeHtml(k)+'</div>' +
        '<div class="count mono">'+groups[k].binFiles.length+' region'+(groups[k].binFiles.length===1?'':'s')+'</div>';
      row.addEventListener('click', function(){ groupsEl.style.display='none'; loadGroup(k, groups[k]); });
      groupsEl.appendChild(row);
    });
    setStatus('Found '+keys.length+' map folders. Pick one to open.', '');
  }

  function escapeHtml(s){ var d=document.createElement('div'); d.textContent=s; return d.innerHTML; }


  function parseRegionFile(file){
    return file.arrayBuffer().then(function(buf){
      var dv = new DataView(buf);
      if (dv.byteLength < 9) throw new Error(file.name+': file too small');
      var magic = dv.getUint32(0, false);
      if (magic !== MAGIC) throw new Error(file.name+': not an AuraMap region file');
      dv.getUint8(4);
      var size = dv.getInt32(5, false);
      var expected = 9 + size*size*4;
      if (dv.byteLength < expected) throw new Error(file.name+': truncated');
      var m = file.name.match(BIN_RE);
      var rx = parseInt(m[1],10), rz = parseInt(m[2],10);

      var src = new Uint8Array(buf, 9, size*size*4);
      var imgData = new ImageData(size, size);
      var dst = imgData.data;
      var hasContent = false;
      for (var i=0, n=size*size; i<n; i++){
        var si = i*4, di = i*4;
        var a = src[si], r = src[si+1], g = src[si+2], b = src[si+3];
        dst[di] = r; dst[di+1] = g; dst[di+2] = b; dst[di+3] = a;
        if (r || g || b) hasContent = true;
      }
      var cnv = document.createElement('canvas');
      cnv.width = size; cnv.height = size;
      cnv.getContext('2d').putImageData(imgData, 0, 0);
      return {rx:rx, rz:rz, size:size, canvas:cnv, hasContent:hasContent};
    });
  }

  function parseWaypoints(file){
    if (!file) return Promise.resolve([]);
    return file.text().then(function(text){
      try{
        var data = JSON.parse(text);
        var out = [];
        (data.sets||[]).forEach(function(set){
          (set.waypoints||[]).forEach(function(w){
            if (w.disabled) return;
            out.push({name:w.name||'', initials:(w.initials||'?').slice(0,1).toUpperCase(),
              x:w.x, z:w.z, y:w.y, color:w.color||'WHITE', set:set.name||''});
          });
        });
        return out;
      } catch(e){ return []; }
    });
  }


  var tiles = new Map();
  var waypoints = [];
  var regionSize = 512;
  var showWaypoints = true;
  var cam = {x:0, z:0, zoom:1};
  var dpr = Math.max(1, window.devicePixelRatio || 1);
  var mouseCss = null;
  var hoveredWp = null;

  function loadGroup(dirKey, group){
    setStatus('Reading '+group.binFiles.length+' region file'+(group.binFiles.length===1?'':'s')+'…', '');
    var failures = [];
    Promise.all(group.binFiles.map(function(f){
      return parseRegionFile(f).catch(function(e){ failures.push(e.message); return null; });
    })).then(function(results){
      tiles.clear();
      results.forEach(function(t){
        if (!t) return;
        regionSize = t.size;
        tiles.set(t.rx+','+t.rz, t);
      });
      if (!tiles.size){
        setStatus('Every region file in that folder failed to parse. '+(failures[0]||''), 'err');
        return;
      }
      return parseWaypoints(group.waypointFile).then(function(wps){
        waypoints = wps;
        openViewer(dirKey, failures);
      });
    });
  }

  function openViewer(dirKey, failures){
    var segs = dirKey.split('/');
    document.getElementById('groupLabel').textContent = segs.slice(-2).join(' / ');
    document.getElementById('wpToggleWrap').style.display = waypoints.length ? 'flex' : 'none';

    landing.style.display = 'none';
    viewer.style.display = 'block';
    resizeCanvas();
    fitToContent();

    if (failures.length){
      console.warn('AuraMap viewer: some files failed to load', failures);
    }
  }

  function bboxOfTiles(preferContent){
    var any = false, minX=0,minZ=0,maxX=0,maxZ=0;
    tiles.forEach(function(t){
      if (preferContent && !t.hasContent) return;
      var x0=t.rx*regionSize, z0=t.rz*regionSize, x1=x0+regionSize, z1=z0+regionSize;
      if (!any){ minX=x0; minZ=z0; maxX=x1; maxZ=z1; any=true; }
      else { minX=Math.min(minX,x0); minZ=Math.min(minZ,z0); maxX=Math.max(maxX,x1); maxZ=Math.max(maxZ,z1); }
    });
    return any ? {minX:minX,minZ:minZ,maxX:maxX,maxZ:maxZ} : null;
  }

  function fitToContent(){
    var box = bboxOfTiles(true) || bboxOfTiles(false);
    if (!box) return;
    var w = canvas.clientWidth, h = canvas.clientHeight;
    var bw = Math.max(1, box.maxX-box.minX), bh = Math.max(1, box.maxZ-box.minZ);
    var z = Math.min(w/bw, h/bh) * 0.88;
    cam.zoom = clamp(z, MIN_ZOOM, MAX_ZOOM);
    cam.x = (box.minX+box.maxX)/2;
    cam.z = (box.minZ+box.maxZ)/2;
    draw();
  }

  function clamp(v,a,b){ return Math.max(a, Math.min(b, v)); }

  function resizeCanvas(){
    var w = canvas.clientWidth, h = canvas.clientHeight;
    dpr = Math.max(1, window.devicePixelRatio || 1);
    canvas.width = Math.round(w*dpr);
    canvas.height = Math.round(h*dpr);
    draw();
  }
  window.addEventListener('resize', resizeCanvas);

  function draw(){
    if (viewer.style.display === 'none') return;
    var w = canvas.clientWidth, h = canvas.clientHeight;
    ctx.save();
    ctx.setTransform(dpr,0,0,dpr,0,0);
    ctx.fillStyle = '#0a0d0b';
    ctx.fillRect(0,0,w,h);
    ctx.imageSmoothingEnabled = false;

    var halfWBlocks = (w/cam.zoom)/2, halfHBlocks = (h/cam.zoom)/2;
    var minBX = Math.floor(cam.x-halfWBlocks), maxBX = Math.ceil(cam.x+halfWBlocks);
    var minBZ = Math.floor(cam.z-halfHBlocks), maxBZ = Math.ceil(cam.z+halfHBlocks);
    var minRX = Math.floor(minBX/regionSize), maxRX = Math.floor(maxBX/regionSize);
    var minRZ = Math.floor(minBZ/regionSize), maxRZ = Math.floor(maxBZ/regionSize);
    var baseX = w/2 - cam.x*cam.zoom, baseZ = h/2 - cam.z*cam.zoom;

    for (var rz=minRZ; rz<=maxRZ; rz++){
      for (var rx=minRX; rx<=maxRX; rx++){
        var t = tiles.get(rx+','+rz);
        var ox = rx*regionSize, oz = rz*regionSize;
        var x0 = Math.floor(baseX+ox*cam.zoom), z0 = Math.floor(baseZ+oz*cam.zoom);
        var x1 = Math.floor(baseX+(ox+regionSize)*cam.zoom), z1 = Math.floor(baseZ+(oz+regionSize)*cam.zoom);
        var iw = x1-x0, ih = z1-z0;
        if (iw<=0 || ih<=0) continue;
        if (x0>w || z0>h || x0+iw<0 || z0+ih<0) continue;
        if (!t) continue;
        ctx.drawImage(t.canvas, 0,0, regionSize, regionSize, x0,z0, iw,ih);
      }
    }

    if (showWaypoints && waypoints.length) drawWaypoints(baseX, baseZ, w, h);

    ctx.restore();
    updateHud();
  }

  function drawWaypoints(baseX, baseZ, w, h){
    hoveredWp = null;
    var best = 999;
    waypoints.forEach(function(wp){
      var sx = baseX + wp.x*cam.zoom, sz = baseZ + wp.z*cam.zoom;
      wp.__sx = sx; wp.__sz = sz;
      if (sx < -20 || sz < -20 || sx > w+20 || sz > h+20) return;
      ctx.beginPath();
      ctx.arc(sx, sz, 6, 0, Math.PI*2);
      ctx.fillStyle = WP_COLORS[wp.color] || '#ffffff';
      ctx.fill();
      ctx.lineWidth = 1.5; ctx.strokeStyle = 'rgba(0,0,0,.8)'; ctx.stroke();
      ctx.fillStyle = contrastText(WP_COLORS[wp.color]);
      ctx.font = '700 9px "IBM Plex Mono", monospace';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(wp.initials, sx, sz+0.5);

      if (mouseCss){
        var d = Math.hypot(mouseCss.x-sx, mouseCss.y-sz);
        if (d < 10 && d < best){ best = d; hoveredWp = wp; }
      }
    });
    updateTooltip();
  }

  function contrastText(hex){
    if (!hex) return '#000';
    var r=parseInt(hex.slice(1,3),16), g=parseInt(hex.slice(3,5),16), b=parseInt(hex.slice(5,7),16);
    var lum = (0.299*r+0.587*g+0.114*b)/255;
    return lum > 0.6 ? '#111' : '#fff';
  }

  function updateTooltip(){
    if (!hoveredWp){ tooltip.style.display = 'none'; return; }
    tooltip.style.display = 'block';
    tooltip.style.left = hoveredWp.__sx+'px';
    tooltip.style.top = hoveredWp.__sz+'px';
    tooltip.innerHTML = '<b>'+escapeHtml(hoveredWp.name||'waypoint')+'</b> &middot; '+hoveredWp.x+', '+hoveredWp.z;
  }

  function updateHud(){
    document.getElementById('zoomReadout').textContent = cam.zoom.toFixed(2)+'x';
    var bx, bz;
    if (mouseCss){
      var w = canvas.clientWidth, h = canvas.clientHeight;
      bx = Math.round(cam.x + (mouseCss.x - w/2)/cam.zoom);
      bz = Math.round(cam.z + (mouseCss.y - h/2)/cam.zoom);
    } else { bx = Math.round(cam.x); bz = Math.round(cam.z); }
    document.getElementById('cx').textContent = bx;
    document.getElementById('cz').textContent = bz;
  }

  var pointers = new Map();
  var dragging = false, dragStart = {x:0,z:0}, dragMouse = {x:0,y:0};
  var pinch = null;

  function screenToWorld(cx, cy){
    var w = canvas.clientWidth, h = canvas.clientHeight;
    return { x: cam.x + (cx - w/2)/cam.zoom, z: cam.z + (cy - h/2)/cam.zoom };
  }

  canvas.addEventListener('pointerdown', function(e){
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, {x:e.clientX, y:e.clientY});
    if (pointers.size === 1){
      dragging = true; canvas.classList.add('grabbing');
      dragStart.x = cam.x; dragStart.z = cam.z;
      dragMouse.x = e.clientX; dragMouse.y = e.clientY;
    } else if (pointers.size === 2){
      dragging = false; canvas.classList.remove('grabbing');
      var pts = Array.from(pointers.values());
      var rect = canvas.getBoundingClientRect();
      var mid = {x:(pts[0].x+pts[1].x)/2 - rect.left, y:(pts[0].y+pts[1].y)/2 - rect.top};
      pinch = {
        startDist: Math.hypot(pts[0].x-pts[1].x, pts[0].y-pts[1].y),
        startZoom: cam.zoom,
        world: screenToWorld(mid.x, mid.y)
      };
    }
  });

  canvas.addEventListener('pointermove', function(e){
    var rect = canvas.getBoundingClientRect();
    mouseCss = {x:e.clientX-rect.left, y:e.clientY-rect.top};
    if (pointers.has(e.pointerId)) pointers.set(e.pointerId, {x:e.clientX, y:e.clientY});

    if (pointers.size === 2 && pinch){
      var pts = Array.from(pointers.values());
      var dist = Math.hypot(pts[0].x-pts[1].x, pts[0].y-pts[1].y);
      var mid = {x:(pts[0].x+pts[1].x)/2 - rect.left, y:(pts[0].y+pts[1].y)/2 - rect.top};
      cam.zoom = clamp(pinch.startZoom * (dist/Math.max(1,pinch.startDist)), MIN_ZOOM, MAX_ZOOM);
      cam.x = pinch.world.x - (mid.x - canvas.clientWidth/2)/cam.zoom;
      cam.z = pinch.world.z - (mid.y - canvas.clientHeight/2)/cam.zoom;
      draw();
      return;
    }
    if (dragging){
      cam.x = dragStart.x - (e.clientX - dragMouse.x)/cam.zoom;
      cam.z = dragStart.z - (e.clientY - dragMouse.y)/cam.zoom;
    }
    draw();
  });

  function endPointer(e){
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinch = null;
    if (pointers.size === 0){ dragging = false; canvas.classList.remove('grabbing'); }
  }
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);
  canvas.addEventListener('pointerleave', function(e){ if (!pointers.size){ mouseCss = null; draw(); } });

  canvas.addEventListener('wheel', function(e){
    e.preventDefault();
    var rect = canvas.getBoundingClientRect();
    var mx = e.clientX-rect.left, my = e.clientY-rect.top;
    var world = screenToWorld(mx, my);
    var factor = Math.pow(1.15, -e.deltaY/100);
    cam.zoom = clamp(cam.zoom*factor, MIN_ZOOM, MAX_ZOOM);
    cam.x = world.x - (mx - canvas.clientWidth/2)/cam.zoom;
    cam.z = world.z - (my - canvas.clientHeight/2)/cam.zoom;
    draw();
  }, {passive:false});

  document.getElementById('zoomInBtn').addEventListener('click', function(){ stepZoom(1.35); });
  document.getElementById('zoomOutBtn').addEventListener('click', function(){ stepZoom(1/1.35); });
  function stepZoom(f){
    cam.zoom = clamp(cam.zoom*f, MIN_ZOOM, MAX_ZOOM);
    draw();
  }
  document.getElementById('fitBtn').addEventListener('click', fitToContent);
  document.getElementById('wpToggle').addEventListener('change', function(e){ showWaypoints = e.target.checked; draw(); });
  document.getElementById('reloadBtn').addEventListener('click', function(){
    tiles.clear(); waypoints = [];
    folderInput.value = '';
    viewer.style.display = 'none';
    landing.style.display = 'flex';
    groupsEl.style.display = 'none';
    setStatus('');
  });

  window.addEventListener('keydown', function(e){
    if (viewer.style.display === 'none') return;
    if (e.key === '+' || e.key === '=') stepZoom(1.25);
    if (e.key === '-' || e.key === '_') stepZoom(1/1.25);
  });

})();
