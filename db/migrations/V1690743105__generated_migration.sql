CREATE SCHEMA IF NOT EXISTS core;
SET search_path TO core;
CREATE TABLE IF NOT EXISTS parasites (id TEXT PRIMARY KEY, email TEXT NOT NULL, active BOOLEAN NOT NULL, last_active TIMESTAMP NULL, settings JSONB NULL, created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS alerts (id uuid PRIMARY KEY, parasite_id TEXT NOT NULL, "data" JSONB NOT NULL, created TIMESTAMP NOT NULL, CONSTRAINT fk_alerts_parasite_id__id FOREIGN KEY (parasite_id) REFERENCES parasites(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE TABLE IF NOT EXISTS parasite_passwords (parasite_id TEXT PRIMARY KEY, "password" TEXT NOT NULL, reset_token TEXT NULL, CONSTRAINT fk_parasite_passwords_parasite_id__id FOREIGN KEY (parasite_id) REFERENCES parasites(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE TABLE IF NOT EXISTS rooms (id uuid PRIMARY KEY, owner_id TEXT NOT NULL, "name" TEXT NOT NULL, created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, CONSTRAINT fk_rooms_owner_id__id FOREIGN KEY (owner_id) REFERENCES parasites(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE TABLE IF NOT EXISTS room_access (parasite_id TEXT, room_id uuid, in_room BOOLEAN NOT NULL, updated TIMESTAMP NOT NULL, CONSTRAINT pk_room_access PRIMARY KEY (parasite_id, room_id), CONSTRAINT fk_room_access_parasite_id__id FOREIGN KEY (parasite_id) REFERENCES parasites(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_room_access_room_id__id FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE RESTRICT ON UPDATE RESTRICT);