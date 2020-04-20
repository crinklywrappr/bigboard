CREATE TABLE schedules
  (name NVARCHAR(28) PRIMARY KEY,
   story NVARCHAR(255),
   contact NVARCHAR(50),
   short_desc NVARCHAR(140),
   long_desc NTEXT,
   trouble NTEXT,
   reporter NVARCHAR(255),
   cron VARCHAR(256),
   created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
   last_triggered TIMESTAMP WITH TIME ZONE DEFAULT NULL);
