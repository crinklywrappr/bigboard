-- :name add-schedule :! :n
-- :doc saves a schedule definition
insert into schedules (name, story, contact,
                       short_desc, long_desc,
                       trouble, reporter, cron)
values (:name, :story, :contact,
        :short_desc, :long_desc,
        :trouble, :reporter, :cron);

-- :name get-schedules :? :*
-- :doc returns the entire schedule
select * from schedules;

-- :name unschedule :! :n
-- :doc deletes a schedule
delete from schedules
 where name = :name;

-- :name record-trigger :! :n
-- :doc updates the last_triggered timestamp on a schedule
update schedules
   set last_triggered = current_timestamp
 where name = :name;

-- :name record-finished :! :n
-- :doc updates last_finished and exit_code on a schedule
update schedules
   set last_finished = current_timestamp,
       exit_code = :code
 where name = :name;

-- :name get-schedule :? :1
-- :doc returns a single schedule
select * from schedules where name = :name;
