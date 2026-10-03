-- Scheduler coordination. Durable asynchronous tasks use the lease fields introduced in V36.
create table background_run_lease (
  business_key varchar(191) character set ascii collate ascii_bin primary key,
  token char(36) character set ascii collate ascii_bin not null,
  completed boolean not null default false,
  lease_until datetime(6) not null
);

-- Speech files are named by execution token; orphan cleanup checks the exact stored reference.
create index idx_question_speech_storage_key on question_speech (storage_key(191));
