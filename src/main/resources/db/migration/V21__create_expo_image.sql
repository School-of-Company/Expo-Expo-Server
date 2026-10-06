CREATE TABLE tb_expo_image (
    id VARCHAR(36) PRIMARY KEY,
    storage_provider VARCHAR(30) NOT NULL,
    object_key VARCHAR(100) NOT NULL UNIQUE,
    public_url TEXT NOT NULL UNIQUE,
    content_type VARCHAR(30) NOT NULL,
    uploaded_by VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expo_id VARCHAR(36) REFERENCES tb_expo (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attached_at TIMESTAMP WITH TIME ZONE,
    detached_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_expo_image_cleanup ON tb_expo_image (status, created_at, detached_at);
