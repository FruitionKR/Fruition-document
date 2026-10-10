-- 데스크톱 앱이 업로드 전에 같은 파일이 이미 있는지 판별하도록 원본 파일 전체의 SHA-256을 둔다.
-- content_hash는 대용량 직접 업로드에서 저장소 ETag 기반 값이라 로컬 파일 해시와 비교할 수 없다.
-- NULL: 아직 계산 전(DocumentOriginalHashWorker가 채운다). 빈 문자열: 원본 객체가 없어 계산할 수 없음.
ALTER TABLE documents ADD COLUMN original_sha256 varchar(64);

CREATE INDEX idx_documents_original_sha256_pending ON documents (uploaded_at)
    WHERE original_sha256 IS NULL AND document_role = 'ORIGINAL' AND source_uri IS NOT NULL AND deleted_at IS NULL;
