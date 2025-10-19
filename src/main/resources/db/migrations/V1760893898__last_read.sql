create table if not exists core.last_read
(
    parasite_id    text                                not null
        constraint room_access_parasites_id_fk
            references core.parasites,
    destination_id text                                not null,
    message_id     uuid                                not null
        constraint last_read_message_id_fk
            references core.messages,
    updated        timestamp default CURRENT_TIMESTAMP not null,
    constraint last_read_destination_pk
        unique (parasite_id, destination_id)
);

-- backfill with current last message id, so not everything is unread
insert into core.last_read
select p_id parasite_id, room_id destination_id, m_id message_id
from (select p.id p_id,
             ra.room_id,
             m.id m_id,
             row_number() over (partition by p.id, ra.room_id order by sent desc) r
      from core.parasites p
               join core.room_access ra on ra.parasite_id = p.id
               join (select id,
                            sender_id,
                            destination_id,
                            destination_type,
                            sent
                     from core.messages
                     where destination_type = 'Room') m
                    on m.destination_id = ra.room_id::text and m.sent < p.last_active
      where ra.in_room) last_message
where r = 1;

with m as (select *, row_number() over (partition by thread_context order by sent desc) r
           from (select id,
                        sent,
                        string_agg(both_p, '|' order by both_p) thread_context,
                        array_agg(both_p order by both_p)       ids
                 from core.messages
                          join lateral (select id                                                      message_id,
                                               unnest(array []::text[] || sender_id || destination_id) both_p
                                        from core.messages
                                        where destination_type = 'Parasite'
                                        group by id) m on message_id = id
                 group by id) thread_messages)
insert into core.last_read
select ids[1] parasite_id, ids[2] destination_id, id message_id
from m
where r = 1
union
select ids[2] parasite_id, ids[1] destination_id, id message_id
from m
where r = 1;
