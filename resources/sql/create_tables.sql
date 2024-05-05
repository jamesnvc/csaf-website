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
  "nameFirst" text not null,
  "nameLast" text not null,
  "address1" text,
  "address2" text,
  "city" text,
  "province" text,
  "country" text default 'Canada',
  "postalCode" text,
  "telephone" text,
  "cell" text,
  "email" text,
  "status" membership_status not null default 'new',
  "master_age" boolean not null default false,
  "master_firstDate" date,
  "dateAdded" timestamp with time zone not null default now(),
  "siteCode" membership_site_code not null default 'member',
  "login" text not null,
  "password_hash" text not null, -- TODO: hash
  "class" membership_class_code not null, -- I guess? replace NULL with 'unknown'?
  "region" text,
  "height" text,
  "weight" text,
  "birthDate" date,
  "tartan" text,
  "biography" text,
  "imageFile" text,
  "gamesList" text,
  "username" text,
  "banned_startDate" date,
  "banned_endDate" date
);

COMMIT;
