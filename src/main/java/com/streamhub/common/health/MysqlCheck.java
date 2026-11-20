package com.streamhub.common.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 执行 SELECT 1：能拿到连接并跑通一条 SQL，就说明数据库可用。 */
@Component
public class MysqlCheck implements DependencyCheck {
    private final JdbcTemplate jdbc;

    public MysqlCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public void check() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
