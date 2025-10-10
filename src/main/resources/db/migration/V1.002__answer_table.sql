
create table answer (
    answer_id bigserial primary key,
    case_id     uuid        not null,
    query_id    uuid        not null,
    version     int         not null,
    answer      text        not null,
    llm_input   text        null,
    doc_id      uuid        null,
    created_at  timestamp not null default current_timestamp
);

// we need to add the constraints and indexes for our table
// we can easily test the constraints in our repository test