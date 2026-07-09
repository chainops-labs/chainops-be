insert into deploy_event (id, service_name, commit_sha, image_tag, status, deployed_at)
values
  ('55555555-5555-4555-8555-555555555555', 'verifier-api', '8f3a2c1', 'verifier-api:2026.07.08', 'SYNCED', '2026-07-08T09:58:00Z'),
  ('66666666-6666-4666-8666-666666666666', 'issuer-api', 'f1c9b77', 'issuer-api:2026.07.08', 'DEGRADED', '2026-07-08T10:18:00Z');

insert into rollback_check (id, incident_id, item, checked)
values
  ('77777777-7777-4777-8777-777777777771', '44444444-4444-4444-8444-444444444444', '직전 정상 image tag 확인', true),
  ('77777777-7777-4777-8777-777777777772', '44444444-4444-4444-8444-444444444444', 'Argo CD sync 상태 확인', false),
  ('77777777-7777-4777-8777-777777777773', '44444444-4444-4444-8444-444444444444', 'ELK trace link로 에러 범위 확인', false);
