package com.dunwugudao.crawler.persistence.config;

import com.dunwugudao.crawler.core.model.SourceType;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SourceType 枚举 ↔ DB INT 映射（替代已移除的 MP @EnumValue 全局枚举处理器）。
 * <p>读：INT → SourceType.fromCode；写：SourceType.getCode() → INT。</p>
 */
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
