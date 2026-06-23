package com.zwbd.agentnexus.sdui.workflow.repo;

import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NodeWorkflowDefinitionRepository extends JpaRepository<NodeWorkflowDefinitionEntity, String> {
    List<NodeWorkflowDefinitionEntity> findAllByOrderByUpdatedAtDesc();
}
