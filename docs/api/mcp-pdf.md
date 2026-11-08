# MCP 외부 AI PDF 변환

[MCP read boundary](mcp-read.md)

외부 AI(MCP 클라이언트)가 이미 올라온 PDF를 직접 읽고 Markdown으로 바꾼다. Fruition은 권한을 확인한 원본 읽기
주소를 주고, 변환 결과를 웹 승인 계획으로만 받아 저장한다. 내부 모델 변환(`POST /documents/{id}/convert-markdown`,
`ai.convert.command`)은 시작하지 않으며 내부 추론 크레딧을 쓰지 않는다. MCP 승인 자체의 작업 요금(승인 견적의
`price_snapshot`)은 다른 MCP 계획과 같다. 도구 등록·scope 판정·감사·응답 크기 제한은 catalog(`McpToolCatalog`)가 한다.

| 도구 | scope | 감사 대상 |
|---|---|---|
| `get_pdf_source` | `read` | `document_id` |
| `propose_pdf_conversion` | `write_propose` | `source_document_id` |

## `get_pdf_source`

입력 `{document_id}`. 읽을 수 있는 PDF 원본(삭제되지 않았고 원본 객체가 있음)만 돌려주고, 그 밖은 `RESOURCE_NOT_FOUND`다.

응답 `mcp.pdf-source.v1`: `document_id`, `display_name`, `folder_id`, `byte_size`, `source_content_hash`,
`can_convert`, `source_url`, `source_url_expires_at`, `data_boundary`.

- `source_url`은 이 PDF 객체 하나만 GET할 수 있는 10분짜리 저장소 서명 주소다. 웹 원본 보기(`original-url`, aws 모드에서만
  주소를 줌)와 별개로 저장소 모드와 관계없이 서명한다. 주소는 bearer와 같아 로그에 남기지 않는다. local 모드에서
  서버 안 저장소 주소(예: docker의 `http://minio:9000`)가 클라이언트에서 닿지 않으면 `MCP_PDF_SOURCE_ENDPOINT`
  (`app.mcp.pdf-source-endpoint`, 기본 빈 값)에 클라이언트가 닿는 MinIO 주소를 넣는다. 서명은 Host까지 묶으므로 주소를
  바꿔 쓰지 않고 그 주소로 서명한다(region `us-east-1`). aws 모드에서는 설정하지 않는다(설정하면 기동 실패).
- PDF 바이트는 응답에 싣지 않는다(PDF는 최대 100MB, MCP 응답은 `MCP_MAX_RESPONSE_BYTES` 제한). 쪽 렌더링·텍스트 추출도
  하지 않는다. 읽기와 해석은 외부 AI가 한다.
- `source_content_hash`는 `documents.content_hash`(일반 업로드는 원본 SHA-256, 직접 multipart 업로드는 저장소 fingerprint)다.
  변환 제안에 그대로 넘긴다.
- `can_convert`는 웹 변환 요청과 같은 판정(업로더 본인·OWNER·`document.convert`, 원본 폴더 편집 권한)이다.

## `propose_pdf_conversion`

입력 `{idempotency_key, source_document_id, source_content_hash, target_folder_id|null, display_name|null, markdown}`.

- 원본은 `can_convert`여야 하고(아니면 `RESOURCE_NOT_FOUND`), hash가 지금 원본과 같아야 한다(아니면 `SOURCE_CHANGED`).
- `target_folder_id`가 null이면 원본 PDF 폴더, `display_name`이 null이면 원본 이름에서 `.pdf`를 뗀 이름이다.
- 이미지는 웹 변환 결과와 같은 `data:image/(png|jpeg|gif);base64,...`만 받는다. 외부 주소·내부 경로·그 밖의 형식 이미지는
  바꾸지 않고 `INVALID_INPUT`으로 거절한다. 저장 때 asset으로 분리된다.
- 결과는 `create_document` 작업 하나짜리 `propose_plan` 계획으로 같은 승인 흐름에 들어간다. 작업 인자는
  `{display_name, folder_id, content_artifact_id, content_hash, source_document_id, source_content_hash}`로 고정된다.
- 응답 `{run_id, status:"awaiting_approval", review_url, next_step}`. 웹에서 승인하기 전에는 아무것도 저장되지 않는다.

## 승인과 실행

- 승인 견적·승인·실행마다 `McpAgentApprovalService`가 원본을 다시 본다. 권한이 없어졌으면 `PERMISSION_REVOKED`(403),
  hash가 바뀌었으면 409다.
- 실행(`AgentToolService`, MCP 승인 경로만)은 `DocumentService.createConvertedMarkdownInCurrentTransaction`으로
  같은 트랜잭션에서 원본을 다시 확인하고(`document.convert` 판정·hash), data: 이미지를 asset으로 분리한 뒤 변환본
  sanitize(`AiMarkdownSanitizer.sanitizeConverted`)를 거쳐 새 Markdown 문서(`ai_origin=agent`)를 만들고
  `source_document_id`에 원본 PDF를 연결한다. 영수증·멱등 재생은 다른 MCP 작업과 같다.
- 내부 Agent 실행 경로는 `source_document_id`가 있는 `create_document`를 받지 않는다.
- 자동 위키 편입(`auto_ingest_pdf_on_convert`)은 내부 모델 편입이라 시작하지 않는다. 저장 뒤 외부 AI가
  `start_ingest`로 새 문서를 편입한다.
