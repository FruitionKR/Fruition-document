# Backend 변경 기록

## 2026-09-30 (수정: 변환 placeholder pipeline run 조회 오류)

- `DocumentPipelineRunReconciler`가 PDF→Markdown 변환 placeholder 문서(runId `convert:doc_…`)까지 AI pipeline run 상태 API에 조회해, 변환이 끝날 때까지 3초마다 500(`invalid input syntax for type uuid`) 오류 로그가 반복되던 문제를 고쳤습니다. `convert:` runId는 로컬 값이라 변환 작업자와 `AiTaskCancellationService`가 정리하므로 reconciler는 건너뜁니다. DB·API 변경은 없습니다.
- `convert:` runId가 AI 클라이언트에 전달되지 않는 단위 테스트 1개를 추가했습니다.

## 2026-09-28 (기능: 채팅 음성 입력)

- `POST /api/workspaces/{workspace_id}/speech/transcriptions`를 추가했습니다. 오디오 bytes(webm·mp4·mpeg·wav, 24 MiB 이하)를 AI 파일 전사에 중계해 `{text}`를 반환합니다. 음성과 텍스트는 저장하지 않고 질의·Agent에 자동 제출하지 않습니다. 인식된 말이 없으면 빈 문자열입니다.
- 형식(415)·빈 음성(422)·크기(413)·멤버십을 AI 호출 전에 검사합니다. AI 오류는 입력 오류는 그대로, 모델 실패는 502, 연결·인증 문제는 503으로 바꾸며 제공자 원문은 노출하지 않습니다.
- 설정 `SPEECH_TRANSCRIPTION_ENDPOINT`, `SPEECH_TRANSCRIPTION_TIMEOUT_SECONDS`(기본 150초)를 추가했습니다. DB 변경은 없습니다.
- 가짜 AI HTTP 서버 테스트 5개를 추가했고, 로컬 실제 AI(OpenAI 모델)로 한국어 m4a·wav·27초 발화·무음을 받아써 확인했습니다. document 경유와 AI 직접 호출의 지연 차이는 없었습니다. 전체 903개 테스트와 OpenAPI 스냅샷 비교를 통과했습니다.
## 2026-09-28 (기능: 회의 녹음 원본·파일 전사·삭제)

- 회의 API 3개를 추가했습니다: 녹음 원본 업로드(`PUT .../meetings/{id}/recording`), 재생 주소(`GET .../recording-url`, 5분 presigned), 회의 삭제(`DELETE .../meetings/{id}`).
- 녹음 파일 회의는 업로드하면 `transcribing`이 되고, 작업자가 `FOR UPDATE SKIP LOCKED`로 한 건씩 선점해 AI 파일 전사를 부른 뒤 문장을 약 1,000자 단위로 묶은 구간으로 저장합니다(회의록 AI 1,000구간 한도). 전사가 100,000자를 넘으면 `failed`로 끝납니다. 실패하면 `failed`와 사유를 남기고 다시 올릴 수 있습니다. 실시간 회의는 받아쓰기가 끝난 뒤 원본만 한 번 보관합니다.
- 원본은 업로드마다 새 키(`recording-{uuid}.{ext}`)에 저장하고 회의 삭제 전까지 보관하며 크기를 기록합니다. 재업로드로 교체된 이전 원본과 상태 변경에 실패한 업로드 객체는 바로 지웁니다. 삭제는 원본을 먼저 지우고 실패하면 아무것도 지우지 않습니다. 받아쓰기 연결 중에는 업로드·삭제를 409로 거절합니다.
- V56: `meetings`에 원본·전사 상태 컬럼 추가(expand-only, 처음 V54였으나 dev의 V52·V53과 겹쳐 번호만 옮김). 설정 `SPEECH_TRANSCRIPTION_ENDPOINT`(채팅 음성 입력과 같은 키). 호환성·롤백·실제 AI 검증 기록은 `docs/db/v56-meeting-recordings-2026-09-28.md`에 있습니다.
- 실제 MinIO와 가짜 AI 서버로 통합 테스트 6개를 추가했고, 로컬 실제 AI로 27초 녹음 → 4문장 전사(약 11초) → 회의록 초안 → 새 문서 → 재생 → 삭제를 확인했습니다. 전체 916개 테스트와 OpenAPI 스냅샷 비교를 통과했습니다.

## 2026-09-28 (기능: 회의록 초안과 저장)

- 회의록 초안 API 4개를 추가했습니다: 생성(`POST .../notes`), 최신 조회(`GET .../notes`), 기존 문서 끝 추가 미리보기(`POST .../notes/{version}/append-preview`), 저장(`POST .../notes/{version}/apply`).
- 초안은 확정 전사로 AI 회의록 API를 불러 만들고 버전별로 보관합니다. AI 호출은 트랜잭션 밖에서 하며 결과는 자기 버전 행에만 써서 늦게 끝난 이전 생성이 새 초안을 덮지 않습니다. 다시 만들기가 실패해도 마지막 성공 초안은 저장할 수 있습니다. 누락·비정상 종료 전사는 `allow_partial=true`일 때만 초안을 만듭니다.
- 문서 본문은 AI 응답의 markdown 대신 요약·결정 사항·할 일·미결 사항 배열로 다시 만들어 근거 ID를 넣지 않습니다.
- 저장은 기존 Markdown 문서 생성(`create`)과 본문 저장(`append`, `base_revision`·`revision_write_id`)을 그대로 쓰고, 대상·본문을 먼저 기록해 같은 요청 재시도가 같은 저장을 반복합니다. 미리보기 이후 문서가 바뀌면 409, 최신이 아닌 초안은 409, 한 버전은 한 번만 저장합니다.
- V55: `meeting_notes` 추가(expand-only, 처음 V53이었으나 dev의 V52·V53과 겹쳐 번호만 옮김). 설정 `MEETING_NOTES_ENDPOINT`, `MEETING_NOTES_TIMEOUT_SECONDS`(기본 150초). 호환성·롤백·실제 AI 검증 기록은 `docs/db/v55-meeting-notes-2026-09-28.md`에 있습니다.
- 가짜 AI 서버 통합 테스트 6개를 추가했고, 로컬 실제 AI로 받아쓰기 → 초안(약 20초) → 기존 문서 끝 추가·새 문서 저장을 확인했습니다. 전체 910개 테스트와 OpenAPI 스냅샷 비교를 통과했습니다.

## 2026-09-28 (기능: 회의 실시간 받아쓰기)

- 회의 생성·조회·ticket 발급 API와 `WS /api/meetings/{meeting_id}/live`를 추가했습니다. document-svc가 AI 실시간 전사 WebSocket에 연결마다 1:1로 중계하고, 확정 문장을 core_db에 저장한 뒤에만 브라우저에 전달합니다. 회의는 만든 사람만 조회할 수 있습니다.
- 브라우저 WebSocket은 Authorization 헤더를 보낼 수 없어 60초 일회용 ticket(Redis `GETDEL`)과 Origin(CORS 허용 목록)으로 인증합니다. 회의당 연결 1개는 Redis 잠금(90초 TTL, 30초 연장)으로 보장합니다.
- 발화 순서는 AI `committed` 시점의 position으로 정하고, 구간 ID에 연결 순번을 붙여(`s{n}_`) 재연결 사이 충돌을 막습니다. 같은 구간에 다른 확정 문장이 오면 덮어쓰지 않고 연결을 닫으며, `finished`의 구간 수와 저장 수가 같을 때만 정상 종료로 기록합니다.
- V54: `meetings`, `meeting_streams`, `meeting_segments`를 추가했습니다(처음 V52였으나 dev의 V52·V53과 겹쳐 번호만 옮김). 새 테이블만 만들며 기존 테이블을 잠그거나 바꾸지 않습니다. 운영 runtime role에 새 테이블 SELECT/INSERT/UPDATE/DELETE 권한이 필요합니다. 로컬 호환성·롤백 검증은 `docs/db/v54-meetings-2026-09-28.md`에 있습니다.
- `spring-boot-starter-websocket` 의존성과 `SPEECH_LIVE_ENDPOINT` 설정을 추가했습니다. 배포 경로(WebSocket 직접 연결, ALB idle timeout)는 platform ADR-0022를 따릅니다.
- 같은 앱 안의 가짜 AI WebSocket으로 역순·중복·충돌 완료, 재연결 순서, 종료 개수 불일치, ticket 재사용·Origin·동시 연결 거절, 1초(48,000 bytes) frame을 검증했습니다. 전체 904개 테스트와 OpenAPI 스냅샷 비교를 통과했습니다. 실제 AI·모델 연결과 AWS 경로는 검증하지 않았습니다.

## 2026-09-22 (기능: 부모 폴더별 파일·폴더 이름)

- V51에서 파일과 폴더가 부모별 공유 namespace를 사용합니다. 같은 부모의 대소문자·NFC 정규화 이름 충돌은 409로 거절하고 다른 부모의 동명 항목은 허용합니다.
- 문서 업로드·목록·상세 및 트리 응답에 `folder_id`를 추가하고, 복제·채팅 문서 자동 이름도 같은 부모를 기준으로 선택합니다.
- 최신 main 통합 후 전체 899개 테스트와 bootJar, 로컬 V50→V51 및 실제 HTTP 19건을 통과했습니다. 기존 교차 종류 이름 충돌은 migration을 중단하며 자동 삭제·이름 변경하지 않습니다. 배포 전 조회와 DB 권한 주의사항은 `docs/db/v51-folder-names-2026-09-22.md`를 참고하세요.

## 2026-09-22 (수정: PDF multipart 완료 요청 MalformedXML)

- 운영 AWS S3에서 PDF multipart 완료(`POST /documents/uploads/complete`)가 `MalformedXML`로 500을 반환하던 문제를 수정했습니다. `MultipartStorage.finish`가 ListParts 응답에서 역직렬화한 `Part`(Size·LastModified 포함)를 그대로 `CompleteMultipartUpload` 본문으로 직렬화했는데, S3 완료 스키마는 `PartNumber`·`ETag`만 허용합니다. MinIO는 이를 무시해 로컬에서는 드러나지 않았습니다.
- 완료 요청은 이제 조각 번호와 ETag만 담은 `Part`로 다시 구성합니다. 조각 수·크기·순서 검증은 기존과 같이 ListParts 결과로 수행합니다.
- 회귀 테스트: AWS 형식 ListParts 응답을 실제 SDK로 역직렬화한 조각으로 완료 요청을 보내고, 전송된 XML에 `Size`·`LastModified`가 없고 `PartNumber`·`ETag`만 있는지 확인합니다. 같은 조각을 그대로 직렬화하면 `Size`·`LastModified`가 포함됨을 대조합니다.

## 2026-09-21 (수정: PDF multipart 업로드 AWS 자격 증명)

- 운영(EKS IRSA)에서 `POST /documents/uploads`가 `NullPointerException: AccessKey must not be null`로 500을 반환하던 문제를 수정했습니다. `MultipartStorage`가 `ChainedProvider(AwsEnvironmentProvider, IamAwsProvider)`를 직접 구성해, 환경변수 키가 없을 때 MinIO 8.5.7이 던지는 NPE가 IAM(web identity) 단계로 넘어가지 못했습니다.
- `MinioConfig.asyncClient`를 추가해 `MinioClient`와 같은 credentials mode·region 검증과 `awsCredentialsProvider` 체인을 multipart 클라이언트에도 적용합니다. 로컬 MinIO(`local` mode)는 기존과 같이 access/secret key를 사용합니다.
- 회귀 테스트 `MultipartStorageTest`: 정적 AWS 키 없이 web identity 토큰만으로 STS를 거쳐 multipart 시작 요청이 IRSA 자격 증명(`X-Amz-Security-Token` 포함)으로 서명되는지, region 누락·알 수 없는 mode가 거부되는지 검증합니다.

## 2026-09-21

- PDF S3 multipart 직접 업로드 API(시작·조각 URL 발급·완료)를 추가했습니다. 서버가 파트 번호·크기, 최종 크기·MIME·PDF 헤더, 사용자·workspace를 검증하고 특정 객체 버전을 고정해 S3 내부에서 원본 경로로 복사합니다. 업로드 티켓은 24시간, 조각 URL은 15분 유효합니다.
- AWS 저장소에서는 converter `/convert-source-batch`에 원본 URL·크기·완료 페이지만 전달해 페이지 묶음 단위로 변환합니다. 원본 전체를 JVM에 내려받지 않습니다.
- V50: `document_convert_queue`에 `completed_pages`, `total_pages`, `attempts`, `updated_at`, `next_attempt_at`(모두 NOT NULL DEFAULT)과 claim 인덱스를 추가했습니다. expand-only이며 롤백 시 제거할 필요가 없습니다. 리허설 기록은 `docs/db/v50-rehearsal-2026-09-21.md`에 있습니다.
- 변환 실패 시 완료한 묶음 다음부터 최대 3회 자동 재시도하고, heartbeat가 10분 이상 끊긴 처리 중 작업은 pending으로 회수합니다.
- 변환 Markdown을 UTF-8 기준 64KiB 문서로 나누어 기존 AI 큐에 등록하고(`origin=convert_part`), 본문의 PNG/JPEG/GIF data URI는 S3 asset으로 분리합니다.
- 실제 MinIO 65MiB(64MiB+1MiB) multipart 통합, 체크포인트 재개, 실패 시 checkpoint 유지, UTF-8 분할 테스트를 포함해 전체 886개 테스트와 bootJar를 통과했습니다.
