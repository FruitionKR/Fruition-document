package fruition.core.aihistory.service;

import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.wiki.domain.WikiPageContribution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 미리보기 시점의 상태를 서명한다. 복구 실행은 이 토큰을 받아 그사이 대상이 바뀌지 않았는지 확인한다.
 *
 * <p>복구는 되돌릴 수 없으므로 "사용자가 본 그 상태"가 아직 유효한지 확인하고 실행해야 한다.
 * 토큰에 상태를 담지 않고 <b>상태를 다시 계산해 서명을 대조</b>하므로 별도 저장이 필요 없다.
 *
 * <p>{@code app.restore.preview-token-secret}이 설정되면 그 값을 서명 키로 쓴다. 인스턴스마다
 * 같은 키를 쓰게 되어 다중 인스턴스에서도 미리보기와 실행이 서로 다른 인스턴스로 가도 토큰이
 * 유효하다. 설정하지 않으면 기동 시 무작위 키를 만든다. 이 경우 미리보기 토큰은 그 인스턴스에서만
 * 유효하고 재시작하면 무효가 된다 — 사용자는 미리보기를 다시 열면 된다.
 */
@Component
public class PreviewTokenSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PreviewTokenSigner(
            @Value("${app.restore.preview-token-secret:}") String configuredSecret) {
        byte[] secret;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * 대상 작업·모드와 영향받는 페이지들의 현재 상태를 묶어 서명한다.
     *
     * @param contributionsByPage 페이지별 전체 기여. 판정에 넘긴 것과 같은 값이어야 한다
     */
    public String sign(String operationId,
                       Map<String, List<WikiPageContribution>> contributionsByPage) {
        return hmac(canonical(operationId, contributionsByPage));
    }

    /** 실행 시점에 다시 계산한 상태가 미리보기와 같은지. */
    public boolean matches(String token, String operationId,
                           Map<String, List<WikiPageContribution>> contributionsByPage) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = sign(operationId, contributionsByPage);
        return constantTimeEquals(token, expected);
    }

    /**
     * 문서 되돌리기용. 지금 버전을 담아 그사이 문서가 편집되면 실행이 막히게 한다.
     */
    public String sign(String operationId, DocumentRestorePlan plan) {
        return hmac(canonical(operationId, plan));
    }

    public boolean matches(String token, String operationId, DocumentRestorePlan plan) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return constantTimeEquals(token, sign(operationId, plan));
    }

    private String canonical(String operationId, DocumentRestorePlan plan) {
        return operationId + "|document=" + plan.documentId()
                + ":" + plan.fromVersion() + ":" + plan.toVersion();
    }

    /**
     * 서명 대상 문자열. 페이지 순서와 기여 순서를 고정해 같은 상태면 같은 문자열이 나오게 한다.
     * 기여의 활성 여부까지 담아야 그사이 다른 복구가 끼어든 것을 잡아낸다.
     */
    private String canonical(String operationId,
                             Map<String, List<WikiPageContribution>> contributionsByPage) {
        String pages = contributionsByPage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().stream()
                        .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                        .map(c -> c.getIngestOperationId() + ":" + c.getSequenceRevision()
                                + ":" + (c.isActive() ? "1" : "0"))
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining(";"));
        return operationId + "|" + pages;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("미리보기 토큰을 서명하지 못했습니다.", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
