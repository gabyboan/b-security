-- Disposable identities only. Always rolled back, including all Auth fixtures.
begin;
set local plpgsql.check_asserts = on;
insert into auth.users(id,aud,role,email,is_anonymous,raw_user_meta_data) values
 ('b5100000-0000-4000-8000-000000000001','authenticated','authenticated','home-admin@fixture.invalid',false,'{}'),
 ('b5100000-0000-4000-8000-000000000002','authenticated','authenticated','home-member@fixture.invalid',false,'{"role":"admin"}'),
 ('b5100000-0000-4000-8000-000000000003','authenticated','authenticated','other-home@fixture.invalid',false,'{}');
insert into auth.sessions(id,user_id,created_at,updated_at) values
 ('b5200000-0000-4000-8000-000000000001','b5100000-0000-4000-8000-000000000001',now(),now()),
 ('b5200000-0000-4000-8000-000000000002','b5100000-0000-4000-8000-000000000002',now(),now()),
 ('b5200000-0000-4000-8000-000000000003','b5100000-0000-4000-8000-000000000003',now(),now());
create function pg_temp.identity(n int) returns void language sql as $f$
 select set_config('request.jwt.claims',jsonb_build_object('sub','b5100000-0000-4000-8000-'||lpad(n::text,12,'0'),
  'session_id','b5200000-0000-4000-8000-'||lpad(n::text,12,'0'),'role','authenticated')::text,true)::text::void
$f$;
create function pg_temp.rpc(a text,n int,fields jsonb default '{}') returns jsonb language sql as $f$
 select public.bsecurity_home(fields||jsonb_build_object('action',a,'device_id','b5300000-0000-4000-8000-'||lpad(n::text,12,'0'),'device_name','Fixture Android'))
$f$;
create function pg_temp.reject(a text,n int,fields jsonb,expected text) returns void language plpgsql as $f$
begin
 begin perform pg_temp.rpc(a,n,fields);
 exception when others then
   if sqlerrm=expected then return; end if;
   raise exception 'Unexpected rejection: %, expected %',sqlerrm,expected;
 end;
 raise exception 'Expected rejection: %',expected;
end $f$;
-- Use the actual API role, rather than a privileged table owner, for every RPC.
set local role authenticated;
do $tests$
declare r jsonb; code text;
begin
 perform pg_temp.identity(1);
 r:=pg_temp.rpc('create',1);
 assert r#>>'{home,role}'='admin','Creator must be admin';
 assert jsonb_array_length(r#>'{home,devices}')=1,'Only own installation';
 code:=pg_temp.rpc('invite',1)->>'code';
 assert length(code)=32,'128-bit code';

 perform pg_temp.identity(2);
 assert pg_temp.rpc('status',2)->'home'='null'::jsonb,'No automatic group';
 perform pg_temp.reject('start',2,'{"consent":true}','BSEC_HOME');
 perform pg_temp.reject('join',2,'{"code":"wrong"}','BSEC_INVITE');
 r:=pg_temp.rpc('join',2,jsonb_build_object('code',lower(code)));
 assert r#>>'{home,role}'='member','Metadata must never grant admin';
 assert jsonb_array_length(r#>'{home,devices}')=1,'Member sees only self';
 assert r#>>'{home,devices,0,sharing}'='false','Join must not enable sharing';
 perform pg_temp.reject('invite',2,'{}','BSEC_ADMIN');
 perform pg_temp.reject('delete',2,'{}','BSEC_ADMIN');
 perform pg_temp.reject('remove',2,'{"target":"b5300000-0000-4000-8000-000000000001"}','BSEC_ADMIN');
 perform pg_temp.reject('location',2,'{"lat":-34.6,"lon":-58.4,"accuracy":10,"age_seconds":1}','BSEC_CONSENT');
 perform pg_temp.reject('start',2,'{"consent":false}','BSEC_CONSENT');
 perform pg_temp.rpc('start',2,'{"consent":true}');
 perform pg_temp.reject('location',2,'{"lat":"NaN","lon":0,"accuracy":10,"age_seconds":1}','BSEC_INPUT');
 perform pg_temp.reject('location',2,'{"lat":0,"lon":0,"accuracy":10,"age_seconds":121}','BSEC_INPUT');
 perform pg_temp.rpc('location',2,'{"lat":-34.6,"lon":-58.4,"accuracy":10,"age_seconds":1}');

 perform pg_temp.identity(1);
 r:=pg_temp.rpc('status',1);
 assert jsonb_array_length(r#>'{home,devices}')=2,'Admin sees joined devices';
 assert jsonb_path_exists(r,'$.home.devices[*].location.lat ? (@ == -34.6)'), 'Admin sees consented position';
 perform pg_temp.reject('status',2,'{}','BSEC_DEVICE');
 assert pg_temp.rpc('status',4)->'home'='null'::jsonb,'Same Google account must not silently join another installation';
 r:=pg_temp.rpc('create',4);
 assert r#>>'{home,role}'='admin' and jsonb_array_length(r#>'{home,devices}')=3,'Explicit recovery joins the existing owned home';
 perform pg_temp.rpc('remove',1,'{"target":"b5300000-0000-4000-8000-000000000004"}');
 perform set_config('bsecurity.fixture_invite',pg_temp.rpc('invite',1)->>'code',true);

 perform pg_temp.identity(3);
 r:=pg_temp.rpc('create',3);
 assert jsonb_array_length(r#>'{home,devices}')=1,'Cross-home isolation';
 perform pg_temp.reject('status',2,'{}','BSEC_DEVICE');
 perform pg_temp.rpc('leave',3);
 perform pg_temp.reject('join',3,jsonb_build_object('code',code),'BSEC_INVITE');

 perform pg_temp.identity(2);
 r:=pg_temp.rpc('stop',2);
 assert r#>'{home,devices,0,location}'='null'::jsonb,'Stop removes coordinates';
 perform pg_temp.rpc('start',2,'{"consent":true}');
 perform pg_temp.rpc('location',2,'{"lat":-34.6,"lon":-58.4,"accuracy":10,"age_seconds":1}');
end $tests$;
reset role;
-- Simulate a killed sender, expired invitation and a revoked Auth session.
update bsecurity_private.invites set expires_at=now()-interval '1 second'
 where home_id in (select id from bsecurity_private.homes where owner_id='b5100000-0000-4000-8000-000000000001');
update bsecurity_private.devices set share_until=now()-interval '1 second' where id='b5300000-0000-4000-8000-000000000002';
set local role authenticated;
do $tests$
declare r jsonb;
begin
 perform pg_temp.identity(3);
 perform pg_temp.reject('join',3,jsonb_build_object('code',current_setting('bsecurity.fixture_invite')),'BSEC_INVITE');
 perform pg_temp.identity(1);
 r:=pg_temp.rpc('status',1);
 assert not jsonb_path_exists(r,'$.home.devices[*].location.lat'),'Expired lease hides position';
 perform pg_temp.identity(2);
 perform pg_temp.rpc('location',2,'{"lat":-34.6,"lon":-58.4,"accuracy":10,"age_seconds":1}');
end $tests$;
reset role;
delete from auth.sessions where id='b5200000-0000-4000-8000-000000000002';
set local role authenticated;
do $tests$
declare r jsonb;
begin
 perform pg_temp.identity(2);
 perform pg_temp.reject('status',2,'{}','BSEC_AUTH');
 perform pg_temp.identity(1);
 r:=pg_temp.rpc('status',1);
 assert not jsonb_path_exists(r,'$.home.devices[*].location.lat'),'Logout immediately hides location';
 r:=pg_temp.rpc('remove',1,'{"target":"b5300000-0000-4000-8000-000000000002"}');
 assert jsonb_array_length(r#>'{home,devices}')=1,'Remove revokes membership';
 assert pg_temp.rpc('delete',1)->'home'='null'::jsonb,'Delete unlinks owner';
end $tests$;
reset role;
do $permissions$
begin
 assert not has_function_privilege('anon','public.bsecurity_home(jsonb)','execute'),'Anon cannot call RPC';
 assert not has_function_privilege('anon','bsecurity_private.home_action(jsonb)','execute'),'Anon cannot call definer';
 assert not has_table_privilege('authenticated','bsecurity_private.devices','select'),'No direct device access';
 assert not has_table_privilege('authenticated','bsecurity_private.homes','insert'),'No direct role escalation';
 assert (select bool_and(relrowsecurity) from pg_class where oid in ('bsecurity_private.devices'::regclass,'bsecurity_private.homes'::regclass,'bsecurity_private.invites'::regclass)),'RLS enabled';
end $permissions$;
rollback;
select 'Home security: all assertions passed; all fixtures rolled back' as result;
