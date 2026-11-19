-- Cloud homes. Raw records are private; the single RPC validates the live Auth
-- session, installation ownership, group membership and explicit sharing consent.
begin;
-- The existing DDL event trigger is for the database owner, never an API RPC.
do $permissions$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
  end if;
end $permissions$;
create schema if not exists bsecurity_private;
revoke all on schema bsecurity_private from public, anon, authenticated;
grant usage on schema bsecurity_private to authenticated;

create table bsecurity_private.homes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null unique references auth.users(id) on delete cascade,
  name text not null check (length(name) between 1 and 60),
  created_at timestamptz not null default now()
);
create table bsecurity_private.devices (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  session_id uuid references auth.sessions(id) on delete set null,
  home_id uuid references bsecurity_private.homes(id) on delete set null,
  name text not null check (length(name) between 1 and 100),
  last_seen timestamptz not null default now(),
  sharing boolean not null default false,
  share_until timestamptz,
  lat double precision, lon double precision, accuracy double precision,
  captured_at timestamptz, mock boolean not null default false,
  check (lat is null or lat between -90 and 90),
  check (lon is null or lon between -180 and 180),
  check (accuracy is null or accuracy between 0 and 100000)
);
create index devices_user_id_idx on bsecurity_private.devices(user_id);
create index devices_home_id_idx on bsecurity_private.devices(home_id);
create index devices_session_id_idx on bsecurity_private.devices(session_id);
create table bsecurity_private.invites (
  code_hash bytea primary key,
  home_id uuid not null references bsecurity_private.homes(id) on delete cascade,
  expires_at timestamptz not null
);
create index invites_home_id_idx on bsecurity_private.invites(home_id);
alter table bsecurity_private.homes enable row level security;
alter table bsecurity_private.devices enable row level security;
alter table bsecurity_private.invites enable row level security;
revoke all on all tables in schema bsecurity_private from public, anon, authenticated;

create function bsecurity_private.home_action(payload jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $function$
declare
  uid uuid := auth.uid();
  sid uuid;
  did uuid;
  action text := payload->>'action';
  d bsecurity_private.devices;
  h bsecurity_private.homes;
  invitation bsecurity_private.invites;
  code text;
  answer jsonb;
  age_seconds double precision;
  latitude double precision;
  longitude double precision;
  precision_m double precision;
  lock_home uuid;
  initial_home uuid;
begin
  if uid is null then raise exception 'BSEC_AUTH'; end if;
  begin sid := (auth.jwt()->>'session_id')::uuid;
  exception when invalid_text_representation then raise exception 'BSEC_AUTH'; end;
  if not exists (select 1 from auth.sessions s join auth.users u on u.id=s.user_id
    where s.id=sid and s.user_id=uid and not coalesce(u.is_anonymous,false)
    and (s.not_after is null or s.not_after > clock_timestamp())) then
    raise exception 'BSEC_AUTH';
  end if;
  if payload is null or jsonb_typeof(payload)<>'object' or length(payload::text)>4096 then
    raise exception 'BSEC_INPUT';
  end if;
  begin did := (payload->>'device_id')::uuid;
  exception when invalid_text_representation then raise exception 'BSEC_INPUT'; end;
  if did is null then raise exception 'BSEC_INPUT'; end if;
  -- Serialize an installation and an account's registrations, avoiding duplicate
  -- ownership/rebinding races and enforcing the registration limit transactionally.
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 410));
  select * into d from bsecurity_private.devices where id=did;
  initial_home := d.home_id;
  lock_home := initial_home;
  if lock_home is null and action='create' then
    select id into lock_home from bsecurity_private.homes where owner_id=uid;
  elsif lock_home is null and action='join' then
    code := upper(regexp_replace(coalesce(payload->>'code',''),'[[:space:]-]','','g'));
    select home_id into lock_home from bsecurity_private.invites where code_hash=extensions.digest(code,'sha256');
  end if;
  -- All rows of a home are locked in the same order, before any device row.
  -- This also serializes invite capacity checks and position cleanup.
  if lock_home is not null then perform pg_advisory_xact_lock(hashtextextended(lock_home::text,411)); end if;
  select * into d from bsecurity_private.devices where id=did for update;
  if d.home_id is distinct from initial_home then raise exception 'BSEC_RETRY'; end if;
  if not found then
    if action not in ('status','create','join') then raise exception 'BSEC_DEVICE'; end if;
    if (select count(*) from bsecurity_private.devices where user_id=uid)>=32 then
      raise exception 'BSEC_LIMIT';
    end if;
    insert into bsecurity_private.devices(id,user_id,session_id,name)
      values(did,uid,sid,left(coalesce(nullif(trim(payload->>'device_name'),''),'Android'),100)) returning * into d;
  end if;
  if d.user_id<>uid then raise exception 'BSEC_DEVICE'; end if;
  if d.session_id is distinct from sid then
    if action not in ('status','create','join') then raise exception 'BSEC_AUTH'; end if;
    update bsecurity_private.devices set session_id=sid,sharing=false,share_until=null,
      lat=null,lon=null,accuracy=null,captured_at=null where id=did returning * into d;
  end if;
  update bsecurity_private.devices set last_seen=clock_timestamp() where id=did;
  if d.home_id is not null then
    select * into h from bsecurity_private.homes where id=d.home_id for update;
  end if;

  case action
  when 'status' then null;
  when 'create' then
    if h.id is not null and h.owner_id<>uid then raise exception 'BSEC_ALREADY_JOINED'; end if;
    select * into h from bsecurity_private.homes where owner_id=uid for update;
    if not found then
      insert into bsecurity_private.homes(owner_id,name)
        values(uid,left(coalesce(nullif(trim(payload->>'name'),''),'Mi hogar'),60)) returning * into h;
    end if;
    if d.home_id is null and (select count(*) from bsecurity_private.devices where home_id=h.id)>=32 then raise exception 'BSEC_LIMIT'; end if;
    update bsecurity_private.devices set home_id=h.id where id=did;
  when 'invite' then
    if h.id is null or h.owner_id<>uid then raise exception 'BSEC_ADMIN'; end if;
    delete from bsecurity_private.invites where home_id=h.id and expires_at<=clock_timestamp();
    if (select count(*) from bsecurity_private.invites where home_id=h.id)>=5 then raise exception 'BSEC_LIMIT'; end if;
    code := upper(encode(extensions.gen_random_bytes(16),'hex'));
    insert into bsecurity_private.invites(code_hash,home_id,expires_at)
      values(extensions.digest(code,'sha256'),h.id,clock_timestamp()+interval '15 minutes');
    return jsonb_build_object('code',code,'expires_in',900);
  when 'join' then
    if h.id is not null then raise exception 'BSEC_ALREADY_JOINED'; end if;
    code := upper(regexp_replace(coalesce(payload->>'code',''),'[[:space:]-]','','g'));
    if code !~ '^[0-9A-F]{32}$' then raise exception 'BSEC_INVITE'; end if;
    -- Home first, then invite: consistent locking with invite/remove/delete.
    select * into invitation from bsecurity_private.invites where code_hash=extensions.digest(code,'sha256');
    if not found then raise exception 'BSEC_INVITE'; end if;
    select * into h from bsecurity_private.homes where id=invitation.home_id for update;
    delete from bsecurity_private.invites where code_hash=extensions.digest(code,'sha256')
      and expires_at>clock_timestamp() returning * into invitation;
    if not found or h.id is null then raise exception 'BSEC_INVITE'; end if;
    if (select count(*) from bsecurity_private.devices where home_id=h.id)>=32 then raise exception 'BSEC_LIMIT'; end if;
    update bsecurity_private.devices set home_id=h.id,sharing=false,share_until=null,
      lat=null,lon=null,accuracy=null,captured_at=null where id=did;
  when 'start' then
    if h.id is null then raise exception 'BSEC_HOME'; end if;
    if payload->>'consent' is distinct from 'true' then raise exception 'BSEC_CONSENT'; end if;
    update bsecurity_private.devices set sharing=true,share_until=clock_timestamp()+interval '90 seconds',
      lat=null,lon=null,accuracy=null,captured_at=null where id=did;
  when 'ping', 'location' then
    if h.id is null or not d.sharing then raise exception 'BSEC_CONSENT'; end if;
    if action='location' then
      begin
        latitude := (payload->>'lat')::double precision;
        longitude := (payload->>'lon')::double precision;
        precision_m := (payload->>'accuracy')::double precision;
        age_seconds := (payload->>'age_seconds')::double precision;
      exception when invalid_text_representation or numeric_value_out_of_range then raise exception 'BSEC_INPUT'; end;
      if latitude is null or longitude is null or precision_m is null or age_seconds is null
        or not (latitude between -90 and 90) or not (longitude between -180 and 180)
        or not (precision_m between 0 and 100000) or not (age_seconds between 0 and 120) then
        raise exception 'BSEC_INPUT';
      end if;
      update bsecurity_private.devices set lat=latitude,lon=longitude,accuracy=precision_m,
        captured_at=clock_timestamp()-age_seconds*interval '1 second',mock=coalesce((payload->>'mock')::boolean,false)
        where id=did;
    end if;
    update bsecurity_private.devices set share_until=clock_timestamp()+interval '90 seconds' where id=did;
  when 'stop', 'leave' then
    update bsecurity_private.devices set sharing=false,share_until=null,lat=null,lon=null,accuracy=null,captured_at=null,
      home_id=case when action='leave' then null else home_id end where id=did;
  when 'remove' then
    if h.id is null or h.owner_id<>uid then raise exception 'BSEC_ADMIN'; end if;
    update bsecurity_private.devices set home_id=null,sharing=false,share_until=null,
      lat=null,lon=null,accuracy=null,captured_at=null
      where home_id=h.id and id=(payload->>'target')::uuid and id<>did;
    if not found then raise exception 'BSEC_DEVICE'; end if;
  when 'delete' then
    if h.id is null or h.owner_id<>uid then raise exception 'BSEC_ADMIN'; end if;
    update bsecurity_private.devices set home_id=null,sharing=false,share_until=null,
      lat=null,lon=null,accuracy=null,captured_at=null where home_id=h.id;
    delete from bsecurity_private.homes where id=h.id;
  else raise exception 'BSEC_INPUT';
  end case;

  select * into d from bsecurity_private.devices where id=did;
  if d.home_id is null then return jsonb_build_object('device_id',did,'home',null); end if;
  select * into h from bsecurity_private.homes where id=d.home_id;
  -- Erase expired coordinates as well as withholding them from responses.
  update bsecurity_private.devices set lat=null,lon=null,accuracy=null,captured_at=null
    where home_id=h.id and captured_at is not null
      and (share_until<=clock_timestamp() or captured_at<clock_timestamp()-interval '2 minutes');
  select coalesce(jsonb_agg(jsonb_build_object(
    'id',v.id,'name',v.name,'self',v.id=did,'last_seen',v.last_seen,
    'sharing',v.sharing and v.share_until>clock_timestamp() and exists(select 1 from auth.sessions s
      where s.id=v.session_id and (s.not_after is null or s.not_after>clock_timestamp())),
    'location',case when v.sharing and v.share_until>clock_timestamp()
      and v.captured_at>=clock_timestamp()-interval '2 minutes'
      and exists(select 1 from auth.sessions s where s.id=v.session_id and (s.not_after is null or s.not_after>clock_timestamp()))
      then jsonb_build_object('lat',v.lat,'lon',v.lon,'accuracy',v.accuracy,'at',v.captured_at,'mock',v.mock,
        'valid_for_seconds',greatest(0,extract(epoch from least(v.share_until,v.captured_at+interval '2 minutes')-clock_timestamp()))) else null end
    ) order by v.name,v.id),'[]'::jsonb) into answer
    from bsecurity_private.devices v where v.home_id=h.id and (h.owner_id=uid or v.id=did);
  return jsonb_build_object('device_id',did,'home',jsonb_build_object('id',h.id,'name',h.name,
    'role',case when h.owner_id=uid then 'admin' else 'member' end,'devices',answer));
end;
$function$;
revoke all on function bsecurity_private.home_action(jsonb) from public, anon, authenticated;
grant execute on function bsecurity_private.home_action(jsonb) to authenticated;

create function public.bsecurity_home(payload jsonb) returns jsonb
language sql security invoker set search_path = ''
as 'select bsecurity_private.home_action(payload)';
revoke all on function public.bsecurity_home(jsonb) from public, anon, authenticated;
grant execute on function public.bsecurity_home(jsonb) to authenticated;
comment on function public.bsecurity_home(jsonb) is 'B-Security: session-validated homes, single-use invites and opt-in current location. No route history.';
notify pgrst, 'reload schema';
commit;
