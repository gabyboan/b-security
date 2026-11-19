import http from 'node:http';
import https from 'node:https';
import { execFile } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const hash = value => createHash('sha256').update(value).digest('hex');
const id = () => randomBytes(16).toString('hex');
const token = () => randomBytes(32).toString('base64url');
const clean = (value, max = 60) => typeof value === 'string' ? value.trim().slice(0, max) : '';
const error = (status, message) => Object.assign(new Error(message), { status });

export function createApi({ dataFile = null, now = Date.now, tls = null } = {}) {
  let db = { sessions: {}, families: {}, invites: {} };
  if (dataFile && fs.existsSync(dataFile)) db = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
  const buckets = new Map();
  function save() {
    if (!dataFile) return;
    fs.mkdirSync(path.dirname(dataFile), { recursive: true });
    fs.writeFileSync(dataFile + '.tmp', JSON.stringify(db), { mode: 0o600 });
    fs.renameSync(dataFile + '.tmp', dataFile);
  }
  function collect() {
    let changed = false;
    for (const [key, invite] of Object.entries(db.invites)) if (invite.expires <= now()) { delete db.invites[key]; changed = true; }
    for (const [key, session] of Object.entries(db.sessions)) {
      if (session.expires <= now()) {
        if (session.family) {
          const family = db.families[session.family];
          if (family?.owner === session.id) {
            disband(family);
          } else if (family) family.members = family.members.filter(member => member !== session.id);
        }
        delete db.sessions[key];
        changed = true;
      }
    }
    return changed;
  }
  function disband(family) {
    for (const peer of Object.values(db.sessions)) if (peer.family === family.id) { delete peer.family; delete peer.report; delete peer.policy; }
    for (const [key, invite] of Object.entries(db.invites)) if (invite.family === family.id) delete db.invites[key];
    delete db.families[family.id];
  }
  const handler = async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    let beforeRequest;
    const send = (status, value) => {
      // Commit before acknowledging success, including session renewal on GET.
      if (beforeRequest && status < 400) save();
      res.writeHead(status); res.end(JSON.stringify(value));
    };
    try {
      if (req.url === '/health' && req.method === 'GET') return send(200, { ok: true, service: 'B-Security family', version: 1 });
      const remote = req.socket.remoteAddress ?? '?';
      if (buckets.size > 10000) buckets.clear();
      const bucket = buckets.get(remote) ?? { start: now(), count: 0, sessions: 0 };
      if (now() - bucket.start >= 60_000) { bucket.start = now(); bucket.count = 0; bucket.sessions = 0; }
      buckets.set(remote, bucket);
      const limited = message => {
        res.setHeader('Retry-After', Math.max(1, Math.ceil((bucket.start + 60_000 - now()) / 1000)));
        return error(429, message);
      };
      if (++bucket.count > 180) throw limited('Demasiadas solicitudes. Reintentá en un minuto.');
      let size = 0; const parts = [];
      for await (const chunk of req) { size += chunk.length; if (size > 32_768) throw error(413, 'Solicitud demasiado grande.'); parts.push(chunk); }
      let body = {};
      try { if (size) body = JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { throw error(400, 'JSON inválido.'); }
      if (!body || Array.isArray(body) || typeof body !== 'object') throw error(400, 'Se esperaba un objeto.');
      if (collect()) save();
      // No asynchronous work occurs between this snapshot and the response.
      // Failed validation or persistence must not leave partial changes in memory.
      beforeRequest = structuredClone(db);
      if (req.method === 'POST' && req.url === '/v1/session') {
        if (++bucket.sessions > 8) throw limited('Límite de nuevas sesiones por minuto.');
        const secret = token();
        const session = { id: id(), name: clean(body.name) || 'Dispositivo', expires: now() + 90 * 86400_000 };
        db.sessions[hash(secret)] = session;
        return send(201, { token: secret, id: session.id, expires: session.expires });
      }
      const auth = typeof req.headers.authorization === 'string' ? req.headers.authorization.trim() : '';
      const bearer = /^Bearer\s+([A-Za-z0-9._~+/=-]+)$/i.exec(auth)?.[1];
      const session = bearer && db.sessions[hash(bearer)];
      if (!session) throw error(401, 'Sesión inválida o vencida.');
      session.expires = now() + 90 * 86400_000;
      const family = db.families[session.family];
      const parent = family?.owner === session.id;
      const memberById = memberId => Object.values(db.sessions).find(value => value.id === memberId && value.family === session.family);
      if (req.method === 'POST' && req.url === '/v1/family') {
        if (family) throw error(409, 'Este dispositivo ya pertenece a un grupo.');
        const group = { id: id(), name: clean(body.name) || 'Mi familia', owner: session.id, members: [session.id] };
        db.families[group.id] = group; session.family = group.id;
        return send(201, { name: group.name });
      }
      if (req.method === 'POST' && req.url === '/v1/invite') {
        if (!parent) throw error(403, 'Solo el responsable puede invitar.');
        if (family.members.length >= 10) throw error(409, 'Máximo diez dispositivos.');
        // Single-use 128-bit invitation, 15-minute lifetime. Never a predictable six-digit code.
        const code = randomBytes(16).toString('hex');
        db.invites[hash(code)] = { family: family.id, expires: now() + 15 * 60_000 };
        return send(201, { code, expires: now() + 15 * 60_000 });
      }
      if (req.method === 'POST' && req.url === '/v1/join') {
        if (family) throw error(409, 'Salí primero del grupo actual.');
        const code = clean(body.code, 100).toLowerCase().replaceAll(' ', '');
        const key = hash(code); const invite = db.invites[key];
        const target = invite && db.families[invite.family];
        if (!invite || !target || invite.expires <= now()) throw error(400, 'Invitación inválida, usada o vencida.');
        if (target.members.length >= 10) throw error(409, 'Grupo completo.');
        target.members.push(session.id); session.family = target.id; delete db.invites[key];
        return send(200, { name: target.name, role: 'member' });
      }
      if (!family) {
        if (req.method === 'GET' && req.url === '/v1/family') return send(200, { family: null });
        throw error(409, 'No pertenecés a un grupo.');
      }
      if (req.method === 'GET' && req.url === '/v1/family') {
        const members = family.members.map(memberById).filter(Boolean).map(value => ({
          id: value.id, name: value.name, role: value.id === family.owner ? 'parent' : 'member',
          report: parent || value.id === session.id ? value.report ?? null : null,
          policy: parent ? value.policy ?? {} : undefined
        }));
        return send(200, { family: { name: family.name, role: parent ? 'parent' : 'member', members }, policy: session.policy ?? {} });
      }
      if (req.method === 'POST' && req.url === '/v1/report') {
        if (body.consent !== true) throw error(400, 'Falta consentimiento explícito.');
        const report = { at: now(), model: clean(body.model), android: clean(body.android, 15),
          risks: Number.isInteger(body.risks) ? Math.max(0, Math.min(1000, body.risks)) : 0,
          controls: body.controls === true, apps: [] };
        if (report.controls && Array.isArray(body.apps)) report.apps = body.apps.slice(0, 150).filter(a =>
          a && typeof a.pkg === 'string' && /^[a-zA-Z0-9_.]{3,200}$/.test(a.pkg)
        ).map(a => ({ pkg: a.pkg, name: clean(a.name, 80) }));
        if (body.location && Number.isFinite(body.location.lat) && Number.isFinite(body.location.lon) &&
            Math.abs(body.location.lat) <= 90 && Math.abs(body.location.lon) <= 180 &&
            Number.isFinite(body.location.ageSeconds) && body.location.ageSeconds >= 0 && body.location.ageSeconds <= 120) {
          report.location = { lat: body.location.lat, lon: body.location.lon,
            accuracy: Number.isFinite(body.location.accuracy) ? Math.max(0, body.location.accuracy) : null,
            ageSeconds: body.location.ageSeconds };
        }
        // Replacing the report removes previously shared location/apps when consent is withdrawn.
        session.report = report;
        if (!report.controls) delete session.policy;
        else if (session.policy?.rules) {
          const shared = new Set(report.apps.map(app => app.pkg));
          const retained = Object.entries(session.policy.rules).filter(([pkg]) => shared.has(pkg));
          if (retained.length !== Object.keys(session.policy.rules).length) {
            session.policy = { rules: Object.fromEntries(retained), updatedAt: now() };
          }
        }
        return send(200, { ok: true });
      }
      if (req.method === 'POST' && req.url === '/v1/policy') {
        if (!parent) throw error(403, 'Solo el responsable puede cambiar reglas.');
        const child = memberById(body.member);
        if (!child || child.id === session.id) throw error(404, 'Integrante no encontrado.');
        if (!child.report?.controls) throw error(409, 'El integrante no aceptó controles remotos.');
        const allowed = new Set((child.report.apps ?? []).map(a => a.pkg));
        const rules = Object.create(null);
        if (!body.rules || typeof body.rules !== 'object' || Array.isArray(body.rules)) throw error(400, 'Reglas inválidas.');
        if (Object.keys(body.rules).length > 150) throw error(400, 'Demasiadas reglas.');
        for (const [pkg, value] of Object.entries(body.rules)) {
          if (!allowed.has(pkg) || !value || typeof value !== 'object' || Array.isArray(value)) throw error(400, 'App no compartida por el integrante.');
          if (!Number.isInteger(value.minutes) || value.minutes < 0 || value.minutes > 1440 || typeof value.blocked !== 'boolean') throw error(400, 'Límite inválido.');
          rules[pkg] = { blocked: value.blocked, minutes: value.minutes };
        }
        child.policy = { rules, updatedAt: now() }; return send(200, { ok: true });
      }
      if (req.method === 'POST' && req.url === '/v1/leave') {
        if (parent) {
          disband(family);
        } else {
          family.members = family.members.filter(member => member !== session.id);
          delete session.family; delete session.report; delete session.policy;
        }
        return send(200, { ok: true });
      }
      throw error(404, 'Ruta no encontrada.');
    } catch (err) {
      if (beforeRequest) db = beforeRequest;
      send(err.status ?? 500, { error: err.status ? err.message : 'Error interno.' });
    }
  };
  const server = tls ? https.createServer(tls, handler) : http.createServer(handler);
  server.requestTimeout = 10_000;
  server.headersTimeout = 8_000;
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 8877);
  const dataFile = process.env.BSECURITY_DATA ?? fileURLToPath(new URL('./data/family.json', import.meta.url));
  const certFile = process.env.BSECURITY_TLS_CERT, keyFile = process.env.BSECURITY_TLS_KEY;
  const tls = certFile && keyFile ? { cert: fs.readFileSync(certFile), key: fs.readFileSync(keyFile), minVersion: 'TLSv1.2' } : null;
  const bind = process.env.BSECURITY_BIND ?? '127.0.0.1';
  const octets = bind.split('.').map(Number);
  if (bind !== '127.0.0.1' && !(tls && octets.length === 4 && octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127 && octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255))) {
    throw new Error('Solo se permite loopback o una IPv4 Tailscale con HTTPS.');
  }
  const server = createApi({ dataFile, tls });
  server.on('error', err => { console.error('No se pudo iniciar Familia:', err.code); process.exitCode = 1; });
  server.listen(port, bind, () => console.log('B-Security Familia: ' + bind + ':' + port + (tls ? ' HTTPS privado' : ' loopback; requiere proxy HTTPS')));
  if (tls && process.env.BSECURITY_RENEW_CLI && process.env.BSECURITY_TLS_HOST) {
    const renew = () => execFile(process.env.BSECURITY_RENEW_CLI, ['cert', '--cert-file=' + certFile,
      '--key-file=' + keyFile, '--min-validity=240h', process.env.BSECURITY_TLS_HOST],
      { timeout: 60000, windowsHide: true }, err => {
        if (err) return console.error('Renovación TLS pendiente; revisar Tailscale.');
        try { server.setSecureContext({ ...tls, cert: fs.readFileSync(certFile), key: fs.readFileSync(keyFile) }); }
        catch { console.error('No se pudo recargar certificado TLS.'); }
      });
    setInterval(renew, 12 * 3600_000).unref();
  }
}
