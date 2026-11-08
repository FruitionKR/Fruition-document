# MCP read boundary

The document service owns the protected MCP resource at `POST /mcp` and its RFC 9728 metadata at
`GET /.well-known/oauth-protected-resource/mcp`. The boundary is disabled unless `MCP_ENABLED=true`;
enabling it also requires canonical HTTPS resource/authorization/AI URIs, an exact Origin allowlist,
RSA verification keys, a cursor secret, and every positive deployment limit in `application.properties`.
`MCP_REVIEW_ORIGIN` is an explicit canonical HTTPS frontend origin used only by the proposal tools;
leaving it blank keeps the four read tools available but makes proposal invocation fail closed.

The transport is the stateless servlet transport from the official Java SDK 2.1.0. It does not issue or
accept MCP session IDs. Every HTTP request verifies an RS256 MCP-audience token, reads at most 4 MiB after
decompression to select the required `read`, `write_propose` (`propose_document_edit`, `propose_plan`) or
`wiki_write` (six wiki tools) scope, and calls Access's current
`POST /internal/mcp/grants/{grant_id}/authorize` contract before SDK protocol parsing. A missing Origin is
allowed for native clients, while any present Origin must exactly match the configured canonical list.
Forwarding headers are ignored; the servlet request itself must already represent the canonical HTTPS host.

## 공개 카탈로그 (`mcp-tools.v3`)

`McpToolCatalog.tools()`는 2026-11-09부터 다음 12개 도구를 이 순서로 등록한다: 읽기 4개(`search_wiki`,
`find_documents`, `get_document`, `get_wiki_page`), 제안 2개(`propose_document_edit`, `propose_plan`), 위키 6개
(`start_ingest`, `resume_ingest`, `get_window`, `submit_window`, `finalize_ingest`, `submit_lint_changes`). 별도 enable
플래그나 숨은 등록 경로는 없다. 제안·위키 도구는 승인 대기 run/draft와 `review_url`만 만들며, 승인·크레딧 예약·실행·정산은
아래 각 절의 웹 승인 경로에서만 일어난다. 도구 인자로 승인이나 실행을 대신할 수 없다. 모든 도구는 같은 spec wrapper로
감사 로그·오류 코드·응답 크기 제한을 공유한다. 이 카탈로그는 Document `f9882a4`와 AI `6bc6a6d`의 semantic merge 계약을
전제로 하므로 두 서비스를 함께 배포해야 한다.

The read tools are:

- `search_wiki` → AI `POST /internal/mcp/wiki/search`, with a server-created actor and only
  `X-Internal-Token`; each stored document/block locator is shape-checked and its document is rechecked for
  current workspace/read access. Published historical evidence is not invalidated by a later source edit.
- `find_documents` → bounded workspace/document-policy candidates and a stateless owner-bound cursor.
- `get_document` → bounded block-boundary chunks from the document-owned current edit state (or the immutable
  chat-export pipeline input), tied to that body version, source hash, and sanitized presentation snapshot. It does
  not require a wiki ingest snapshot.
- `get_wiki_page` → one bounded published workspace page with the seven `mcp.wiki-page.v1` fields and a
  body hash over the exact UTF-8 body returned by the tool.

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

The response contains only `schema_version`, `document_id`, `workspace_id`, `source_version`,
`source_content_hash`, `mime_type`, and ordered blocks `{block_id, ordinal, text, content_hash}`. Document reads
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
omit the target retain name/alias matching. No server-side generative LLM or JEV
call is added. This changed the tool schema and server catalog version from
`mcp-tools.v1` to `mcp-tools.v2`; registering the twelve tools publicly bumps it to `mcp-tools.v3`.
