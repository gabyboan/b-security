import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createApi } from './server.mjs';

async function fixture(run, options = {}) {
  let time = Date.UTC(2026, 10, 17);
  let server, root;
  const start = async () => {
    server = createApi({ ...options, now: () => time });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    root = 'http://127.0.0.1:' + server.address().port;
  };
  const stop = () => new Promise(resolve => {
    server.close(resolve);
    server.closeAllConnections();
  });
  await start();
  const api = async (route, token, body) => {
    const result = await fetch(root + route, { method: body !== undefined ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    return { status: result.status, ...await result.json() };
  };
  const session = async name => api('/v1/session', null, { name });
  try { await run({ api, session, root, advance: ms => { time += ms; }, restart: async () => { await stop(); await start(); } }); }
  finally { await stop(); }
}

async function familyFixture(run, options) {
  return fixture(async context => {
    const { api, session } = context;
    const parent = await session('Parent'), child = await session('Child');
    assert.equal((await api('/v1/family', parent.token, {})).status, 201);
    const invite = await api('/v1/invite', parent.token, {});
    assert.equal((await api('/v1/join', child.token, { code: invite.code })).status, 200);
    await run({ ...context, parent, child });
  }, options);
}

const apps = [{ pkg: 'com.example.game', name: 'Game' }, { pkg: 'com.example.video', name: 'Video' }];
const rules = { 'com.example.game': { blocked: true, minutes: 20 }, 'com.example.video': { blocked: false, minutes: 30 } };
const share = (api, child, extra = {}) => api('/v1/report', child.token, { consent: true, controls: true, apps, ...extra });

test('authentication, isolation, single-use invitations, child consent and policy authorization', () => fixture(async ({api,session}) => {
  assert.equal((await api('/v1/family')).status, 401);
  const a = await session('Parent A'), child = await session('Child'), outsider = await session('Other family'), sibling = await session('Sibling');
  await api('/v1/family', a.token, {name:'A'});
  await api('/v1/family', outsider.token, {name:'B'});
  const invite = await api('/v1/invite', a.token, {});
  assert.equal(invite.code.length,32);
  assert.equal((await api('/v1/join', child.token, {code:invite.code,role:'parent'})).role,'member');
  assert.equal((await api('/v1/join', outsider.token, {code:invite.code})).status,409);
  const next = await api('/v1/invite',a.token,{});
  await api('/v1/join',sibling.token,{code:next.code});
  const spare = await session('Spare');
  assert.equal((await api('/v1/join',spare.token,{code:invite.code})).status,400);
  assert.equal((await api('/v1/invite',child.token,{})).status,403);
  assert.equal((await api('/v1/report',child.token,{model:'test'})).status,400);
  const rules = {'com.example.game':{blocked:true,minutes:20}};
  assert.equal((await api('/v1/policy',a.token,{member:child.id,rules})).status,409);
  await api('/v1/report',child.token,{consent:true,model:'test',controls:true,apps:[{pkg:'com.example.game',name:'Game'}],
    location:{lat:-34,lon:-58,accuracy:8,ageSeconds:2}});
  assert.equal((await api('/v1/policy',child.token,{member:a.id,rules})).status,403);
  assert.equal((await api('/v1/policy',outsider.token,{member:child.id,rules})).status,404);
  assert.equal((await api('/v1/policy',a.token,{member:child.id,rules})).status,200);
  const state = await api('/v1/family',child.token);
  assert.deepEqual(state.policy.rules,rules);
  assert.equal((await api('/v1/family',outsider.token)).family.members.length,1);
  const sibState = await api('/v1/family',sibling.token);
  assert.equal(sibState.family.members.find(m=>m.id===child.id).report,null);
  const parentState = await api('/v1/family',a.token);
  assert.equal(parentState.family.members.find(m=>m.id===child.id).report.location.lat,-34);
  assert.deepEqual(parentState.family.members.find(m=>m.id===child.id).policy.rules,rules);
  assert.equal((await api('/v1/policy',a.token,{member:child.id,rules:{'com.unshared':{blocked:true,minutes:5}}})).status,400);
  assert.equal((await api('/v1/policy',a.token,{member:child.id,rules:{'com.example.game':{blocked:true,minutes:-1}}})).status,400);
  await api('/v1/report',child.token,{consent:true,controls:false});
  const revoked = (await api('/v1/family',a.token)).family.members.find(m=>m.id===child.id).report;
  assert.equal(revoked.location,undefined); assert.equal(revoked.apps.length,0);
  assert.equal((await api('/v1/policy',a.token,{member:child.id,rules})).status,409);
  await api('/v1/leave',child.token,{});
  assert.equal((await api('/v1/family',a.token)).family.members.length,2);
  assert.equal((await api('/v1/family',child.token)).family,null);
  await api('/v1/leave',a.token,{});
  assert.equal((await api('/v1/family',sibling.token)).family,null);
}));

test('expired invite and stale GPS cannot be shared; oversized request rejected', () => fixture(async ({api,session,advance}) => {
  const parent = await session('Parent'), child = await session('Child');
  await api('/v1/family',parent.token,{});
  const invite = await api('/v1/invite',parent.token,{});
  advance(16*60_000);
  assert.equal((await api('/v1/join',child.token,{code:invite.code})).status,400);
  const next = await api('/v1/invite',parent.token,{});
  await api('/v1/join',child.token,{code:next.code});
  await api('/v1/report',child.token,{consent:true,location:{lat:1,lon:2,ageSeconds:121}});
  const state=await api('/v1/family',parent.token);
  assert.equal(state.family.members.find(m=>m.id===child.id).report.location,undefined);
  assert.equal((await api('/v1/report',child.token,{consent:true,data:'x'.repeat(33000)})).status,413);
}));

test('rejects malformed bearer credentials', () => fixture(async ({ session, root }) => {
  const parent = await session('Parent');
  const malformed = await fetch(root + '/v1/family', {
    method: 'GET',
    headers: { Authorization: 'Basic ' + parent.token }
  });
  assert.equal(malformed.status, 401);
  const empty = await fetch(root + '/v1/family', {
    method: 'GET',
    headers: { Authorization: 'Bearer ' }
  });
  assert.equal(empty.status, 401);
}));

test('report consent must be the boolean true', () => familyFixture(async ({ api, child }) => {
  for (const consent of [false, null, 0, 1, 'true', 'false', {}, []]) {
    assert.equal((await share(api, child, { consent })).status, 400, JSON.stringify(consent));
  }
  assert.equal((await share(api, child)).status, 200);
}));

test('withdrawing control consent deletes policy and does not revive it on re-enrollment', () => familyFixture(async ({ api, parent, child }) => {
  await share(api, child);
  assert.equal((await api('/v1/policy', parent.token, { member: child.id, rules })).status, 200);
  await share(api, child, { controls: false });
  let state = await api('/v1/family', child.token);
  assert.deepEqual(state.policy, {});
  assert.deepEqual(state.family.members.find(m => m.id === child.id).report.apps, []);
  await share(api, child);
  state = await api('/v1/family', child.token);
  assert.deepEqual(state.policy, {});
}));

test('unsharing an app revokes only its remote rule', () => familyFixture(async ({ api, parent, child }) => {
  await share(api, child);
  await api('/v1/policy', parent.token, { member: child.id, rules });
  await share(api, child, { apps: [apps[1]] });
  const state = await api('/v1/family', child.token);
  assert.deepEqual(state.policy.rules, { 'com.example.video': rules['com.example.video'] });
}));

test('invitations expire exactly at the advertised deadline', () => fixture(async ({ api, session, advance }) => {
  const parent = await session('Parent'), child = await session('Child');
  await api('/v1/family', parent.token, {});
  const invite = await api('/v1/invite', parent.token, {});
  advance(15 * 60_000);
  assert.equal((await api('/v1/join', child.token, { code: invite.code })).status, 400);
}));

test('sessions expire exactly at 90 days of inactivity', () => fixture(async ({ api, session, advance }) => {
  const user = await session('Device');
  advance(90 * 86400_000);
  assert.equal((await api('/v1/family', user.token)).status, 401);
}));

function temporaryData(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bsecurity-test-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(os.tmpdir()));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return path.join(directory, 'family.json');
}

test('session renewal without a family survives a server restart', t => fixture(async ({ api, session, advance, restart }) => {
  const user = await session('Device');
  advance(89 * 86400_000);
  assert.equal((await api('/v1/family', user.token)).status, 200);
  await restart();
  advance(2 * 86400_000);
  assert.equal((await api('/v1/family', user.token)).status, 200);
}, { dataFile: temporaryData(t) }));

test('health is public and responses prevent caching and MIME sniffing', () => fixture(async ({ root, api, session }) => {
  const response = await fetch(root + '/health');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.match(response.headers.get('content-type'), /application\/json/);
  assert.equal((await response.json()).ok, true);
  const user = await session('Device');
  await api('/v1/family', user.token, {});
  assert.equal((await api('/missing', user.token)).status, 404);
}));

test('invalid JSON and non-object bodies are rejected without breaking the server', () => fixture(async ({ root, session }) => {
  for (const body of ['{', 'null', '[]', 'true', '42', '"text"']) {
    const response = await fetch(root + '/v1/session', { method: 'POST', body });
    assert.equal(response.status, 400, body);
    assert.equal(typeof (await response.json()).error, 'string');
  }
  assert.equal((await session('Still alive')).status, 201);
}));

test('new session throttling resets at exactly one minute', () => fixture(async ({ session, root, advance }) => {
  for (let n = 0; n < 8; n++) assert.equal((await session('Device')).status, 201);
  const response = await fetch(root + '/v1/session', { method: 'POST', body: '{}' });
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '60');
  await response.json();
  advance(60_000);
  assert.equal((await session('Device')).status, 201);
}));

test('request throttling includes unauthorized requests and recovers after its window', () => fixture(async ({ api, root, advance }) => {
  for (let n = 0; n < 180; n++) assert.equal((await api('/v1/family')).status, 401);
  const response = await fetch(root + '/v1/family');
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '60');
  await response.json();
  assert.equal((await api('/health')).status, 200);
  advance(60_000);
  assert.equal((await api('/v1/family')).status, 401);
}));

test('a concurrent invitation can enroll only one device', () => fixture(async ({ api, session }) => {
  const parent = await session('Parent'), one = await session('One'), two = await session('Two');
  await api('/v1/family', parent.token, {});
  const invite = await api('/v1/invite', parent.token, {});
  const results = await Promise.all([one, two].map(user => api('/v1/join', user.token, { code: invite.code })));
  assert.deepEqual(results.map(result => result.status).sort(), [200, 400]);
  assert.equal((await api('/v1/family', parent.token)).family.members.length, 2);
}));

test('the ten-device cap also applies to outstanding invitations', () => fixture(async ({ api, session, advance }) => {
  const parent = await session('Parent');
  await api('/v1/family', parent.token, {});
  const spareInvite = await api('/v1/invite', parent.token, {});
  for (let n = 0; n < 9; n++) {
    advance(60_000);
    const child = await session('Child ' + n);
    const invite = await api('/v1/invite', parent.token, {});
    assert.equal((await api('/v1/join', child.token, { code: invite.code })).status, 200);
  }
  const extra = await session('Extra');
  assert.equal((await api('/v1/invite', parent.token, {})).status, 409);
  assert.equal((await api('/v1/join', extra.token, { code: spareInvite.code })).status, 409);
  assert.equal((await api('/v1/family', parent.token)).family.members.length, 10);
}));

test('location validation rejects stale, malformed and out-of-range coordinates', () => familyFixture(async ({ api, parent, child }) => {
  const valid = { lat: -90, lon: 180, ageSeconds: 120, accuracy: 8 };
  assert.equal((await share(api, child, { location: valid })).status, 200);
  let report = (await api('/v1/family', parent.token)).family.members.find(m => m.id === child.id).report;
  assert.deepEqual(report.location, valid);
  for (const location of [null, {}, { ...valid, lat: 91 }, { ...valid, lon: -181 },
    { ...valid, ageSeconds: -1 }, { ...valid, ageSeconds: 121 }, { ...valid, lat: '1' }]) {
    await share(api, child, { location });
    report = (await api('/v1/family', parent.token)).family.members.find(m => m.id === child.id).report;
    assert.equal(report.location, undefined);
  }
}));

test('report fields are bounded and non-boolean controls do not share apps', () => familyFixture(async ({ api, child }) => {
  await share(api, child, { model: 'x'.repeat(100), android: 'y'.repeat(100), risks: 1001,
    apps: [null, { pkg: 'bad/package' }, ...Array.from({ length: 160 }, (_, n) => ({ pkg: 'com.example.app' + n, name: 'z'.repeat(100) }))] });
  let report = (await api('/v1/family', child.token)).family.members.find(m => m.id === child.id).report;
  assert.equal(report.model.length, 60);
  assert.equal(report.android.length, 15);
  assert.equal(report.risks, 1000);
  assert.equal(report.apps.length, 148);
  assert.equal(report.apps[0].name.length, 80);
  await share(api, child, { controls: 'true', risks: '999' });
  report = (await api('/v1/family', child.token)).family.members.find(m => m.id === child.id).report;
  assert.equal(report.controls, false);
  assert.deepEqual(report.apps, []);
  assert.equal(report.risks, 0);
}));

test('invalid policy payloads are atomic and preserve the last accepted policy', () => familyFixture(async ({ api, parent, child }) => {
  await share(api, child);
  await api('/v1/policy', parent.token, { member: child.id, rules });
  const invalid = [null, [], true, { 'com.example.game': [] }, { 'com.example.game': null },
    { 'com.example.game': { blocked: 1, minutes: 20 } }, { 'com.example.game': { blocked: true, minutes: '20' } },
    { 'com.example.game': { blocked: true, minutes: 1.5 } }, { 'com.example.game': { blocked: true, minutes: 1441 } },
    { ...rules, 'com.unshared.app': { blocked: true, minutes: 1 } },
    Object.fromEntries(Array.from({ length: 151 }, (_, n) => ['com.example.app' + n, { blocked: false, minutes: 1 }]))];
  for (const payload of invalid) {
    assert.equal((await api('/v1/policy', parent.token, { member: child.id, rules: payload })).status, 400);
    assert.deepEqual((await api('/v1/family', child.token)).policy.rules, rules);
  }
  for (const minutes of [0, 1440]) {
    assert.equal((await api('/v1/policy', parent.token, { member: child.id, rules: { 'com.example.game': { blocked: false, minutes } } })).status, 200);
  }
  assert.equal((await api('/v1/policy', parent.token, { member: child.id, rules: {} })).status, 200);
  assert.deepEqual((await api('/v1/family', child.token)).policy.rules, {});
}));

test('owner expiry disbands the family and erases reports and policies', () => familyFixture(async ({ api, parent, child, advance }) => {
  await share(api, child);
  await api('/v1/policy', parent.token, { member: child.id, rules });
  advance(89 * 86400_000);
  await api('/v1/family', child.token);
  advance(86400_000);
  const state = await api('/v1/family', child.token);
  assert.equal(state.family, null);
  assert.equal((await api('/v1/family', parent.token)).status, 401);
  await api('/v1/family', child.token, {});
  const newGroup = await api('/v1/family', child.token);
  assert.equal(newGroup.family.members[0].report, null);
  assert.deepEqual(newGroup.policy, {});
}));

test('member expiry removes only that member', () => familyFixture(async ({ api, parent, child, advance }) => {
  advance(89 * 86400_000);
  await api('/v1/family', parent.token);
  advance(86400_000);
  assert.equal((await api('/v1/family', parent.token)).family.members.length, 1);
  assert.equal((await api('/v1/family', child.token)).status, 401);
}));

test('leaving a group clears shared state and invalidates its outstanding invitations', () => familyFixture(async ({ api, session, parent, child }) => {
  await share(api, child);
  await api('/v1/policy', parent.token, { member: child.id, rules });
  const invitation = await api('/v1/invite', parent.token, {});
  await api('/v1/leave', parent.token, {});
  const newcomer = await session('Newcomer');
  assert.equal((await api('/v1/join', newcomer.token, { code: invitation.code })).status, 400);
  await api('/v1/family', child.token, {});
  const state = await api('/v1/family', child.token);
  assert.equal(state.family.members[0].report, null);
  assert.deepEqual(state.policy, {});
}));

test('persisted groups survive restart without storing bearer tokens or invitation codes', t => {
  const dataFile = temporaryData(t);
  return familyFixture(async ({ api, parent, child, restart }) => {
    await share(api, child);
    await api('/v1/policy', parent.token, { member: child.id, rules });
    const invite = await api('/v1/invite', parent.token, {});
    const serialized = fs.readFileSync(dataFile, 'utf8');
    for (const secret of [parent.token, child.token, invite.code]) assert.equal(serialized.includes(secret), false);
    await restart();
    assert.deepEqual((await api('/v1/family', child.token)).policy.rules, rules);
    assert.equal((await api('/v1/family', parent.token)).family.members.length, 2);
  }, { dataFile });
});

test('failed disk writes return a generic error and roll back in-memory changes', t => {
  const dataFile = temporaryData(t);
  return familyFixture(async ({ api, child }) => {
    await share(api, child, { model: 'Original' });
    fs.mkdirSync(dataFile + '.tmp');
    try {
      const result = await share(api, child, { model: 'Must not persist' });
      assert.equal(result.status, 500);
      assert.equal(result.error, 'Error interno.');
    } finally { fs.rmdirSync(dataFile + '.tmp'); }
    const state = await api('/v1/family', child.token);
    assert.equal(state.family.members.find(m => m.id === child.id).report.model, 'Original');
  }, { dataFile });
});
