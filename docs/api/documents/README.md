# Documents API

[서비스 문서](../../README.md) / [document-svc](../README.md)

문서 API를 기본 관리, 본문·편집, 삭제·버전 이력으로 나눈다.

채팅 Wiki page화로 만들어진 `chat_export` 문서는 목록·상세에 확인용으로 노출하되 읽기 전용이다
(`editable: false`). 자세한 이유와 거절 규칙은 [Content](content.md)를 본다.

| 구분 | API 수 | 역할 |
|---|---:|---|
| [Management](management.md) | 8 | 목록·생성·업로드·조회·복제·이동·이름 변경 |
| [Content](content.md) | 9 | 본문·block·asset·원본·내보내기·편집 잠금 |
| [History](history.md) | 7 | 삭제·휴지통·복구·diff·버전 이력 |
