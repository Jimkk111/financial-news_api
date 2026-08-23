package com.financial.news.utils;

import com.financial.news.model.content.Block;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * MyBatis TypeHandler — 将 List&lt;Block&gt; 与 MySQL LONGTEXT 互转
 *
 * @author financial-news
 * @since 1.0.0
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.LONGVARCHAR)
public class BlockListTypeHandler extends BaseTypeHandler<List<Block>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Block> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, ContentCodec.toJson(parameter));
    }

    @Override
    public List<Block> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return ContentCodec.fromJson(rs.getString(columnName));
    }

    @Override
    public List<Block> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return ContentCodec.fromJson(rs.getString(columnIndex));
    }

    @Override
    public List<Block> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return ContentCodec.fromJson(cs.getString(columnIndex));
    }
}
