package fruition.core.agent.controller;

import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.service.AgentToolService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent/tools")
public class AgentToolController {

    private final AgentToolService toolService;

    public AgentToolController(AgentToolService toolService) {
        this.toolService = toolService;
    }

    @PostMapping("/read/{tool_name}")
    public ResponseEntity<Object> read(
            @PathVariable("tool_name") String toolName,
            @Valid @RequestBody AgentToolReadRequest request) {
        return ResponseEntity.ok(toolService.read(toolName, request));
    }

    @PostMapping("/execute/{tool_name}")
    public ResponseEntity<Object> execute(
            @PathVariable("tool_name") String toolName,
            @Valid @RequestBody AgentToolExecuteRequest request) {
        return ResponseEntity.ok(toolService.execute(toolName, request));
    }
}
