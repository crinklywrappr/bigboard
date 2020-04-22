alter table schedules
  add column last_finished timestamp default null
  after exit_code;
