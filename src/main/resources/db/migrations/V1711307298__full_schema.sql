create table if not exists core.parasites
(
    id          text                                not null
        primary key,
    email       text                                not null,
    active      boolean                             not null,
    last_active timestamp default CURRENT_TIMESTAMP,
    settings    jsonb,
    created     timestamp default CURRENT_TIMESTAMP not null,
    updated     timestamp default CURRENT_TIMESTAMP not null
);

create table if not exists core.alerts
(
    id          uuid                                not null
        primary key,
    parasite_id text                                not null
        constraint fk_alerts_parasite_id__id
            references core.parasites
            on update restrict on delete restrict,
    data        jsonb                               not null,
    created     timestamp default CURRENT_TIMESTAMP not null
);

create table if not exists core.parasite_passwords
(
    parasite_id text                                not null
        primary key
        constraint fk_parasite_passwords_parasite_id__id
            references core.parasites
            on update restrict on delete restrict,
    password    text                                not null,
    reset_token text,
    updated     timestamp default CURRENT_TIMESTAMP not null
);

create table if not exists core.messages
(
    id               uuid                                not null
        primary key,
    sender_id        text                                not null
        constraint fk_messages_sender_id__id
            references core.parasites
            on update restrict on delete restrict,
    destination_id   text                                not null,
    destination_type varchar(48)                         not null,
    data             jsonb                               not null,
    sent             timestamp default CURRENT_TIMESTAMP not null
);

create table if not exists core.rooms
(
    id       integer generated always as identity
        constraint rooms_pk
            primary key,
    owner_id text
        constraint rooms_parasites_id_fk
            references core.parasites,
    name     text                                not null,
    created  timestamp default CURRENT_TIMESTAMP not null,
    updated  timestamp default CURRENT_TIMESTAMP not null
);

create table if not exists core.room_access
(
    parasite_id text                                not null
        constraint room_access_parasites_id_fk
            references core.parasites,
    room_id     integer                             not null
        constraint room_access_rooms_id_fk
            references core.rooms,
    in_room     boolean   default false             not null,
    updated     timestamp default CURRENT_TIMESTAMP not null,
    constraint room_access_pk
        unique (parasite_id, room_id)
);

create table if not exists core.room_invitations
(
    room_id    integer                             not null
        constraint room_invitations_rooms_id_fk
            references core.rooms,
    invitee_id text                                not null
        constraint room_invitations_parasites_id_fk
            references core.parasites,
    sender_id  text                                not null
        constraint room_invitations_parasites_id_fk_2
            references core.parasites,
    created    timestamp default CURRENT_TIMESTAMP not null,
    constraint room_invitations_pk
        primary key (invitee_id, room_id, sender_id)
);
