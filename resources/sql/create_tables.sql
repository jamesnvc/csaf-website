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
        'board',
        'admin'
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
        'womensmaster',
        'womensyouth'
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
  "login" text not null, -- TODO: this isn't unique...but the data has lots of dupes
  "password_hash" text not null,
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

create table if not exists members_roles (
  "member_id" integer not null references members(id),
  "role" membership_site_code not null,
  primary key ("member_id", "role")
);

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'score_sheet_status') then
      create type score_sheet_status as enum (
        'pending', 'complete', 'approved'
      );
    end if;
end$$;

create table if not exists score_sheets (
  "id" serial primary key,
  "status" score_sheet_status not null default 'pending',
  "games_id" integer references games(id),
  "games_date" date,
  "created_at" timestamp with time zone not null default now(),
  "submitted_by" integer not null references members(id),
  "data" jsonb
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
   "events_list" text,
   "source_sheet_id" integer references score_sheets(id)
);
create index if not exists game_instances_date_year_idx
  on game_instances (extract("year" from "date"));

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
   distance_inches numeric(8,1) not null,
   clock_minutes integer,
   weight numeric(8,2),
   score numeric(11,4),
   class membership_class_code not null,

   constraint caber_has_clock check ((event <> 'caber'::game_event_type and clock_minutes is null) or (event = 'caber'::game_event_type and clock_minutes is not null))
);

create index if not exists game_member_results_member_idx on game_member_results(member_id, game_instance);
create index if not exists game_member_results_game_idx on game_member_results(game_instance, member_id);
create index if not exists game_member_results_instance_idx on game_member_results(game_instance);

create table if not exists game_results_placing (
   "member_id" integer not null references members(id),
   "game_instance_id" integer not null references game_instances(id),
   "placing" integer not null check ("placing" > 0),
   "class" membership_class_code not null,
   primary key(member_id, game_instance_id, "class")
);
create index if not exists game_results_placing_pkey2
  on game_results_placing (game_instance_id, member_id);

do $$
  begin
    if not exists (select 1 from pg_type where typname = 'game_record_status') then
      create type game_record_status as enum (
        'unverified', 'verified', 'new', 'updated', 'inactive'
      );
    end if;
end$$;

create table if not exists event_records (
  "id" serial primary key,
  "canadian" boolean not null, -- in the old db, 125 are "Canada", 69 NULL, 53 "". What does that mean?
  "class" membership_class_code not null,
  "event" game_event_type not null,
  -- in the old DB, records are just athlete names, not linked to member records...
  -- "athlete_id" integer not null references members(id),
  "athlete_name" text not null,
  -- No records for caber
  "distance_inches" numeric(8,1) not null,
  "weight" numeric(8,2) not null,
  "year" integer not null,
  "comment" text,
  "status" game_record_status not null default 'unverified',
  "should_display" boolean not null default false
);

create table if not exists event_record_submissions (
  "id" serial primary key,
  "class" membership_class_code not null,
  "event" game_event_type not null,
  -- in the old DB, records are just athlete names, not linked to member records...
  -- "athlete_id" integer not null references members(id),
  "athlete_name" text not null,
  -- No records for caber
  "distance_inches" numeric(8,1) not null,
  "weight" numeric(8,2) not null,
  "year" integer not null,
  "comment" text,
  "record_approved" boolean -- nullable
);

create table if not exists pages (
   "title" text not null primary key,
   "content" text not null default ''
);

COMMIT;
