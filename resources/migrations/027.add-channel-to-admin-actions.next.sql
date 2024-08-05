alter table admin_action
  add column channel varchar(64) not null default '##programming',
  alter column channel set default null;
