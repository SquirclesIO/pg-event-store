create sequence eventStoreVersion_seq as bigint start with 26;

create table events
(
    eventStoreVersion bigint NOT NULL DEFAULT nextval('eventStoreVersion_seq') not null,
    processid         uuid not null,
    aggregateid       uuid not null,
    aggregatename     text not null,
    sentdate          text not null,
    aggregateVersion  int not null,
    payload           text not null
);

alter sequence eventStoreVersion_seq OWNED by events.eventStoreVersion;
alter table events add primary key (eventStoreVersion);
create unique index on events USING btree (aggregateid, aggregateVersion);
create index on events (aggregatename, aggregateid);