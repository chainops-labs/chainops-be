create table deploy_event (
  id uuid primary key,
  service_name varchar(80) not null,
  commit_sha varchar(80) not null,
  image_tag varchar(120) not null,
  status varchar(24) not null,
  deployed_at timestamptz not null
);

create table incident (
  id uuid primary key,
  title varchar(180) not null,
  severity varchar(24) not null,
  status varchar(24) not null,
  trace_id varchar(120) not null,
  elk_url varchar(500) not null,
  started_at timestamptz not null,
  resolved_at timestamptz
);

create table incident_event (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  type varchar(60) not null,
  note text not null,
  created_at timestamptz not null default now()
);

create table rollback_check (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  item varchar(160) not null,
  checked boolean not null default false
);
