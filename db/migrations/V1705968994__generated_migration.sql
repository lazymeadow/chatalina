SET search_path TO core;
CREATE TABLE IF NOT EXISTS messages (id uuid PRIMARY KEY, sender_id TEXT NOT NULL, destination_id TEXT NOT NULL, destination_type VARCHAR(48) NOT NULL, "data" JSONB NOT NULL, sent TIMESTAMP NOT NULL, CONSTRAINT fk_messages_sender_id__id FOREIGN KEY (sender_id) REFERENCES parasites(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
