package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.upload.UploadLimitService;
import fruition.shared.util.StorageProperties;
import fruition.shared.util.DirectMultipartUpload;
import fruition.shared.util.MultipartStorage;
import io.jsonwebtoken.Claims;
import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 원본 PDF는 임시 객체에 직접 업로드한다. 확정 시 권한과 객체를 재검증한다. */
@Service
public class DocumentDirectUploadService {
    private final MinioClient storage;
    private final DirectMultipartUpload upload;
    private final StorageProperties properties;
    private final WorkspaceAccessGuard access;
    private final DocumentService documents;
    private final UploadLimitService limits;

    public DocumentDirectUploadService(MinioClient storage, MultipartStorage multipart, StorageProperties properties,
            WorkspaceAccessGuard access, DocumentService documents, UploadLimitService limits,
            @Value("${app.jwt.secret}") String secret) throws Exception {
        this.storage = storage;
        this.limits = limits;
        this.properties = properties;
        this.access = access;
        this.documents = documents;
        this.upload = new DirectMultipartUpload(storage, multipart, properties, secret, "document-upload-ticket",
                java.util.regex.Pattern.compile("tmp/document-uploads/[^/]+/[^/]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    public record StartRequest(String filename, long size, UUID folder_id) {}
    public record StartResponse(String ticket, long part_size, int part_count) {}
    public record PartsRequest(String ticket, int first_part, int count) {}
    public record PartUrl(int part_number, String url) {}
    public record PartsResponse(List<PartUrl> parts) {}
    public record CompleteRequest(String ticket) {}

    public StartResponse start(String workspaceId, String userId, StartRequest request) throws Exception {
        access.requireMember(workspaceId, userId);
        String filename = request.filename();
        if (filename == null || filename.length() > 255 || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || filename.contains("/") || filename.contains("\\")
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 PDF 파일명이 필요합니다.");
        }
        if (request.size() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 크기는 0보다 커야 합니다.");
        }
        // 알린 크기로 먼저 거절한다. 실제 크기는 확정 때 조립한 객체로 다시 확인한다.
        limits.requirePdfSize(request.size());
        limits.precheck(userId, request.size());
        var started = upload.start(userId, workspaceId, keyPrefix(workspaceId, userId) + UUID.randomUUID(),
                "application/pdf", request.size(), Map.of("filename", filename,
                        "folder", request.folder_id() == null ? "" : request.folder_id().toString()));
        return new StartResponse(started.ticket(), started.partSize(), started.partCount());
    }

    /** 임시 원본은 발급 대상 경로에 둔다. ticket이 같은 형식의 남의 임시 객체를 가리키지 못하게 확정 때 대조한다. */
    private static String keyPrefix(String workspaceId, String userId) {
        return "tmp/document-uploads/" + workspaceId + "/" + userId + "/";
    }

    private Claims verify(String workspaceId, String userId, String ticket) {
        Claims claims = upload.verify(workspaceId, userId, ticket);
        if (!claims.get("key", String.class).startsWith(keyPrefix(workspaceId, userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 사용자의 업로드를 사용할 수 없습니다.");
        }
        access.requireMember(workspaceId, userId);
        return claims;
    }

    public PartsResponse partUrls(String workspaceId, String userId, PartsRequest request) throws Exception {
        Claims claims = verify(workspaceId, userId, request.ticket());
        List<String> urls = upload.partUrls(claims, request.first_part(), request.count());
        List<PartUrl> parts = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            parts.add(new PartUrl(request.first_part() + index, urls.get(index)));
        }
        return new PartsResponse(parts);
    }

    public void abort(String workspaceId, String userId, CompleteRequest request) throws Exception {
        upload.abort(verify(workspaceId, userId, request.ticket()));
    }

    public DocumentUploadResponse complete(String workspaceId, String userId, String idempotencyKey,
            CompleteRequest request) throws Exception {
        Claims claims = verify(workspaceId, userId, request.ticket());
        String key = claims.get("key", String.class);
        StatObjectResponse stat = upload.assemble(claims);
        if (stat.size() != ((Number) claims.get("size")).longValue()
                || !"application/pdf".equals(stat.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드한 파일 정보가 일치하지 않습니다.");
        }
        // 한도를 낮추기 전에 발급된 ticket도 실제 크기로 막는다. 용량은 문서 등록 트랜잭션이 잠금 아래에서 확인한다.
        limits.requirePdfSize(stat.size());
        String filename = claims.get("filename", String.class);
        String folder = claims.get("folder", String.class);
        // 버전 ID(로컬 비버전 버킷은 ETag)를 고정하여 해시 계산과 원본 저장 사이 덮어쓰기를 차단한다.
        MultipartFile file = new StoredPdf(storage, properties.getBucket(), key, filename, stat);
        try (InputStream input = file.getInputStream()) {
            if (!Arrays.equals(input.readNBytes(5), "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF 파일 내용이 올바르지 않습니다.");
            }
        }
        // 기존 멱등성·폴더 권한·롤백 처리를 그대로 사용한다. 임시 객체는 재시도를 위해 lifecycle로 삭제한다.
        return documents.upload(workspaceId, userId, idempotencyKey,
                folder.isEmpty() ? null : UUID.fromString(folder), file);
    }

    private record StoredPdf(MinioClient storage, String bucket, String key, String filename,
                             StatObjectResponse stat) implements StoredOriginal {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/pdf"; }
        public boolean isEmpty() { return stat.size() == 0; }
        public long getSize() { return stat.size(); }
        public byte[] getBytes() { throw new UnsupportedOperationException("PDF는 스트림으로 처리해야 합니다."); }
        public InputStream getInputStream() throws IOException {
            try {
                return storage.getObject(GetObjectArgs.builder().bucket(bucket).object(key)
                        .versionId(stat.versionId()).matchETag(stat.etag()).build());
            } catch (Exception e) {
                throw new IOException("업로드한 PDF를 읽을 수 없습니다.", e);
            }
        }
        public String storageFingerprint() {
            try {
                // 원본 PDF는 S3 multipart ETag와 길이로 식별한다. 편집 Markdown은 기존 본문 SHA-256을 유지한다.
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        (stat.etag() + ":" + stat.size()).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public void copyTo(String targetKey) throws Exception {
            // S3 내부 multipart copy: 대용량 원본을 애플리케이션으로 내려받지 않는다.
            storage.composeObject(ComposeObjectArgs.builder().bucket(bucket).object(targetKey)
                    .sources(List.of(ComposeSource.builder().bucket(bucket).object(key)
                            .versionId(stat.versionId()).matchETag(stat.etag()).build()))
                    .headers(Map.of("Content-Type", "application/pdf")).build());
        }
        public void transferTo(File dest) throws IOException {
            try (InputStream input = getInputStream(); OutputStream output = new FileOutputStream(dest)) {
                input.transferTo(output);
            }
        }
    }
}
