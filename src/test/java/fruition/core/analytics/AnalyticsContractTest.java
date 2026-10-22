package fruition.core.analytics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnalyticsContractTest {
    @Test void uuidMatchesCrossLanguageRfc4122V5Fixture() {
        assertThat(AnalyticsRecorder.eventId("query_answered","query-123").toString()).isEqualTo("325fecef-e58c-5f27-9dbf-62ccd3940fde");
        assertThat(AnalyticsRecorder.eventId("ai_operation_rolled_back","success:op")).isNotEqualTo(AnalyticsRecorder.eventId("ai_operation_rolled_back","failure:op"));
    }
    @Test void arbitraryPropertiesAndMissingRequiredMetadataAreRejected() {
        var p=DocumentAnalytics.props("skill_id","skill-1","skill_run_id","run-1","scope","team","is_creator",false);
        AnalyticsSchema.validate("skill_run",p);
        for(String forbidden:List.of("name","title","body","question","answer","error","url","$set","$groups")) {
            var changed=new HashMap<>(p);changed.put(forbidden,"private");
            assertThatThrownBy(()->AnalyticsSchema.validate("skill_run",changed)).isInstanceOf(IllegalArgumentException.class);
        }
        p.remove("is_creator");
        assertThatThrownBy(()->AnalyticsSchema.validate("skill_run",p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rawFailureMessagesNeverEscapeEnumMapping() {
        assertThat(AnalyticsSchema.failureReason("private query URL and body")).isEqualTo("unknown");
        assertThat(AnalyticsSchema.failureReason("provider_unavailable")).isEqualTo("provider_error");
        assertThat(AnalyticsSchema.failureReason("deadline_exceeded")).isEqualTo("timeout");
    }
    @Test void editTypesMatchAiTargetAndOperationContract() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String target : List.of("whole_document", "selection", "current_section")) {
            var edit = mapper.createObjectNode().put("operation", "replace");
            edit.putObject("actual_target").put("type", target);
            assertThat(DocumentAnalytics.editType(edit)).isEqualTo(target.equals("whole_document") ? "rewrite" : "replace");
            edit.put("operation", "insert_after");
            assertThat(DocumentAnalytics.editType(edit)).isEqualTo("append");
        }
    }
    @Test void invalidEnvironmentDisablesCaptureWithoutBlockingProductStartup() {
        var settings = new AnalyticsSettings(true,"phc_testPublicToken","http://localhost","prod","test");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.environment()).isEqualTo("local");
    }
    @Test void onlyPublicProjectTokensEnableRelayAndDiagnosticsRedactTokens() {
        for (String token : List.of("", "phx_personalFixture", "arbitrary", "phc_")) {
            var settings = new AnalyticsSettings(true, token, "http://localhost", "local", "test");
            assertThat(settings.relayEnabled()).isFalse();
        }
        var publicSettings = new AnalyticsSettings(true, "phc_testPublicToken", "http://localhost", "local", "test");
        assertThat(publicSettings.relayEnabled()).isTrue();
        assertThat(publicSettings.toString()).doesNotContain("phc_testPublicToken");
    }
}
