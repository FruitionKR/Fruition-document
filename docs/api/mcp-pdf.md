# MCP 외부 AI PDF 변환

[MCP read boundary](mcp-read.md)

웹 PDF 변환과 같은 파이프라인을 쓰고, 그 안의 모델 판단 단계만 외부 AI(MCP 클라이언트)가 맡는다.

| 기존 단계 (AnyDoc crop-first) | MCP 경로 |
|---|---|
| 1. 전처리: Heron 특수 영역 → 표·수식·그림 crop, 쪽 본문 AnyDoc 추출, 깨짐 판정 | 그대로. 기존 converter가 기존 batch·journal로 실행 |
| 2. selective repair: 표·수식·깨진 본문을 공급사 모델로 복원 | **외부 AI**. 같은 prompt·payload·이미지를 창으로 받고 기존 검증(`valid_replacement`)과 기존 재시도 규칙(`retry_blocks`)을 거친다 |
| 3. 그림 설명(워크스페이스 `describe_pdf_figures`가 켜졌을 때) | **외부 AI**. 같은 prompt·이미지, 기존 정리(`clean_description`) |
| 4. 조립: manifest·복원 결과·원본 그림 asset → Markdown | 그대로. 서버가 조립하고 외부 AI가 쓴 Markdown을 그대로 저장하지 않는다 |
| 5. 저장: batch 결과를 저장소에서 읽어 이미지 asset 분리·sanitize·쪽 번호 바꾸기 후 원본 PDF 폴더에 저장(크기 무관) | 그대로. 웹 승인 뒤 같은 코드(`DocumentService.convertedBatchMarkdown`)로 저장한다 |

내부 공급사 호출과 내부 추론 크레딧은 없다. 문서 저장은 웹 승인 뒤에만 하며 MCP 승인 요금은 다른 MCP 계획과 같다.
창 조회·제출에는 따로 요금이 없다. 도구 등록·scope 판정·감사·응답 크기 제한은 catalog(`McpToolCatalog`)가 한다.

| 도구 | scope | 감사 대상 |
|---|---|---|
| `start_pdf_conversion` | `write_propose` | `document_id` |
| `get_conversion_window` | `read` | `conversion_id` |
| `submit_conversion_window` | `write_propose` | `conversion_id` |
| `finalize_pdf_conversion` | `write_propose` | `conversion_id` |

쓰기 도구는 호출마다 현재 grant(`McpAccessClient.authorizeProposal`)가 토큰과 같은지 본다. 세션은 AI(`/internal/mcp/pdf-conversions`)가
갖고, 모든 호출은 같은 workspace·user·grant·client·policy generation이어야 한다(아니면 `RESOURCE_NOT_FOUND`).

## `start_pdf_conversion`

입력 `{idempotency_key, document_id, source_content_hash}`. 원본은 웹 변환과 같은 판정(`viewer.canConvert`)이어야 하고
(아니면 `RESOURCE_NOT_FOUND`), hash(`documents.content_hash`, `sha256:` 형식)가 같아야 한다(아니면 `SOURCE_CHANGED`).

AI에 세션을 만들고, 아직 journal에 없는 다음 전처리 batch(`run_id=mcpconvert:<conversion>:<start>`, `restoration=external`,
provider·model 없음, `app.convert.pages-per-batch`쪽)를 기존 `ai.convert.command` outbox로 넣는다. converter는 전처리만 하고
command를 발행하지 않는다. 같은 인자로 다시 부르면 같은 세션의 상태를 돌려주고 다음 batch를 잇는다(진행 확인). 응답 `mcp.pdf-conversion.v1`: `conversion_id`, `state`(`PREPARING|READY|FAILED|FINALIZED`),
`source_document_id`, `source_content_hash`, `total_pages`, `prepared_pages`, `window_count`, `error_code`, `next_step`.

## `get_conversion_window`

입력 `{conversion_id, window_ordinal}`. `READY` 전에는 `CONVERSION_NOT_READY`. 응답: `window_id`, `window_hash`, `kind`(`repair|figure`),
`lane`(`text|table|equation|figure`), 원본 기준 `page`, 기존 복원 prompt(`instructions`)와 `payload`, `result_schema`, `images`,
`images_expire_at`, `submitted_attempts`, `max_attempts`(1), `data_boundary`. `window_count`는 재시도 창이 생기면 늘어난다.

`images`는 전처리 묶음(`tmp/convert/mcp/<workspace>/<conversion>/`)의 PNG 하나씩에 대한 10분짜리 서명 GET 주소다. 다른 경로는
서명하지 않는다. 주소는 bearer와 같아 로그에 남기지 않는다. local 모드에서 서버 안 저장소 주소(예: docker의 `http://minio:9000`)가
클라이언트에서 닿지 않으면 `MCP_PDF_SOURCE_ENDPOINT`(`app.mcp.pdf-source-endpoint`)에 클라이언트가 닿는 MinIO 주소를 넣는다.
서명은 Host까지 묶으므로 그 주소로 서명한다(region `us-east-1`). aws 모드에서는 설정하지 않는다(설정하면 기동 실패).

## `submit_conversion_window`

입력 `{idempotency_key, conversion_id, window_id, window_hash, result}`. `result`는 창의 `result_schema`
(복원: `{results:[{block_id, action: keep|replace, replacement}]}`, 그림: `{alt, description}`)다. 모양이 틀리면 `INVALID_INPUT`,
창이 바뀌었으면 `STALE_WINDOW`, 이미 제출한 창이면 `ATTEMPTS_EXHAUSTED`(기존 복원처럼 창마다 한 번 묻는다). 응답의
`rejected_block_ids`는 기존 검증에서 떨어진 block이다. 다시 묻기는 웹 복원과 같은 재시도 규칙(AI `retry_blocks`)을 따른다:
떨어진 block마다, 이어진 표는 첫 조각이 받아들여진 교체가 아니면 조각마다 한 블록 재시도 창이 창 목록 뒤에 붙고
`new_window_ordinals`로 그 번호를 돌려준다. 재시도 창과 그림 창은 더 묻지 않는다. 재시도에서도 떨어진 block은 기존 복원과 같이
"원문으로 대신함"·"자동 복원 실패" 안내로 남는다.

## `finalize_pdf_conversion`

입력 `{idempotency_key, conversion_id, display_name|null(=PDF 이름 - .pdf)}`. 저장 폴더는 고를 수 없다. 웹 변환처럼 원본 PDF와
같은 폴더다. 재시도 창까지 모든 창이 제출돼야 한다(아니면 `WINDOWS_NOT_SUBMITTED`). AI가 묶음마다 기존 조립(`assemble`)과 이미지
내장을 하고 AnyDoc 원문에 있던 링크만 남기고 sanitize한 결과를, 쪽 번호는 묶음 안 번호 그대로 `[{page_start, markdown}]` JSON으로
`tmp/mcp-convert/<workspace>/<conversion>/final.json`에 둔다. 여기서는 그 key가 이 세션 위치인지와 원본 변환 권한·hash를 다시 본
다음, `create_document` 한 작업짜리 `propose_plan`을 제출한다. 작업 인자는
`{display_name, folder_id(=PDF 폴더), content_result_key, content_hash, source_document_id, source_content_hash}`이고 artifact는 없다.
AI는 key·hash가 같은 사용자의 조립 결과이고 finalize 뒤 6일 안일 때만 받는다. 본문을 승인 요청에 싣지 않으므로 크기 상한이 없다.
승인 화면 미리 보기(AI `edit-previews`)는 다른 수정안과 같은 모양으로 AI가 이 결과를 읽어 hash를 확인하고 만든다(이미지 바이트는
줄여 보여 준다). 계획은 다른 MCP 수정안처럼 run 생성 + 1일에 만료한다. 결과 object는 `tmp/` lifecycle(7일)이 지우므로 승인할 수
있는 동안에는 항상 남아 있다.

응답 `{run_id, status:"awaiting_approval", review_url, conversion_id, restoration_summary, next_step}`.
`restoration_summary`는 `broken_pages`, `repaired_pages`, `fallback_pages`, `rejected_block_ids`다.

## 승인과 실행

- 승인 견적·승인·실행마다 `McpAgentApprovalService`가 원본을 다시 본다. 권한이 없어졌으면 `PERMISSION_REVOKED`(403),
  hash가 바뀌었으면 409다.
- 실행(`AgentToolService`, MCP 승인 경로만)은 `content_result_key`가 이 워크스페이스의 `tmp/mcp-convert/` 아래인지 보고
  `DocumentService.createConvertedMarkdownInCurrentTransaction`으로 같은 트랜잭션에서 원본(hash·폴더)을 다시 확인한다. 웹 변환의
  `readConvertResult`로 결과를 읽어 승인된 `content_hash`와 대조하고(다르면 409), 묶음마다 웹 변환 batch와 같은 코드(data: 이미지
  asset 분리 → 변환본 sanitize → `ConvertedMarkdownChunks.renumberPages`)로 이어 붙여 크기와 관계없이 새 Markdown 문서
  (`ai_origin=agent`)로 원본 PDF 폴더에 만들고 `source_document_id`에 원본 PDF를 연결한다. 영수증·멱등 재생은 다른 MCP 작업과 같다.
- 공개 `propose_plan` 스키마에는 원본 연결 `create_document`가 없다. 이 작업은 `finalize_pdf_conversion`만 제안한다.
- 내부 Agent 실행 경로는 `source_document_id`가 있는 `create_document`를 받지 않는다.
- 자동 위키 편입(`auto_ingest_pdf_on_convert`)은 내부 모델 편입이라 시작하지 않는다. 저장 뒤 외부 AI가 `start_ingest`로 편입한다.
- 전처리 batch 결과 이벤트(`run_id` `mcpconvert:`)는 문서 변환 대기열이 무시한다.
- 원본 PDF 삭제: 웹 변환도 원본 PDF 삭제로 변환을 취소하지 않는다(취소는 변환 중인 placeholder 삭제 때 `cancelConvertOnDelete`).
  MCP 변환은 승인 전까지 placeholder가 없고, 전처리 batch는 클라이언트의 `start_pdf_conversion` 호출마다 원본을 확인한 뒤 하나씩
  넣으므로 원본이 삭제되면 다음 batch를 넣지 않는다. 창 조회·finalize·승인·실행도 원본을 다시 확인해 거절한다.
