package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;

/**
 * Сборка ранних collaborator'ов: gate, capture, client API mounts (без OS-процессов).
 */
public interface CoreCollaboratorsBootstrap {

    void assemble(ChildProcessesContext processes);
}
