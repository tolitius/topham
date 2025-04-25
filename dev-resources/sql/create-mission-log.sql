create table starship_missions (
  id           bigserial primary key,

  -- dimensional columns -----------------------------
  galaxy       text     not null,          -- required
  star         text,
  planet       text,
  moon         text,
  asteroid     text,
  mission_type text,

  -- non dims ----------------------------------------
  ship         text     not null,
  payload      jsonb    not null,    -- mission intel
  topham       integer  not null,    -- computed bit-mask

  -- when and when -----------------------------------
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
