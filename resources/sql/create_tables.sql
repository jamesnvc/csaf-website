BEGIN;

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'membership_status') then
      create type membership_status as enum (
        'new',
        'active',
        'inactive'
      );
    end if;
end$$;

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'membership_site_code') then
      create type membership_site_code as enum (
        'member',
        'contact',
        'board'
      );
    end if;
end$$;

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'membership_class_code') then
      create type membership_class_code as enum (
        'N/A', -- what's the difference between class = '' and class = N/A and class = NULL?
        'unknown', -- use unknown for null & empty string?
        'juniors',
        'lightweight',
        'amateurs', -- who's considered amateur vs open?
        'open',
        'masters',
        'womens',
        'womensmaster'
        );
    end if;
end$$;

create table if not exists members (
  "id" serial primary key,
  -- maybe not really robust, but matching the existing data
  "first_name" text not null,
  "last_name" text not null,
  "address1" text,
  "address2" text,
  "city" text,
  "province" text,
  "country" text default 'Canada',
  "postal_code" text,
  "telephone" text,
  "cell" text,
  "email" text,
  "status" membership_status not null default 'new',
  "master_age" boolean not null default false,
  "master_first_date" date,
  "date_added" timestamp with time zone not null default now(),
  "site_code" membership_site_code not null default 'member',
  "login" text not null,
  "password_hash" text not null, -- TODO: hash
  "class" membership_class_code not null, -- I guess? replace NULL with 'unknown'?
  "region" text,
  "height" text,
  "weight" text,
  "birth_date" date,
  "tartan" text,
  "biography" text,
  "image_file" text,
  "games_list" text,
  "username" text,
  "banned_start_date" date,
  "banned_end_date" date
);

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'game_status') then
      create type game_status as enum (
         'active', 'inactive'
      );
    end if;
end$$;

create table if not exists games (
   "id" serial primary key,
   "name" text not null,
   "website" text,
   "status" game_status default 'active',
   "contact_name" text,
   "contact_email" text,
   "contact_phone" text,
   "city" text,
   "province" text,
   "region" text
);

create table if not exists game_instances (
   "id" serial primary key,
   "game_id" integer not null references games(id),
   "date" date not null,
   "events_list" text
);

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'game_event_type') then
      create type game_event_type as enum (
         'braemar',
         'open',
         'sheaf',
         'caber',
         'lwfd',
         'hwfd',
         'lhmr',
         'hhmr',
         'wob'
      );
    end if;
end$$;

create table if not exists game_member_results (
   id serial primary key,
   member_id integer not null references members(id),
   game_instance integer references game_instances(id),
   event game_event_type not null,
   distance_inches numeric(8,1),
   clock_sixteenths integer,
   weight numeric(8,2),
   score numeric(11,4),

   constraint caber_has_clock check ((distance_inches is not null and event <> 'caber'::game_event_type) or  (clock_sixteenths is not null and event = 'caber'::game_event_type) )
);

create index if not exists game_member_results_member_idx on game_member_results(member_id, game_instance);
create index if not exists game_member_results_game_idx on game_member_results(game_instance, member_id);

COMMIT;
