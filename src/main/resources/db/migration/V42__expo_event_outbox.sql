-- 박람회 변경 이벤트(expo.expo.changed) 아웃박스. sequence가 이벤트 version이다(docs/expo-change-events.md).
CREATE TABLE tb_expo_event_outbox (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    expo_id VARCHAR(36) NOT NULL,
    deleted BOOLEAN NOT NULL,
    snapshot TEXT,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publish_token VARCHAR(36),
    publish_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CHECK (deleted = (snapshot IS NULL))
);
CREATE INDEX expo_event_pending ON tb_expo_event_outbox (sequence) WHERE state = 'PENDING';
CREATE INDEX expo_event_pending_expo ON tb_expo_event_outbox (expo_id, sequence) WHERE state = 'PENDING';
CREATE INDEX expo_event_published ON tb_expo_event_outbox (published_at) WHERE state = 'PUBLISHED';
