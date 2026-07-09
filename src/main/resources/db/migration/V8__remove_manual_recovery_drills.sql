delete from rollback_check
where incident_id in (
  select id from incident where title like '%recovery drill'
);

delete from incident_event
where incident_id in (
  select id from incident where title like '%recovery drill'
);

delete from incident
where title like '%recovery drill';
