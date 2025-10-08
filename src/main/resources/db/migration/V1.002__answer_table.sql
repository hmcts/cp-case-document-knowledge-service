
create table answer (
    id bigserial primary key,
    case_id     uuid        not null,
    query_id    uuid        not null,
    version     int         not null,
    answer      text        not null,
    llm_input   text        null,
    doc_id      uuid        null,
    created_at  timestamp not null default current_timestamp
);
