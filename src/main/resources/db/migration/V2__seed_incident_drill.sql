insert into incident (id, title, severity, status, started_at, resolved_at)
values
  ('11111111-1111-4111-8111-111111111111', 'Verifier API latency after deploy', 'SEV2', 'RESOLVED', '2026-07-08T10:20:00Z', '2026-07-08T10:38:00Z'),
  ('22222222-2222-4222-8222-222222222222', 'Argo CD sync drift', 'SEV3', 'RESOLVED', '2026-07-08T09:30:00Z', '2026-07-08T09:39:00Z'),
  ('33333333-3333-4333-8333-333333333333', 'Postgres connection pool saturation', 'SEV2', 'RESOLVED', '2026-07-08T08:15:00Z', '2026-07-08T08:31:00Z'),
  ('44444444-4444-4444-8444-444444444444', 'Prometheus scrape lag on rollout', 'SEV3', 'MITIGATING', '2026-07-08T11:05:00Z', null);
