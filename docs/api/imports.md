# Imports API

[서비스 문서](../README.md) · [API 목차](README.md)

Notion "Markdown & CSV" 내보내기를 가져온다(alpha MVP). zip 해석·폴더 구조·CSV 표 변환은 클라이언트가 맡고, 서버는 **가져오기 세션** 단위로
한도를 다르게 세고 만든 문서를 표시한다. 하위 페이지가 있는 페이지는 폴더 + 같은 이름의 첫 문서로, CSV 데이터베이스는 표 문서 하나로 만든다(클라이언트).
모든 API는 Bearer access token이 필요하고 워크스페이스 구성원이어야 한다(아니면 `404 WORKSPACE_NOT_FOUND`). 별도 문서 생성 권한은 없고
문서 생성(`POST /documents/markdown`)과 같은 멤버십 검사를 쓴다. 세션은 만든 사용자 본인만 쓰고 볼 수 있다(남의 세션은 `404 IMPORT_NOT_FOUND`).

## 흐름

1. `POST /imports`로 세션을 연다. 알린 `total_bytes`가 남은 저장 공간을 넘으면 파일을 하나도 올리기 전에 `413`이다.
2. 응답의 `import_id`를 `X-Fruition-Import-Id` 헤더로 붙여 쓴다.
   - `POST /documents/markdown`: 문서 생성. 항목 1개.
   - `POST /folders`: 폴더 생성. 항목으로 세지 않는다.
   - `PUT /documents/{id}/content`: 본문·이미지 저장. 그 세션이 만든 문서에만 쓸 수 있고, 새로 들어간 이미지마다 항목 1개.
3. `POST /imports/{id}/complete`로 닫는다. 이후 그 세션으로 쓰면 `400 IMPORT_INVALID`.
4. 사용자가 원할 때만 `GET .../ingest-estimate`로 예상 비용을 보여 주고 `POST .../ingest`로 편입한다.

## 한도

| 한도 | 일반 업로드 | 가져오기 세션 쓰기 |
|---|---|---|
| 하루 업로드 개수 | 없음 | 없음 |
| 세션 항목(문서+이미지) | - | 세기만 하고 상한 없음(`items_used`) |
| 저장 공간(5GB, 사용자 단위) | 적용 | 적용(`413 STORAGE_QUOTA_EXCEEDED`) |
| Markdown 5MB, 이미지 10MB, 저장당 이미지 20개, 요청 100MB | 적용 | 적용 |
| 자동 편입(워크스페이스 설정) | 업로드 Markdown만 | **하지 않음** |

헤더 값이 열린 내 세션(같은 워크스페이스, 만료 전, 완료 전)이 아니면 일반 한도로 대신 처리하지 않고 `400 IMPORT_INVALID`다.
세션은 생성 24시간 뒤 만료된다. 항목 수는 쓰기와 같은 트랜잭션에서 세므로 거절·실패한 쓰기는 세지 않는다.
하루 업로드 개수 한도는 모든 업로드 경로에서 없앴다. V86의 `user_upload_events` 테이블은 더 쓰지 않지만 expand-only 원칙으로 남겨 둔다.

## API

| API | 동작 |
|---|---|
| `POST /api/workspaces/{workspace_id}/imports` | body `{"source":"notion","item_count":<int>,"total_bytes":<long>}` → `201 {"import_id","status":"open","expires_at","max_items":null,"quota":{"used_bytes","limit_bytes"}}`. 항목 상한이 없어 `max_items`는 항상 `null`(계약 안정성을 위해 필드 유지). `limit_bytes`는 한도가 꺼져 있으면 `null`. `source`가 notion이 아니거나 값이 없거나 음수면 `400 INVALID_REQUEST`, `used_bytes + total_bytes` > 한도면 `413 STORAGE_QUOTA_EXCEEDED` |
| `POST /api/workspaces/{workspace_id}/imports/{import_id}/complete` | body `{"created","failed","skipped"}`(클라이언트 집계, 기록용, 음수면 `400 INVALID_REQUEST`) → `200 {"import_id","status":"completed","document_count"}`. `document_count`는 서버가 센 이 세션의 생성 문서 수(휴지통 포함). 이미 완료했으면 같은 응답(처음 기록한 집계 유지) |
| `GET /api/workspaces/{workspace_id}/imports/{import_id}` | `200 {"import_id","source","status","created_at","expires_at","completed_at","max_items":null,"item_count","items_used","document_count","document_ids","created","failed","skipped"}`. `status`는 `open`·`completed`·`expired`(열린 채 만료). `item_count`는 생성 때 알린 수, `items_used`는 실제 센 수. `created`·`failed`·`skipped`는 완료 전 `null` |
| `GET /api/workspaces/{workspace_id}/imports/{import_id}/ingest-estimate` | `200 {"document_count","estimated_cost_usd","estimated_credits"}`. `document_count`는 아직 편입 요청한 적 없는 휴지통 밖 가져온 문서 수. `estimated_credits` = `app.billing.estimate.document` × 문서 수(milli-KRW, `/users/me/credits`의 `*_krw_milli`와 같은 단위). `estimated_cost_usd` = 같은 값을 최신 환율로 USD 환산(환율을 못 읽으면 `null`). 문서 길이와 무관한 상한 기반 예상치다 |
| `POST /api/workspaces/{workspace_id}/imports/{import_id}/ingest` | body `{"document_ids":[...]}`(선택) → `202 {"queued","remaining"}`. 문서마다 수동 위키 편입(`POST /documents/{id}/ingest`)과 같은 경로라 `wiki.ingest` 권한·AI 사용 한도·크레딧 처리가 같다. 한 번에 20개까지: `document_ids`가 비었거나 20개를 넘거나 이 세션 문서(휴지통 밖)가 아닌 ID가 있으면 `400 INVALID_REQUEST`. 생략하면 아직 편입하지 않은 문서 중 앞 20개. 첫 문서부터 거절되면 그 오류(`403`·`402 INSUFFICIENT_CREDIT`·`429 AI_USAGE_LIMIT_EXCEEDED` 등)를 그대로 주고, 일부를 넣은 뒤 거절되면 거기서 멈추고 넣은 수만 준다. 이미 처리 중인 문서는 건너뛴다. `remaining`이 0이 될 때까지(429면 잠시 뒤) 다시 부른다 |

## 오류 코드

| 코드 | HTTP | 문구 |
|---|---|---|
| `IMPORT_INVALID` | 400 | 가져오기 세션이 없거나 만료·완료되었습니다. 가져오기를 다시 시작해 주세요. (세션 밖 문서에 쓰면: 이 가져오기 세션에서 만든 문서가 아닙니다.) |
| `IMPORT_NOT_FOUND` | 404 | 가져오기 기록을 찾을 수 없습니다. |

## 저장

`document_imports`(V90)가 세션을 담고 `documents.import_id`(V90, 인덱스는 V91 CONCURRENTLY)가 세션에서 만든 문서를 가리킨다.
가져온 문서의 `origin`은 `notion_import`다.

진입점: `src/main/java/fruition/core/imports/DocumentImportController.java`, 처리: `DocumentImportService.java`,
세션 확인·항목 계산: `ImportSessionStore.java`(문서 쓰기 경로 `DocumentService`·`DocumentAssetContentService`·`FolderController`가 쓴다).
