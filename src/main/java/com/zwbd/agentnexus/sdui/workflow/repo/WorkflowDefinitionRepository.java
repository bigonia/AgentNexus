package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.model.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {
    List<WorkflowDefinition> findAllByOrderByUpdatedAtDesc();
}
