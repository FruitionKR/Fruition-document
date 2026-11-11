package fruition.core.document.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.imports.DocumentImportException;
import fruition.core.imports.ImportSessionStore;
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

/**
 * 원본 파일(PDF·Markdown·txt)을 임시 객체에 직접 업로드한다. 형식 규칙은 일반 업로드와 같다({@link DocumentService#uploadType}).
 * 확정 시 권한과 객체를 재검증한다. MCP 업로드 도구도 같은 메서드를 그대로 쓴다.
 */
@Service
public class DocumentDirectUploadService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentDirectUploadService.class);
    private final MinioClient storage;
    private final DirectMultipartUpload upload;
    private final StorageProperties properties;
    private final WorkspaceAccessGuard access;
    private final DocumentService documents;
    private final UploadLimitService limits;
    private final ImportSessionStore importSessions;
    private final DocumentAccessPolicy accessPolicy;

    public DocumentDirectUploadService(MinioClient storage, MultipartStorage multipart, StorageProperties properties,
            WorkspaceAccessGuard access, DocumentService documents, UploadLimitService limits,
            ImportSessionStore importSessions, DocumentAccessPolicy accessPolicy,
            @Value("${app.jwt.secret}") String secret) throws Exception {
        this.storage = storage;
        this.accessPolicy = accessPolicy;
        this.limits = limits;
        this.importSessions = importSessions;
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
        return start(workspaceId, userId, request, null);
    }

    /**
     * @param importId {@code X-Fruition-Import-Id}. 있으면 열린 가져오기 세션(본인·같은 워크스페이스)이어야 하고 ticket에
     *                 세션을 묶는다. 확정 때 같은 헤더가 다시 와야 한다. 아니면 400 IMPORT_INVALID다.
     */
    public StartResponse start(String workspaceId, String userId, StartRequest request, String importId)
            throws Exception {
        access.requireMember(workspaceId, userId);
        var session = importSessions.resolve(workspaceId, userId, importId);
        // 큰 파일을 다 올린 뒤에 거절하지 않도록 미리 본다. 폴더 확인과 최종 판정은 확정 때 일반 업로드 경로가 한다.
        accessPolicy.requirePlacement(workspaceId, request.folder_id(), userId);
        String filename = request.filename();
        DocumentService.UploadType type = DocumentService.uploadType(filename);
        if (request.size() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 크기는 0보다 커야 합니다.");
        }
        // 알린 크기로 먼저 거절한다. 실제 크기는 확정 때 조립한 객체로 다시 확인한다.
        requireSize(type, request.size());
        limits.precheck(userId, request.size());
        var started = upload.start(userId, workspaceId, keyPrefix(workspaceId, userId) + UUID.randomUUID(),
                type.mimeType(), request.size(), Map.of("filename", filename,
                        "folder", request.folder_id() == null ? "" : request.folder_id().toString(),
                        "import", session == null ? "" : session.id().toString()));
        log.info("[직접 업로드 시작] operation=document_direct_upload stage=started workspaceId={} userId={} size={} partCount={} importId={} 설명=브라우저가 S3로 조각을 올립니다. 조각 전송은 서버가 관측하지 않습니다.",
                workspaceId, userId, request.size(), started.partCount(), session == null ? null : session.id());
        return new StartResponse(started.ticket(), started.partSize(), started.partCount());
    }

    /** PDF는 원본 크기 상한, Markdown·txt는 일반 업로드와 같은 5MB 상한. */
    private void requireSize(DocumentService.UploadType type, long size) {
        if (type.markdown()) DocumentService.requireMarkdownUploadSize(size);
        else limits.requirePdfSize(size);
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
        log.info("[직접 업로드 중단] operation=document_direct_upload stage=aborted workspaceId={} userId={}", workspaceId, userId);
    }

    public DocumentUploadResponse complete(String workspaceId, String userId, String idempotencyKey,
            CompleteRequest request) throws Exception {
        return complete(workspaceId, userId, idempotencyKey, request, null);
    }

    /**
     * @param importId 시작 때 ticket에 묶은 가져오기 세션과 같아야 한다(둘 다 없거나 둘 다 같은 세션). 가져오기 PDF는
     *                 그 세션 문서로 표시되고 항목 수를 세며 자동 편입하지 않는다. 용량·크기 한도·멱등성은 일반 업로드와 같다.
     */
    public DocumentUploadResponse complete(String workspaceId, String userId, String idempotencyKey,
            CompleteRequest request, String importId) throws Exception {
        Assembled assembled = assemble(workspaceId, userId, request, importId);
        if (assembled.session() != null) {
            return documents.uploadImported(workspaceId, userId, idempotencyKey, assembled.folderId(), assembled.file(), importId);
        }
        return documents.upload(workspaceId, userId, idempotencyKey, assembled.folderId(), assembled.file());
    }

    /**
     * MCP 업로드 확정. 검증·등록은 {@link #complete}와 같고, 서버 자동 편입만 하지 않는다(편입은 사용자 AI가 한다).
     * 가져오기 세션은 쓰지 않는다.
     */
    public DocumentUploadResponse completeWithoutAutoIngest(String workspaceId, String userId, String idempotencyKey,
            CompleteRequest request) throws Exception {
        Assembled assembled = assemble(workspaceId, userId, request, null);
        return documents.uploadWithoutAutoIngest(workspaceId, userId, idempotencyKey, assembled.folderId(), assembled.file());
    }

    private record Assembled(MultipartFile file, UUID folderId, ImportSessionStore.OpenSession session) {}

    private Assembled assemble(String workspaceId, String userId, CompleteRequest request, String importId)
            throws Exception {
        Claims claims = verify(workspaceId, userId, request.ticket());
        String ticketImport = Objects.requireNonNullElse(claims.get("import", String.class), "");
        var session = importSessions.resolve(workspaceId, userId, importId);
        if (!ticketImport.equals(session == null ? "" : session.id().toString())) {
            throw DocumentImportException.invalid();
        }
        String key = claims.get("key", String.class);
        StatObjectResponse stat;
        try {
            stat = upload.assemble(claims);
        } catch (ResponseStatusException e) {
            String code = e.getStatusCode().value() == 409 ? "UPLOAD_PARTS_INCOMPLETE" : "UPLOAD_PARTS_INVALID";
            log.warn("[직접 업로드 거부] operation=document_direct_upload stage=assemble errorCode={} retryable=true workspaceId={} userId={} 원인={}",
                    code, workspaceId, userId, fruition.shared.logging.CommittedLog.reason(code));
            throw e;
        } catch (Exception e) {
            log.error("[직접 업로드 실패] operation=document_direct_upload stage=assemble errorCode=STORAGE_ASSEMBLE_FAILED retryable=true workspaceId={} userId={} errorType={} 원인=S3에 올린 조각을 하나의 파일로 조립하지 못했습니다. 조각 누락과 저장소 장애는 최초 예외로 구분합니다.",
                    workspaceId, userId, e.getClass().getSimpleName());
            throw e;
        }
        String filename = claims.get("filename", String.class);
        DocumentService.UploadType type = DocumentService.uploadType(filename);
        if (stat.size() != ((Number) claims.get("size")).longValue()
                || !type.mimeType().equals(stat.contentType())) {
            log.warn("[직접 업로드 거부] operation=document_direct_upload stage=validation errorCode=UPLOAD_METADATA_MISMATCH retryable=false workspaceId={} userId={} size={} 원인={}",
                    workspaceId, userId, stat.size(), fruition.shared.logging.CommittedLog.reason("UPLOAD_METADATA_MISMATCH"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드한 파일 정보가 일치하지 않습니다.");
        }
        // 한도를 낮추기 전에 발급된 ticket도 실제 크기로 막는다. 용량은 문서 등록 트랜잭션이 잠금 아래에서 확인한다.
        requireSize(type, stat.size());
        String folder = claims.get("folder", String.class);
        // 버전 ID(로컬 비버전 버킷은 ETag)를 고정하여 해시 계산과 원본 저장 사이 덮어쓰기를 차단한다.
        MultipartFile file = new StoredUpload(storage, properties.getBucket(), key, filename, type.mimeType(), stat);
        // Markdown·txt의 UTF-8·크기는 등록 단계(일반 업로드와 같은 readUploadedMarkdown)가 검사한다.
        if (!type.markdown()) try (InputStream input = file.getInputStream()) {
            if (!Arrays.equals(input.readNBytes(5), "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                log.warn("[직접 업로드 거부] operation=document_direct_upload stage=validation errorCode=INVALID_PDF retryable=false workspaceId={} userId={} 원인={}",
                        workspaceId, userId, fruition.shared.logging.CommittedLog.reason("INVALID_PDF"));
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF 파일 내용이 올바르지 않습니다.");
            }
        }
        // 기존 멱등성·폴더 권한·롤백 처리를 그대로 사용한다. 임시 객체는 재시도를 위해 lifecycle로 삭제한다.
        UUID folderId = folder.isEmpty() ? null : UUID.fromString(folder);
        log.debug("[직접 업로드 조립 완료] workspaceId={} userId={} size={} 설명=문서 등록을 이어서 진행합니다. 최종 완료는 문서 업로드 완료 로그로 확인합니다.",
                workspaceId, userId, stat.size());
        return new Assembled(file, folderId, session);
    }

    private record StoredUpload(MinioClient storage, String bucket, String key, String filename, String contentType,
                                StatObjectResponse stat) implements StoredOriginal {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() { return stat.size() == 0; }
        public long getSize() { return stat.size(); }
        public byte[] getBytes() { throw new UnsupportedOperationException("직접 업로드 원본은 스트림으로 처리해야 합니다."); }
        public InputStream getInputStream() throws IOException {
            try {
                return storage.getObject(GetObjectArgs.builder().bucket(bucket).object(key)
                        .versionId(stat.versionId()).matchETag(stat.etag()).build());
            } catch (Exception e) {
                throw new IOException("업로드한 파일을 읽을 수 없습니다.", e);
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
                    .headers(Map.of("Content-Type", contentType)).build());
        }
        public void transferTo(File dest) throws IOException {
            try (InputStream input = getInputStream(); OutputStream output = new FileOutputStream(dest)) {
                input.transferTo(output);
            }
        }
    }
}
