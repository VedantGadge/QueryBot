package com.vedant.querybot.repository;

import com.vedant.querybot.entity.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {
}
