package fruition.core.document.controller;

import fruition.core.document.dto.DocumentUploadResponse;
import fruition.core.document.service.DocumentDirectUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents/uploads")
public class DocumentDirectUploadController {
    private final DocumentDirectUploadService uploads;
    public DocumentDirectUploadController(DocumentDirectUploadService uploads) { this.uploads = uploads; }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<fruition.shared.util.ErrorResponse> error(
            org.springframework.web.server.ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(
                fruition.shared.util.ErrorResponse.of("DOCUMENT_UPLOAD_REJECTED", error.getReason()));
    }

    @PostMapping
    public DocumentDirectUploadService.StartResponse start(@PathVariable("workspace_id") String workspace,
            @AuthenticationPrincipal String user, @RequestBody DocumentDirectUploadService.StartRequest request)
            throws Exception {
        return uploads.start(workspace, user, request);
    }

    @PostMapping("/parts")
    public DocumentDirectUploadService.PartsResponse parts(@PathVariable("workspace_id") String workspace,
            @AuthenticationPrincipal String user, @RequestBody DocumentDirectUploadService.PartsRequest request)
            throws Exception {
        return uploads.partUrls(workspace, user, request);
    }

    @PostMapping("/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@PathVariable("workspace_id") String workspace, @AuthenticationPrincipal String user,
            @RequestBody DocumentDirectUploadService.CompleteRequest request) throws Exception {
        uploads.abort(workspace, user, request);
    }

    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentUploadResponse complete(@PathVariable("workspace_id") String workspace,
            @AuthenticationPrincipal String user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentDirectUploadService.CompleteRequest request) throws Exception {
        return uploads.complete(workspace, user, idempotencyKey, request);
    }
}
