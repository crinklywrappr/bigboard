alter table schedules
  add column exit_code tinyint default null
  after last_triggered;
