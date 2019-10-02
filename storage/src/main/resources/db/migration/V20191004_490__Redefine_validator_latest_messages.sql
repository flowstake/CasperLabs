DROP TABLE validator_latest_messages;

CREATE TABLE validator_latest_messages
(
    validator  BLOB    NOT NULL,
    block_hash BLOB    NOT NULL,
    FOREIGN KEY (block_hash) REFERENCES block_metadata (block_hash)
);