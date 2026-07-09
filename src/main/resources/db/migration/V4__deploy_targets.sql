create table deploy_target (
  id uuid primary key,
  service_name varchar(80) not null unique,
  repository_url varchar(300) not null,
  namespace varchar(80) not null,
  environment varchar(40) not null,
  created_at timestamptz not null default now()
);

insert into deploy_target (id, service_name, repository_url, namespace, environment, created_at)
values
  ('88888888-8888-4888-8888-888888888881', 'verifier-api', 'https://github.com/cyjoon68/verifier-api', 'chainops-prod', 'production', '2026-07-08T09:50:00Z'),
  ('88888888-8888-4888-8888-888888888882', 'issuer-api', 'https://github.com/cyjoon68/issuer-api', 'chainops-prod', 'production', '2026-07-08T10:10:00Z');
