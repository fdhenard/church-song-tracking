-- Initial church song tracking schema.

CREATE TABLE person (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    time_added TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE TABLE church_service (
    id INTEGER PRIMARY KEY,
    service_date TEXT NOT NULL,
    title TEXT NOT NULL,
    holiday TEXT,
    link_youtube TEXT,
    link_vimeo TEXT,
    preacher_id INTEGER NOT NULL REFERENCES person(id),
    song_leader_id INTEGER NOT NULL REFERENCES person(id),
    time_added TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE TABLE song (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    artist TEXT NOT NULL
);

--;;

CREATE TABLE song_variation (
    id INTEGER PRIMARY KEY,
    song_id INTEGER NOT NULL REFERENCES song(id),
    variation_name TEXT NOT NULL,
    example_link TEXT,
    prominent_electric_guitar INTEGER NOT NULL DEFAULT 0
);

--;;

CREATE TABLE song_performance (
    id INTEGER PRIMARY KEY,
    song_variation_id INTEGER NOT NULL REFERENCES song_variation(id),
    church_service_id INTEGER NOT NULL REFERENCES church_service(id),
    order_in_service INTEGER NOT NULL,
    time_in_service_vimeo TEXT NOT NULL
);
