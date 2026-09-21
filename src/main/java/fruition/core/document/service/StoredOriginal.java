package fruition.core.document.service;

import org.springframework.web.multipart.MultipartFile;

/** 서버가 검증한 불변 S3 객체만 구현한다. 외부 HTTP multipart에서 만들 수 없다. */
interface StoredOriginal extends MultipartFile {
    String storageFingerprint();
    void copyTo(String targetKey) throws Exception;
}
