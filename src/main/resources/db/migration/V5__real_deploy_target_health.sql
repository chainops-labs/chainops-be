alter table deploy_target
  add column health_url varchar(300);

update deploy_target
set
  service_name = 'idwallet-be',
  repository_url = 'https://github.com/idwallet-labs/idwallet-be',
  health_url = 'http://localhost:8080/actuator/health',
  namespace = 'idwallet-local',
  environment = 'local'
where id = '88888888-8888-4888-8888-888888888881';

update deploy_target
set
  service_name = 'credleaf-be',
  repository_url = 'https://github.com/credleaf-labs/credleaf-be',
  health_url = 'http://localhost:8084/actuator/health',
  namespace = 'credleaf-local',
  environment = 'local'
where id = '88888888-8888-4888-8888-888888888882';

insert into deploy_target (id, service_name, repository_url, health_url, namespace, environment, created_at)
values
  ('88888888-8888-4888-8888-888888888881', 'idwallet-be', 'https://github.com/idwallet-labs/idwallet-be', 'http://localhost:8080/actuator/health', 'idwallet-local', 'local', '2026-07-08T09:50:00Z'),
  ('88888888-8888-4888-8888-888888888882', 'credleaf-be', 'https://github.com/credleaf-labs/credleaf-be', 'http://localhost:8084/actuator/health', 'credleaf-local', 'local', '2026-07-08T10:10:00Z')
on conflict (service_name) do update
set
  repository_url = excluded.repository_url,
  health_url = excluded.health_url,
  namespace = excluded.namespace,
  environment = excluded.environment;

alter table deploy_target
  alter column health_url set not null;
