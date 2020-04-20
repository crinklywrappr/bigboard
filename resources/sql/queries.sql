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
