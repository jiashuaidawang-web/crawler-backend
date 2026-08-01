package com.dunwugudao.crawler.persistence.handler;

import com.dunwugudao.crawler.core.model.SourceType;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SourceType ⇄ SMALLINT(data_source) 的类型处理器。
 * 字段以 {@code @TableField(typeHandler = SourceTypeTypeHandler.class)} 声明后，
 * MyBatis-Plus 在读写 crawl_task.source 列时使用本处理器。
 */
@MappedTypes(SourceType.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public class SourceTypeTypeHandler extends BaseTypeHandler<SourceType> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, SourceType parameter, JdbcType jdbcType) throws SQLException {
        ps.setInt(i, parameter.getCode());
    }

    @Override
    public SourceType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : SourceType.fromCode(code);
    }

    @Override
    public SourceType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : SourceType.fromCode(code);
    }

    @Override
    public SourceType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : SourceType.fromCode(code);
    }
}
