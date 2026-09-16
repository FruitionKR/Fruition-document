package fruition.core.skill.controller;

import fruition.core.aihistory.exception.InvalidCallbackTokenException;
import fruition.core.skill.dto.SkillReferenceReadRequest;
import fruition.core.skill.dto.SkillReferenceReadResponse;
import fruition.core.skill.service.SkillReferenceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/agent/skill-authoring/references")
public class SkillReferenceController {

    private final SkillReferenceService referenceService;

    public SkillReferenceController(SkillReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @PostMapping("/read")
    public ResponseEntity<SkillReferenceReadResponse> read(
            @Valid @RequestBody SkillReferenceReadRequest request) {
        return ResponseEntity.ok(referenceService.read(
                request.workspaceId(), request.userId(), request.documentId()));
    }
}

@Component
class SkillReferenceTokenFilter extends OncePerRequestFilter {

    private static final String PATH = "/internal/agent/skill-authoring/references/read";

    private final byte[] serviceToken;
    private final HandlerExceptionResolver exceptionResolver;

    SkillReferenceTokenFilter(
            @Value("${app.skill.agent-token}") String serviceToken,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver
    ) {
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = request.getHeader("X-Agent-Service-Token");
        if (serviceToken.length == 0 || token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), serviceToken)) {
            exceptionResolver.resolveException(request, response, null, new InvalidCallbackTokenException());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
