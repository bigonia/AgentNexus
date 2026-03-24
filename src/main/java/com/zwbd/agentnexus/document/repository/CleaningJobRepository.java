package com.zwbd.agentnexus.document.repository;

import com.zwbd.agentnexus.document.entity.CleaningJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:54
 * @Desc:
 */
@Repository
public interface CleaningJobRepository extends JpaRepository<CleaningJob, Long> {}
