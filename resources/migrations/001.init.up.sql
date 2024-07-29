create table hostname
(
  /* Immutable. */
  id serial primary key,
  hostname varchar(64) not null unique
);

create table nick_association
(
  /* Immutable. */
  id serial primary key,
  nick varchar(16),
  hostname_id int references hostname(id) not null,
  unique(nick, hostname_id),

  first_seen bigint not null,

  /* Mutable. */
  last_seen bigint not null
);

create table account_association
(
  /* Immutable. */
  id serial primary key,
  account varchar(16),
  hostname_id int references hostname(id) not null,
  unique(account, hostname_id),

  first_seen bigint not null,

  /* Mutable. */
  last_seen bigint not null
);

create type admin_action_type as enum('note', 'warn', 'quiet', 'ban', 'kick');
create table admin_action
(
  /* Immutable. */
  id serial primary key,
  target_id int references hostname(id) not null,
  admin_account varchar(16) not null,
  action admin_action_type not null,
  reason text,
  seen bigint not null
);
create index admin_action_target_id_index on admin_action (target_id);

/* For inspecting various stats about our usage. */
create extension pg_stat_statements;
