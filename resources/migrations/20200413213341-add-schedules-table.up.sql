CREATE TABLE schedules
  (name NVARCHAR(28),
   story NVARCHAR(255) NOT NULL,
   contact NVARCHAR(50) NOT NULL,
   short_desc NVARCHAR(140) NOT NULL,
   long_desc NTEXT,
   trouble NTEXT,
   reporter NVARCHAR(255) NOT NULL,
   cron VARCHAR(256) NOT NULL,
   created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
   last_triggered TIMESTAMP DEFAULT NULL,
   CONSTRAINT schedules_pk PRIMARY KEY(name),
   CONSTRAINT unique_stories UNIQUE(story));
