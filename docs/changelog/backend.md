# Backend 변경 기록

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
