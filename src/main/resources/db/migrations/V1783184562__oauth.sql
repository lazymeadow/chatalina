alter table core.parasites
    add auth_id varchar default '' not null,
    add reactivation_request timestamp;

comment on column core.parasites.auth_id is 'if empty, this user hasn''t been migrated to ouath yet and can''t log in';
