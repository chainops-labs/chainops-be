delete from rollback_check;
delete from incident_event;
delete from incident;
delete from deploy_event;

insert into deploy_event (id, service_name, commit_sha, image_tag, status, deployed_at)
values
  ('99999999-9999-4999-8999-999999999981', 'idwallet-be', '445e1a3', 'idwallet-be:develop-445e1a3', 'RUNNING', now()),
  ('99999999-9999-4999-8999-999999999982', 'credleaf-be', '07fbb70', 'credleaf-be:develop-07fbb70', 'RUNNING', now());
