# Backend 변경 기록

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
