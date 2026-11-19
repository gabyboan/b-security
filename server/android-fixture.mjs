/** Disposable HTTPS server for Android integration checks; never uses server/data. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createApi } from './server.mjs';

let server, directory, readyFile, fixtureParent;
let closing = false;

function removeOwnedFiles() {
  if (readyFile) fs.rmSync(readyFile, { force: true });
  if (!directory) return;
  // Only the unique direct child created by this process may be deleted recursively.
  const target = path.resolve(directory);
  if (path.dirname(target) !== fixtureParent || !path.basename(target).startsWith('fixture-')) {
    throw new Error('Invalid temporary fixture directory.');
  }
  fs.rmSync(target, { recursive: true, force: true });
}

function shutdown() {
  if (closing) return;
  closing = true;
  if (!server?.listening) { removeOwnedFiles(); return; }
  server.close(() => removeOwnedFiles());
  server.closeAllConnections();
}

async function main() {
  if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node.js 22 or newer is required.');
  const args = process.argv.slice(2);
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--ready', '--openssl'].includes(args[i]) || !args[i + 1] || options[args[i]]) {
      throw new Error('Usage: node android-fixture.mjs --ready <temporary-directory>/ready.json [--openssl <executable>]');
    }
    options[args[i]] = args[i + 1];
  }
  if (!options['--ready']) throw new Error('A temporary --ready file is required.');
  const parent = fs.realpathSync(path.dirname(path.resolve(options['--ready'])));
  const temp = fs.realpathSync(os.tmpdir());
  const relative = path.relative(temp, parent);
  if (!relative || relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) {
    throw new Error('The readiness file must be in a dedicated directory inside the system temporary directory.');
  }
  const output = path.join(parent, path.basename(options['--ready']));
  if (fs.existsSync(output)) throw new Error('The readiness file already exists.');
  fixtureParent = parent;
  directory = fs.mkdtempSync(path.join(parent, 'fixture-'));
  const certFile = path.join(directory, 'localhost.crt');
  const keyFile = path.join(directory, 'localhost.key');
  // This one-day CA/leaf is generated for this run and is trusted only by the test clients.
  execFileSync(options['--openssl'] ?? 'openssl', [
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1',
    '-keyout', keyFile, '-out', certFile, '-subj', '/CN=localhost',
    '-addext', 'subjectAltName=DNS:localhost',
    '-addext', 'basicConstraints=critical,CA:TRUE',
    '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign',
    '-addext', 'extendedKeyUsage=serverAuth'
  ], { stdio: 'ignore', timeout: 30_000, windowsHide: true });
  const cert = fs.readFileSync(certFile);
  let clock = Date.now();
  server = createApi({
    dataFile: path.join(directory, 'family.json'),
    tls: { cert, key: fs.readFileSync(keyFile), minVersion: 'TLSv1.2' },
    now: () => clock
  });
  // Test-only clock control: this route never exists in the production API.
  const apiHandler = server.listeners('request')[0];
  server.removeAllListeners('request');
  server.on('request', (request, response) => {
    if (request.method !== 'POST' || request.url !== '/__verification__/expire') {
      return apiHandler(request, response);
    }
    request.resume();
    clock += 90 * 86_400_000 + 1;
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify({ ok: true }));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  server.on('error', () => { process.exitCode = 1; shutdown(); });
  readyFile = output;
  fs.writeFileSync(output + '.tmp', JSON.stringify({ port: server.address().port, ca: cert.toString('base64') }), { mode: 0o600 });
  fs.renameSync(output + '.tmp', output);
}

process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
main().catch(error => {
  // Do not log key material, session credentials, or OpenSSL command output.
  console.error('Android HTTPS fixture could not start: ' + error.message);
  process.exitCode = 1;
  shutdown();
});
