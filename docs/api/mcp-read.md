# MCP read boundary

The document service owns the protected MCP resource at `POST /mcp` and its RFC 9728 metadata at
`GET /.well-known/oauth-protected-resource/mcp` (`scopes_supported`는 Access가 발급하는
`["read","write_propose","wiki_write"]`와 같다. 광고만 할 뿐 자동 부여는 없다). The boundary is disabled unless `MCP_ENABLED=true`;
enabling it also requires canonical HTTPS resource/authorization/AI URIs, an exact Origin allowlist,
RSA verification keys, a cursor secret, and every positive deployment limit in `application.properties`.
`MCP_REVIEW_ORIGIN` is an explicit canonical HTTPS frontend origin used by proposal review links and read source links.
Leaving it blank keeps the four read tools available with `source_url: null`, but makes proposal invocation fail closed.

The transport is the stateless servlet transport from the official Java SDK 2.1.0. It does not issue or
accept MCP session IDs. Every HTTP request verifies an RS256 MCP-audience token, reads at most 4 MiB after
decompression to select the required `read`, `write_propose` (`propose_document_edit`, `propose_plan`,
`start_pdf_conversion`, `submit_conversion_window`, `finalize_pdf_conversion`, `propose_skill_definition`,
`start_document_upload`, `get_upload_part_urls`, `complete_document_upload`) or
`wiki_write` (six wiki tools) scope, and calls Access's current
`POST /internal/mcp/grants/{grant_id}/authorize` contract before SDK protocol parsing. A missing Origin is
allowed for native clients, while any present Origin must exactly match the configured canonical list.
Forwarding headers are ignored; the servlet request itself must already represent the canonical HTTPS host.

## 공개 카탈로그 (`mcp-tools.v13`)

lint 대상 값 추가(2026-11-12, `mcp-tools.v12` → `mcp-tools.v13`): `get_wiki_page` 응답에 `lint_target`
(`page_id`, `version`, `content_hash`)을 추가했다. pipeline lint가 대조하는 값과 같은 기준이다. `version`은
`updated_at`의 epoch 마이크로초, `content_hash`는 `markdown_uri` 원문 객체(없으면 빈 문자열)의 SHA-256이다.
응답 `content_hash`는 무해화한 `body`의 hash라 lint 대상으로 쓰면 `TARGET_CHANGED`가 난다. `search_wiki`의
`content_hash`도 검색 단위 hash라 lint 대상이 아니다. `submit_lint_changes`·`get_wiki_page` 설명이 이를 안내한다.

수수료 안내·면제 표시(2026-11-12, `mcp-tools.v11` → `mcp-tools.v12`): 도구 목록은 그대로다.
- 서버 `instructions`(`McpTransportConfig.INSTRUCTIONS`)가 읽기·검색·문서 수정 제안은 무료이고, 위키 편입·lint는
  클라이언트 LLM으로 돌지만 Fruition 수수료가 따로 붙는다고 연결 시점에 알린다.
- 같은 안내(`McpToolCatalog.FEE_NOTICE`)를 `start_ingest`·`finalize_ingest`·`submit_lint_changes` 설명에 붙인다.
  승인은 웹 승인 화면의 최대 수수료를 예약하며, 면제 아닌 계정의 가용 잔액이 그보다 적으면 거절된다. 수수료는 바꾸지 않았다.
- `get_credit_balance` 응답에 `limit_exempt`를 추가했다(아래 크레딧 조회 참고).

계층 조회 추가(2026-11-12, `mcp-tools.v10` → `mcp-tools.v11`): 외부 AI가 폴더 정리 계획의
대상 ID·현재 위치·메타데이터 버전을 확인할 수 있도록 아래 5개 `read` 도구를 추가했다.

| 도구 | 입력(모든 필드 필수) | 출력 |
| --- | --- | --- |
| `list_root_items` | `cursor`, `page_size` | 최상위 폴더·문서 `items` |
| `list_folder_children` | `folder_id`, `cursor`, `page_size` | 해당 폴더의 직계 `items` |
| `get_document_metadata` | `document_id` | 현재 배치 `item` |
| `search_hierarchy` | `query`, `cursor`, `page_size` | 이름 검색 `items` (폴더와 문서) |
| `get_breadcrumb` | `folder_id`, `document_id` (정확히 하나만 값, 나머지는 null) | 최상위부터 대상까지 순서대로 `items` |

첫 페이지의 `cursor`는 null이다. 목록·검색은 `next_cursor`가 null이 될 때까지 읽으며
`truncated`는 다음 페이지 존재 여부다. 모든 응답은 `workspace_id`, `schema_version`,
`data_boundary=UNTRUSTED_WORKSPACE_CONTENT`를 포함한다. 목록 schema는 `mcp.hierarchy-list.v1`,
검색은 `mcp.hierarchy-search.v1`, 메타데이터는 `mcp.document-metadata.v1`, 경로는 `mcp.breadcrumb.v1`이다.
목록은 `parent_id`(최상위 null), 검색은 정규화된 `query`를 추가로 반환한다.
각 item은 `{id,type,name,parent_id,current_version,position}`이며 type은 `folder|document`다.

`current_version`은 **이동·이름 변경용 documents/folders 메타데이터 버전**이다. `get_document`의
본문 편집 revision과 혼동하지 않는다. `parent_id`를 `propose_plan.source_parent_id`에 사용한다.
원본 PDF도 본문 로딩·변환 없이 배치 메타데이터를 조회할 수 있다.

공통 MCP 보안 필터가 매 호출 grant·현재 멤버십을 검증하며 기존 `DocumentAccessPolicy.Viewer`로
읽기 권한을 검사한다. 다른 workspace·삭제·읽기 불가 대상은 `RESOURCE_NOT_FOUND`다.
목록/검색은 기존 `maxCandidates` 이하만 DB에서 가져오고, 후보 합계 초과 시 `RESPONSE_LIMIT_EXCEEDED`로
거절한다(일부 결과를 전체 결과처럼 반환하지 않는다). `page_size`는 1..`maxPageSize`다.
커서는 사용자·workspace·grant·client·정책 세대·도구·부모·검색어·페이지 크기에 묶이고,
항목/배치/버전 변경 시 `STALE_CURSOR`다. 경로는 최대 64개 폴더이며 순환은 거절한다.
이름은 기존 sanitizer를 거치며 응답 바이트 상한과 감사 로깅은 공통 카탈로그 wrapper를 재사용한다.

내부 도구 대조 결과: 내부 읽기 6개 중 5개는 위 도구로, `get_document_content`는 기존 `get_document`로
제공된다. 폴더·문서 생성/이동/이름 변경/본문 편집 7개 작업은 기존 `propose_plan`으로,
`publish_skill_definition`은 `propose_skill_definition`으로 제공된다.
내부 실행기의 `delete_folder`·`delete_document`는 외부 MCP 계획에서 지원하지 않는다.
삭제는 현재 Skill 허용 작업에도 없으며 이번 읽기 도구 변경에서 외부 승인 계약을 확장하지 않는다.


크레딧 조회 추가(2026-11-11, `mcp-tools.v9` → `mcp-tools.v10`):

- `get_credit_balance {}`는 인증된 **사용자 본인**의 잔액을 조회한다. 워크스페이스별 잔액이 아니다.
- `read` scope와 기존 MCP access 검증을 사용한다. 사용자·워크스페이스 ID 등 인자는 허용하지 않는다.
- 웹의 `CreditService.credits(userId)` 조회를 재사용하되 원장 내역·실행 ID·사용자 ID는 반환하지 않는다.
- `balance_krw_milli`는 총 잔액, `reserved_krw_milli`는 실행 중 예약액,
  `available_krw_milli`는 잔액에서 예약을 뺀 현재 가용액이다. 금액은 정수 milli-KRW이며 1000으로 나누면 원 단위다.
  음수도 그대로 반환한다. 응답은 조회 시점 값이며 다음 작업의 승인 가능 여부를 보장하지 않는다.
- 계정이 없으면 0을 반환하며 계정을 생성하지 않는다. 조회 실패는 0으로 숨기지 않고 기존 `DEPENDENCY_UNAVAILABLE` 오류로 처리한다.
  조회는 예약·차감·정산을 수행하지 않는다.
- `limit_exempt`(2026-11-12 추가)가 true면 잔액 제한을 받지 않는 기존 가입자다. 도구 설명은 이때 금액이나 음수 잔액을
  빚처럼 전하지 말고 크레딧이 적용되지 않는다고 안내하게 한다. 계정이 없으면 false다.

```json
{
  "schema_version": "mcp.credit-balance.v1",
  "currency": "KRW",
  "balance_krw_milli": 123450,
  "reserved_krw_milli": 23450,
  "available_krw_milli": 100000,
  "limit_exempt": false
}
```

출처 링크 변경 기록(2026-11-11, `mcp-tools.v8` → `mcp-tools.v9`):

- 공개 읽기 도구 네 개의 응답에 서버가 인가한 `workspace_id`를 추가한다.
- 위키 페이지는 내부 지식이며 사용자가 여는 출처가 아니다. `search_wiki` 결과와 `get_wiki_page`에는
  페이지 URL을 제공하지 않는다. 위키 기반 답변은 `search_wiki.results[].evidence[]`의
  `{document_id, block_id, source_url}`을 사용해 원문 블록을 인용한다.
- 근거 `source_url`은 `<MCP_REVIEW_ORIGIN>/home?doc=<encoded document_id>&blocks=<encoded block_id>`다.
  프런트엔드는 `blocks`를 기존 원문 미리보기의 하이라이트·스크롤 경로에 전달한다.
  현재 본문에서 블록을 못 찾거나 문서가 바뀐 경우에도 기존 위치 검증·안내를 사용한다.
- `find_documents.results[]`와 `get_document`의 `source_url`은 문서 전체를 여는 `/home?doc=...`다.
  이 도구들의 block 번호를 위키 영구 source block ID로 추측해 링크를 만들지 않는다.
- `get_wiki_page`는 맥락 조회용이며, 도구 설명은 인용 전에 `search_wiki`에서 일치하는 원문 근거를
  얻도록 명시한다. 근거 없는 페이지에는 출처 URL을 만들어 붙이지 않는다.
- 모든 `source_url`은 필수 nullable string이다. origin 미설정이면 null로 반환하고 문서·블록 ID로 인용한다.
  정적 도구 설명은 주장 옆에 반환된 원문 링크로 Markdown 인용을 달고 위키 페이지 링크는 만들지 않도록 지시한다.
- URL은 인가·무해화가 끝난 결과에 document 서비스의 공개 MCP 경계에서 붙인다.
  AI 내부 DTO, 본문, `content_hash`, 커서 및 권한 판정은 바꾸지 않으며 링크를 붙인 전체 응답에도 크기 제한을 적용한다.
- 기존 `/home` 링크는 브라우저가 선택한 워크스페이스에서 열린다. 도구 설명에 반환된 `workspace_id`와
  다르면 먼저 해당 워크스페이스를 선택하도록 명시한다. 링크는 공개 공유 URL이나 권한 우회 수단이 아니다.

공개 output schema에 위 필드를 추가하고 카탈로그 버전을 올린다. 기존 `schema_version` v1은 유지하며,
AI↔document 내부 strict 계약에는 이 표시용 필드를 보내지 않는다.

`McpToolCatalog.tools()`는 2026-11-12부터 다음 30개 도구를 이 순서로 등록한다: 읽기 4개(`search_wiki`,
`find_documents`, `get_document`, `get_wiki_page`), 제안 2개(`propose_document_edit`, `propose_plan`), 위키 6개
(`start_ingest`, `resume_ingest`, `get_window`, `submit_window`, `finalize_ingest`, `submit_lint_changes`), 계층 읽기 5개
(`list_root_items`, `list_folder_children`, `get_document_metadata`, `search_hierarchy`, `get_breadcrumb`), Skill 3개
(`list_skills`, `get_skill` `read`, `propose_skill_definition` `write_propose`, 계약은 [mcp-skills.md](mcp-skills.md)), 회의 읽기 2개(`list_meetings`,
`get_meeting_transcript`, `read` scope, 아래 절), PDF 변환 4개(`start_pdf_conversion`·`submit_conversion_window`·`finalize_pdf_conversion` `write_propose`,
`get_conversion_window` `read`, AnyDoc 전처리 결과의 복원만 외부 AI가 맡는다. 계약은 [mcp-pdf.md](mcp-pdf.md)), 문서 업로드 3개(`start_document_upload`·`get_upload_part_urls`·`complete_document_upload` `write_propose`, 아래 절), 본인 크레딧 읽기 1개(`get_credit_balance`, `read`). 다른 MCP 도구 bean(`McpHierarchyTools`, `McpSkillTools`, `McpMeetingTools`, `McpPdfTools`, `McpDocumentUploadTools`)은
`tools()`와 `invoke(identity, tool, args)`만 제공하고, 카탈로그가 같은 wrapper로 감싼다. 별도 enable
플래그나 숨은 등록 경로는 없다. 제안·위키 도구는 승인 대기 run/draft와 `review_url`만 만들며, 승인·크레딧 예약·실행·정산은
아래 각 절의 웹 승인 경로에서만 일어난다. 도구 인자로 승인이나 실행을 대신할 수 없다. 모든 도구는 같은 spec wrapper로
감사 로그·오류 코드·응답 크기 제한을 공유한다. 이 카탈로그는 Document `f9882a4`와 AI `6bc6a6d`의 semantic merge 계약을
전제로 하므로 두 서비스를 함께 배포해야 한다.

schema 변경 기록(2026-11-11, `mcp-tools.v4` → `mcp-tools.v6`; `v5`는 병행 중인 MCP 문서 업로드 변경이 쓴다):
PDF 변환을 웹 변환 코드 경로에 맞췄다([mcp-pdf.md](mcp-pdf.md)). `finalize_pdf_conversion` 입력에서 `target_folder_id`를 뺐다
(변환본은 원본 PDF 폴더). `submit_conversion_window` 출력에 `new_window_ordinals`를 더했고 창마다 한 번만 제출한다(`max_attempts` 1,
떨어진 블록은 재시도 창). `propose_plan` 인자 스키마에서 원본 연결 `create_document` 변형을 뺐다.

정적 설명 변경 기록(2026-11-11, `mcp-tools.v3` → `mcp-tools.v4`, schema 변경 없음): `review_url`을 돌려주는 6개 도구
(`propose_document_edit`, `propose_plan`, `finalize_ingest`, `submit_lint_changes`, `propose_skill_definition`,
`finalize_pdf_conversion`)의 설명에 공통 문구 `McpToolCatalog.REVIEW_APPROVAL`(사람이 승인하기 전에는 저장되지 않으니
`review_url`을 바로 사용자에게 열어 주고 승인이 필요하다고 알릴 것)을 붙였다. `submit_window`·`submit_lint_changes` 설명에는
AI `mcp_wiki_draft` 검증 규칙(한 statement 안에서 같은 `block_id` 근거는 한 번만, quote는 그 블록의 정확한 부분 문자열,
statement 단어 대부분이 quote에 있어야 하므로 번역·의역 금지, 앞뒤 공백·URL·이미지·안전하지 않은 markup·지시문 형태 금지,
길이·개수 상한)을 적었다. 임계값 수치는 설정값이라 적지 않으며, 설명에는 workspace·사용자 내용을 넣지 않는다.

도구 추가 기록(2026-11-11, `mcp-tools.v6` → `mcp-tools.v7`; v6 기록이 예약한 `v5`는 쓰지 않았다): 웹 직접 업로드 API를 감싸는 문서 업로드 3개 도구를 더했다(아래 절, ADR Fruition-ai 0048).

정적 설명 변경 기록(2026-11-11, `mcp-tools.v7` → `mcp-tools.v8`, schema 변경 없음): `start_ingest` 설명에 현재 문서 내용이 이미 위키에 편입돼 있으면 `ALREADY_INGESTED`로 거절되며 재시도하지 말고 문서를 고친 뒤 다시 시작한다는 안내를 더했다. AI는 마지막 성공 편입(웹·MCP)의 입력 Markdown 해시(`source_block_snapshots`)가 현재 문서 해시와 같으면 run을 만들지 않고 `409 ALREADY_INGESTED`를 돌려주며, `McpWikiDraftClient`가 이 코드를 그대로 전달한다.

The read tools are:

- `search_wiki` → AI `POST /internal/mcp/wiki/search`, with a server-created actor and only
  `X-Internal-Token`; each stored document/block locator is shape-checked and its document is rechecked for
  current workspace/read access. A malformed locator fails the whole search with `DEPENDENCY_UNAVAILABLE`, but a
  result whose evidence includes a document the caller cannot read (or that no longer exists) is dropped from the
  page instead of failing the search; its title, snippet, and locators are never returned. The page may then hold
  fewer than `page_size` results while `next_cursor` stays valid. AI ranks candidates with the web evidence search
  path (Fruition-ai `docs/api/mcp-wiki.md`). Published historical evidence is not invalidated by a later source edit.
- `find_documents` → the web document-list search (`DocumentRepository.searchVisibleByWorkspaceId`, the same query
  as `GET /documents?query=`) bounded by the candidate limit, then document-policy filtering and a stateless
  owner-bound cursor. It matches only title or filename substrings, never document bodies, so `snippet` is the title
  and `evidence_block_ids` is empty. The tool description says so and points content queries to `search_wiki`
  (ingested documents only) or `get_document`.
- `get_document` → bounded block-boundary chunks from the document-owned current edit state (or the immutable
  chat-export pipeline input), tied to that body version, source hash, and sanitized presentation snapshot. It does
  not require a wiki ingest snapshot. Since 2026-11-12 it splits the raw Markdown first and sanitizes each block, so
  `block_id` is `B0001`… with the same ordinals as `McpWikiSourceService`; `submit_lint_changes` evidence accepts these
  IDs directly. Earlier responses used `mcp_<hash>_<n>`, which lint rejected with `INVALID_EVIDENCE`.
  Because blocks are sanitized one at a time, a reference link (`[t][x]`) whose definition sits in another block is
  not rewritten; external URLs in link reference definitions are therefore replaced with `label: 외부 링크(host)` text.
- `get_wiki_page` → the web page detail (`WikiService.read`, the same path as
  `GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}`), so the body is resolved in the web order
  (AI body → latest `wiki_page_versions.markdown` → stored object). The MCP boundary adds the ADR 0046 contract:
  a stored but empty body is returned as an empty `body` (the web detail still omits `markdown`); after the body is
  resolved the page metadata (`page_type`, `title`, `markdown_uri`, `status`, `updated_at`, via AI
  `POST /wiki/pages/lookup`) and the latest revision are read again, and any difference or a non-active page is
  retryable `PAGE_CHANGED`; the whole read must finish within `app.mcp.wiki-page-deadline-ms`
  (`MCP_WIKI_PAGE_DEADLINE_MS`, default 5000) or it is `DEPENDENCY_UNAVAILABLE`. It also applies the body size limit,
  sanitizing, and the eight `mcp.wiki-page.v1` fields with a body hash over the exact UTF-8 body returned by the tool
  plus `lint_target` (stored-object hash and `updated_at` micros, the values lint compares).
  A missing page is `PAGE_NOT_FOUND`; a page whose body the web cannot resolve is `DEPENDENCY_UNAVAILABLE`.

Request bodies are capped at 4 MiB after gzip decompression. Candidate counts, source block counts/bytes,
page sizes, response bytes, cursor bytes, and document chunk blocks/bytes use explicit deployment settings.
Required fields and blocks are never silently truncated. Audit events contain only actor/grant/tool/target,
result/error, timestamp, ID, and origin; query, snippets, Markdown, and tokens are excluded.

MCP 도구 호출 로그: 감사 JSON(`fruition.mcp.audit`)은 형식을 그대로 두고 콘솔에만 남긴다(INFO는 PostHog로 보내지 않는다).
같은 호출마다 `fruition.mcp.activity`가 `[MCP 도구 호출 성공|실패] operation=mcp_tool stage=completed` 한 줄을 남기고,
`PostHogLogPolicy` 허용 필드로 PostHog Logs에 전송된다. 검색 필드는 `status`(SUCCESS/ERROR), `toolName`, `errorCode`,
`auditId`(감사 JSON의 `audit_id`와 같다), `userId`, `workspaceId`, `grantId`, `clientIdHash`(URL인 `client_id`의 SHA-256 앞 16자리),
`targetCount`, `targetIds`(안전한 형식의 ID 최대 20개), `origin`, `requestId`다. 성공은 INFO, 실패는 WARN이다.
승인·영수증 등 MCP 전용 승인 단계(`McpAgentApprovalService`, `McpWikiApprovalService`)는 별도 `operation=` 로그가 없어
DB 이력으로만 남는다.
Markdown-like document and wiki fields are passed through the existing AI Markdown sanitizer before final response
sizing, so external links/images and raw HTML are neutralized while relative links, anchors, and code remain data.

## 문서 업로드 (`McpDocumentUploadTools`)

MCP 전용 업로드 경로는 없다. 세 도구는 웹 직접 업로드 API([documents/management.md](documents/management.md)의 `POST .../documents/uploads`·`/parts`·`/complete`)가 쓰는 `DocumentDirectUploadService` 메서드를 MCP 신원(검증된 user·workspace)으로 그대로 부른다. 형식(PDF·Markdown·txt, txt는 `.md`로 받음), 크기(PDF 100MB, Markdown·txt 5MB), UTF-8·`%PDF-` 검사, 용량, 멤버십·폴더 배치 권한, ticket 소유 대조, 멱등성, `content_hash` 의미가 웹과 같다. 업로드는 AI 결과가 아니므로 웹 승인 없이 complete에서 바로 문서(origin `upload`)가 된다.

| 도구 | 웹 API | 입력 | 출력 |
|---|---|---|---|
| `start_document_upload` | `POST .../uploads` | `{filename, size, folder_id\|null}` | `{ticket, part_size, part_count, next_step}` |
| `get_upload_part_urls` | `POST .../uploads/parts` | `{ticket, first_part, count(1~32)}` | `{parts:[{part_number, url}]}` (15분 presigned PUT) |
| `complete_document_upload` | `POST .../uploads/complete` | `{idempotency_key, ticket}` | 웹 `DocumentUploadResponse` 필드 + `content_hash` + `next_step` |

- MCP에만 있는 필드: `idempotency_key`(웹은 `Idempotency-Key` 헤더), `next_step`(코드 고정 안내), `content_hash`(웹 응답에 없지만 `start_pdf_conversion`의 `source_content_hash`로 필요하다. `documents.content_hash`의 `sha256:` 형식).
- 노출하지 않는 것: 웹 `abort`(버려진 multipart는 버킷 lifecycle이 정리), 가져오기 세션 헤더.
- 클라이언트는 각 part URL에 인증 헤더 없이 원본 바이트 구간을 PUT한다. 객체 Content-Type은 시작 때 서버가 정하므로 PUT에 필요 없다. HTTP를 보낼 수 없는 클라이언트에게 설명은 웹에서 올리라고 안내한다. base64 인자는 없다.
- 서버 자동 편입은 하지 않는다(`wiki_ingest.status=NOT_APPLICABLE`). 가져오기 세션과 같은 `DocumentService` 분기(`UploadAccounting.autoIngest=false`)를 탄다. 편입은 사용자 AI가 `start_ingest`, PDF 변환은 `start_pdf_conversion`으로 한다.
- 오류 코드: `UNSUPPORTED_FILE_TYPE`, `INVALID_DOCUMENT_FILENAME`, `MARKDOWN_CONTENT_TOO_LARGE`, `INVALID_MARKDOWN_CONTENT`, `INVALID_PDF`, `FILE_TOO_LARGE`, `STORAGE_QUOTA_EXCEEDED`, `DOCUMENT_WRITE_FORBIDDEN`, `UPLOAD_PARTS_INCOMPLETE`, `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS`, 다른 사용자·워크스페이스 ticket은 `HANDLE_NOT_FOUND`, 그 밖의 형식 오류는 `INVALID_INPUT`.
- 감사: `target_ids`는 `start_document_upload`의 `folder_id`(있으면)뿐이다. ticket·URL·파일 이름은 남기지 않는다.
- 승인: 새 scope나 웹 승인은 없다. 업로드 확인은 MCP 클라이언트의 도구 실행 확인(예: Claude Code의 도구별 확인 창)이 맡는다. 그래서 세 도구는 `readOnlyHint=false`, `destructiveHint=false`, `openWorldHint=false`로 쓰기임을 알리고, `idempotentHint`는 start `false`(호출마다 새 ticket), part URL·complete `true`(같은 인자·같은 key는 같은 결과)다. start·complete 설명은 업로드 전에 사용자에게 확인하라고 적는다. 서버는 확인 창이 실제로 떴는지 알 수 없다(자동 승인 모드 클라이언트 등). 신뢰 수준은 사용자가 웹 업로드 버튼을 누르는 것과 같다.

## 회의 전사 읽기 (`McpMeetingTools`)

외부 AI가 회의록을 직접 쓰도록 본인 회의의 전사만 읽힌다. 웹과 같은 소유자 전용 `MeetingService.list`/`get`을 쓰므로
같은 워크스페이스의 다른 사용자 회의는 `RESOURCE_NOT_FOUND`로 숨겨진다. 모델 호출·크레딧·녹음 원본·presign URL·실시간
스트림은 노출하지 않는다.

- `list_meetings {cursor}` → `mcp.meeting-list.v1` `{meetings[{meeting_id,display_name,source,status,has_notes,created_at}],next_cursor,data_boundary}`.
  최근 생성 순이며 페이지 크기는 `max-page-size`다.
- `get_meeting_transcript {meeting_id,cursor}` → `mcp.meeting-transcript.v1`
  `{meeting_id,display_name,document_id,source,status,live_connected,transcript_complete,segment_count,segments[{segment_id,position,status,text}],next_cursor,data_boundary}`.
  발화 순서로 `max-document-blocks-per-page` 구간과 `max-document-content-bytes` 안에서 끊고 `next_cursor`로 잇는다.
  잘라 내지 않으며, 한 구간이 한도를 넘으면 `RESPONSE_LIMIT_EXCEEDED`다. 확정 전 구간은 `status:"pending"`, `text:null`이고,
  `transcript_complete:false`면 일부 전사다. 저장된 구간에는 화자·시각 정보가 없다.

cursor는 기존 `McpCursorCodec`으로 user·workspace·grant·client·policy generation·도구(전사는 회의 ID까지)에 묶인다.
텍스트와 이름은 `AiMarkdownSanitizer`를 거친 `UNTRUSTED_WORKSPACE_CONTENT`다. 회의록 저장은 새 경로 없이 `get_document` 후
`propose_document_edit`(회의 `document_id`에 덧붙이기) 또는 `propose_plan`의 `create_document`로 제안하고 웹에서 승인한다.

채팅 음성 입력(STT)·읽어 주기(TTS)는 사용자 기기의 마이크·스피커에서 일어나는 클라이언트 기능이다. MCP는 오디오를 주고받지
않으며 Fruition이 외부 AI에 넘길 저장 음성도 없다. Fruition이 저장하는 오디오는 회의 녹음뿐이고, 위 전사 텍스트로만 제공한다.

## Internal edit context for I4 proposals

`POST /internal/agent/mcp/document-edit-context` is not an MCP tool and is never exposed through the public
catalog. It requires the existing `X-Agent-Service-Token`; no MCP bearer is accepted or forwarded. Its exact,
unknown-field-free request is:

```json
{
  "schema_version":"mcp.document-edit-context.v1",
  "actor":{"invocation_id":"<uuid-v4>","grant_id":"<uuid-v4>","user_id":"<id>","workspace_id":"<id>","client_id":"https://...","policy_generation":1,"origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"propose_document_edit"},
  "document_id":"<document id>",
  "base_version":1
}
```

The service calls Access's current grant authorization with required scope `write_propose`, binds the returned
user/workspace/client/generation/role to the actor, loads only that workspace's live document, and applies the
current `DocumentAccessPolicy.requireEdit` decision without the web role cache. It then verifies the requested
base version and the stored current-body hash before returning this exact bounded response:

```json
{
  "schema_version":"mcp.document-edit-context.v1",
  "document_id":"<document id>",
  "workspace_id":"<workspace id>",
  "current_version":1,
  "content_hash":"sha256:<64 lowercase hex>",
  "current_markdown":"<current Markdown>",
  "display_name":"<current name>",
  "parent_folder_id":null
}
```

It never returns storage URIs or recursively crawls a workspace. Missing/currently forbidden documents,
base-version drift, size limits, and inconsistent stored hashes fail closed. The AI service uses this response
only to create one deterministic `awaiting_approval` proposal; public write registration and execution remain
future work.

## I4 document proposal tool

`propose_document_edit` is registered in the public catalog and requires `write_propose`. It only creates an
`awaiting_approval` run; web approval, reservation/current authorization, execution receipts, and the
workspace-safe run preview route (`/mcp/workspaces/{workspace_id}/runs/{run_id}`) own every later step.

The input is exactly:

```json
{
  "idempotency_key":"<bounded string>",
  "document_id":"doc_... or chatdoc_...",
  "base_version":1,
  "target":{"type":"selection","start_line":1,"end_line":1},
  "instruction":"<raw untrusted instruction>",
  "replacement_markdown":"<replacement Markdown>"
}
```

Unknown fields and caller-supplied actor, provenance, hashes, status, folder, or URL values are rejected. The
handler builds its actor only from verified MCP identity, rechecks the live Access grant/scope/generation and
the document's current edit policy locally, then sends `POST /agent/runs/mcp-proposals` with only
`X-Agent-Service-Token`. It preserves raw instruction bytes, trims only `request_summary` and operation
`reason`, and creates deterministic `op-1`/`artifact-1` records. The artifact hash covers exact UTF-8 Markdown;
the canonical request hash excludes invocation ID and includes trusted actor identity and the complete proposal.

`base_version`은 `get_document`·`find_documents`의 `current_version`과 같은 본문 편집 revision
(`document_edit_states.revision`)이다. 웹 승인 견적·승인·실행 재검증도 같은 revision과 비교하며, 이름 변경·이동처럼
`documents.current_version`(메타데이터 버전)만 올리는 변경은 편집 승인을 막지 않는다. 본문이 바뀌면 revision이 올라가
`MCP target version changed.`(409)로 거절한다.

The external result is exactly
`{ "run_id": "<uuid-v4>", "status": "awaiting_approval", "review_url": "https://<configured-origin>/mcp/workspaces/<encoded-workspace-id>/runs/<encoded-run-id>" }`.
The server builds the link only from `MCP_REVIEW_ORIGIN`, verified actor workspace, and the validated AI run ID;
it never uses request/forwarding headers or places tokens, grants, prices, or proposal content in the URL. The
configured value must be an HTTPS origin with no userinfo, non-root path, query, or fragment. Missing configuration
fails before the remote proposal is created. The handler never approves or executes a run.

Static schema change record: on 2026-11-08, `mcp-tools.v1` gained this non-destructive proposal schema and
its I5 result added the required `review_url`. Static descriptions contain no workspace or user content.

## Internal I6 plan context and multi-operation proposal

`POST /internal/agent/mcp/plan-context` accepts exact `mcp.plan-context.v1`, a verified `propose_plan` actor,
an optional target folder, at most 40 unique folder/document resources, and at most 20 unique version-bound edit
documents. Document rechecks the current `PROPOSAL_CREATE` grant and `write_propose` scope, uses Access's current
role for every target/destination/root policy, and returns only the authoritative requested rows, required ancestor
chain, readable duplicate-review siblings, and requested edit bodies. Database rows, cumulative bodies, and the
whole response are bounded; version/hash drift and overflow fail the request instead of truncating it.

The strict `propose_plan` tool (`write_propose`) accepts only
`{idempotency_key,request_summary,target_folder_id,operations,artifacts}` and sends the existing
`mcp.agent-proposal.v1` endpoint with `instruction:null`, the verified actor, and the shared canonical request hash.
It supports at most 20 closed-set operations (`create_folder`, `rename_folder`, `move_folder`, `create_document`,
`rename_document`, `move_document`, `apply_document_edit`) plus exact result references and artifact shapes. Its
validated response remains `{run_id,status,review_url}`. It never approves, reserves credit, or executes.
A PDF conversion result is proposed only by `finalize_pdf_conversion` (the public `propose_plan` schema does not
advertise it): its `create_document` arguments are exactly `{display_name,folder_id,content_result_key,content_hash,
source_document_id,source_content_hash}` with no artifact. The source pair is revalidated at proposal, approval, and
save, and the save reads the result from `content_result_key` and checks `content_hash` ([mcp-pdf.md](mcp-pdf.md)).

## Internal I9 source snapshot callback

`POST /internal/documents/{document_id}/mcp-wiki-source` is a service-authenticated producer endpoint, not a
public MCP tool. It accepts only exact `mcp.wiki-source.v1` plus a trusted `start_ingest` or
`submit_lint_changes` actor and requires `X-Agent-Service-Token`. Before reading source data, Document rechecks
the current ACTIVE grant, `wiki_write` scope, policy generation, workspace membership, the tool-specific
`wiki.ingest` or `wiki.lint` permission, and document read policy through Access; request identity alone is never
sufficient. Lint is never relabeled as ingest and does not require `wiki.ingest`.

Because `start_ingest` creates an unapproved persistent wiki draft, this service-actor authorization uses the
existing `PROPOSAL_CREATE` phase with `ACTIVE_GRANT` rather than `TOOL_CALL`: token and required scope are exactly
`wiki_write`, required permission is exactly `wiki.ingest`, and approved policy generation is null. The callback
does not reuse that phase for resume, finalize, generic source reads, or approved execution.

The response contains only `schema_version`, `document_id`, `display_name`, `workspace_id`, `source_version`,
`source_content_hash`, `mime_type`, and ordered blocks `{block_id, ordinal, text, content_hash}`. `display_name` is
the document's current display name from the same read, unsanitized; AI treats it as untrusted text and uses it as the
SOURCE wiki page title. Document reads
one read-only current Markdown/text revision with a version/hash-coherent body, uses the existing Markdown block segmentation, assigns
server block IDs `B0001` onward, and hashes the exact UTF-8 source and each block as `sha256:<lowercase hex>`.
Existing configured MCP source byte/block limits apply. PDF, client-supplied source bytes/version/URLs, storage
locators, unreadable documents, inconsistent stored hashes, and unknown request fields fail closed.

## I9 wiki tools, web approval, and settlement

The six strict wiki tools are `start_ingest`, `resume_ingest`, `get_window`, `submit_window`,
`finalize_ingest`, and `submit_lint_changes`. They are registered in the public catalog, and the transport preflight
requires `wiki_write` (a `read`/`write_propose`-only grant gets 403 `insufficient_scope` before any handler runs).
Their external top-level fields are, respectively:

- `{idempotency_key,document_id}`
- `{run_id}`
- `{run_id,window_ordinal}`
- `{idempotency_key,run_id,window_id,snapshot_id,window_hash,contributions}`
- `{idempotency_key,run_id,snapshot_id,expected_submitted_window_count}`
- `{idempotency_key,target_snapshot,source_documents,operations}`

Every call builds the actor from the verified MCP token, reauthorizes the current grant/client/generation and
tool permission, rejects unknown/coerced fields, and uses the shared canonical serializer for request hashes.
Document owns ingest handles, window bindings, and lint idempotency rows. External evidence names
`document_id`; Document resolves it to the immutable server-created `snapshot_id`. Lint requires each source's
exact `{document_id,current_version,content_hash}` and retains the first server mapping for same-intent retries.
No caller may supply source bytes, snapshot mappings, URLs, paths, or identity. Successful finalization returns
only the validated AI draft fields plus a review URL built from configured `MCP_REVIEW_ORIGIN` and the route
`/mcp/workspaces/{workspace_id}/wiki-drafts/{draft_id}`.

AI wiki draft/PDF conversion API rejections reach the tool result as `{"error":"<code>"}` with the AI `error.code`
unchanged when it is in the allowlist in `McpWikiDraftClient`: the AI `McpWikiDraftError`/`McpPdfConversionError`
codes (e.g. `INVALID_EVIDENCE`, `LEXICAL_OVERLAP_REJECTED`, `INSTRUCTION_PATTERN_REJECTED`, `UNSAFE_CONTENT`,
`MERGE_TARGET_CHANGED`, `WINDOW_OUT_OF_ORDER`, `INCOMPLETE_WINDOWS`, `SNAPSHOT_MISMATCH`, `NOT_FOUND`, `EXPIRED`,
`REQUEST_TOO_LARGE`), the run/draft state name the AI returns when a run no longer accepts the command
(`READY_TO_FINALIZE`, `AWAITING_WEB_APPROVAL`, `APPLYING`, `PUBLISHED`, `NO_CHANGE`, `CONFLICTED`, `HALTED`,
`UNKNOWN`, `EXPIRED`, `CANCELLED`, `INVALIDATED`), and the relayed source callback `INVALID_SOURCE_TYPE`. Unknown
codes, non-JSON bodies, and bodies without `error.code` (including FastAPI's default 422, which only a
Document-built header/query contract bug can cause) become `DEPENDENCY_UNAVAILABLE`.

The owner web API is:

- `GET /api/workspaces/{workspace_id}/mcp/wiki-drafts/{draft_id}`
- `POST .../{draft_id}/approve` with the exact body
  `{draft_id,draft_version,draft_hash,public_state_hash}`
- `POST .../{draft_id}/reject` and `POST .../{draft_id}/cancel` with `{}`

The GET response is exact `mcp.wiki-web-draft.v1`: trusted client name, frozen quote, complete whole-draft
preview, approval/publication/settlement status, and the original hashes. External source/evidence contains only
document IDs, block IDs, and sanitized quotes; immutable snapshot IDs stay internal. Approval rechecks the
current source versions/hashes and AI target `updated_at` microsecond versions/body hashes, then atomically
creates one stable approval/operation and reserves the full configured maximum in existing KRW-milli credits.
Insufficient credit returns the existing 402 path and never creates a partial reservation. INGEST prices bind
source UTF-8 byte quantity, configured unit size/unit price/formula/version; LINT binds one configured fixed apply.
Blank price settings fail closed rather than inventing a rate.

AI verifies an approved apply through service-authenticated
`POST /internal/mcp/wiki/approvals/lookup`. The request is exact `mcp.wiki-approval-lookup.v1`; its response has
only `{schema_version:"mcp.wiki-approval-status.v1",status,reason}` where status is
`VALID|FENCED|INVALID|UNKNOWN|NOT_FOUND`. VALID requires the exact persisted EXPLICIT_WEB identity/draft/hash/
source mapping, a full OPEN MCP reservation, current operation-boundary authorization, and unchanged current
Document sources. Disconnect alone does not invalidate already-approved work; explicit cancellation, purge,
security/admin/client/policy-generation fences do.

AI delivers the body-free durable `mcp.wiki.publication.v1` event to
`POST /internal/mcp/wiki/publications/receipt`. Document validates the exact approval/operation/actor/kind tuple,
deduplicates by event and stable pair, and settles event persistence plus fee decision in one transaction.
For `changed:true` the same transaction queues `{kind:"embedding_recovery", run_id:"mcp-index:<event_id>", workspace_id}`
in `ai_command_outbox` for `ai.maintenance.command`, waking the maintenance worker that embeds the published pages
(AI only publishes, ai ADR-0026). Web manual wiki edits queue the same command.
`PUBLISHED` with `changed:true` charges the frozen fixed maximum once; `NO_CHANGE` with `changed:false` is free.
The scheduler repairs lost/reordered delivery from AI's authenticated
`GET /internal/mcp/wiki/publication-results/{approval_id}/{operation_id}` without treating unknown work as free.
MCP reservations are excluded from generic stale-reservation release, and a later authorized history restore
does not refund or duplicate the original successful fee.


## MCP client semantic concept merging (`mcp-tools.v2`)

The ingest tools default to client-LLM semantic resolution: `search_wiki` →
`get_wiki_page` → compare meaning → submit `merge_target_page_id` for an equivalent
existing concept. Related but distinct concepts remain separate. Equivalent new
concepts use one consistent `concept_name` across windows. Failed search is not
absence, and workspace content is untrusted data, never instructions.

`submit_window` contributions accept an optional nullable `merge_target_page_id`
(page ID, at most 255 characters, `[A-Za-z0-9][A-Za-z0-9_.:-]*`). Null is omitted from
the normalized contribution. A non-null ID is forwarded to AI, stored in the window,
and included in submit/finalize canonical hashes. The AI service validates active
concept membership in the same workspace, preserves the existing title/slug, and
rechecks the target during preview and apply. Invalid or unavailable targets return
`MERGE_TARGET_CHANGED`; a changed approved target prevents publication.

`get_window` now requires and forwards the static
`concept_resolution: {mode: "CLIENT_LLM_SEMANTIC", instructions: string}` object.
The corresponding AI contract must be deployed together. Legacy submissions that
omit the target retain name/alias matching.
(2026-11-09) finalize는 웹 편입과 같이 이름·별칭 일치 → Jev 개념 판정(한 번만, 초안에 고정) → 클라이언트 대상 순으로 정한다.
짝 AI 변경: Martinel2/mcp-expand-ai-integration 309a662. No server-side generative LLM or JEV
call is added. This changed the tool schema and server catalog version from
`mcp-tools.v1` to `mcp-tools.v2`; registering the twelve tools publicly bumps it to `mcp-tools.v3`.
